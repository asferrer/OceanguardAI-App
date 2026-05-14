"""OceanGuard AI - Fine-tuning Etapa 2: refinamiento materiales + clases finas + (opc) VQA mix.

Modelo base:   adapter de Etapa 1 (outputs/lora_etapa1/adapter) sobre unsloth/gemma-4-E2B-it
Dataset:       ~449 samples curated 8-class + (opcional) 10-15% LLaVA-Instruct general
Estrategia:    LoRA r=8 + abre vision encoder (finetune_vision_layers=True).
               LR mas bajo, menos pasos, mas eval. Mezcla VQA via interleave_datasets.
Hardware:      RTX 5090 32GB (Blackwell sm_120, torch 2.7.0+cu128)
Tiempo est.:   ~1-2h con 449 samples (3 epochs, bs=1, grad_accum=4)

Comando para lanzar (cuando Etapa 1 termine):
    cd C:/Users/aleja/Desktop/Doctorado/OceanguardAI-App/finetune
    python train_etapa2.py --config configs/etapa2.yaml

Validacion sin GPU (smoke test imports):
    python -c "from unsloth import FastVisionModel; print(0)"

NOTA: Etapa 2 carga el adapter de Etapa 1 y aplica un SEGUNDO conjunto de LoRA
encima. Si el config indica `merge_etapa1_first: true`, primero hace merge del
adapter de etapa 1 dentro del modelo base y luego anade un LoRA fresco. Esto es
mas limpio para evitar layered-LoRA quirks.

Referencias:
- Catastrophic forgetting in MLLM: https://yx-s-z.github.io/emt/
- Empirical study (Zhai 2024):     https://proceedings.mlr.press/v234/zhai24a/zhai24a.pdf
- LLaVA-Instruct-150K dataset:     https://huggingface.co/datasets/liuhaotian/LLaVA-Instruct-150K
"""
from __future__ import annotations

import os
import sys

os.environ.setdefault("TORCH_CUDA_ARCH_LIST", "12.0")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
os.environ.setdefault("HF_HUB_ENABLE_HF_TRANSFER", "1")
# Workaround Blackwell sm_120 + triton 3.6.0 (issue unsloth #5154)
os.environ.setdefault("TORCHDYNAMO_DISABLE", "1")
os.environ.setdefault("UNSLOTH_COMPILE_DISABLE", "1")
# Reduce fragmentación CUDA (creep VRAM con variable image sizes / variable seq lengths)
os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True,max_split_size_mb:512")

from unsloth import FastVisionModel, is_bfloat16_supported
from unsloth import get_chat_template
from unsloth.trainer import UnslothVisionDataCollator

import argparse
import json
from pathlib import Path

import torch
import yaml
from datasets import Dataset, concatenate_datasets, interleave_datasets
from PIL import Image
from trl import SFTConfig, SFTTrainer


def parse_args():
    parser = argparse.ArgumentParser(description="OceanGuard Gemma 4 E2B - Etapa 2")
    parser.add_argument("--config", type=str, required=True,
                        help="Ruta a YAML config (configs/etapa2.yaml)")
    parser.add_argument("--resume", type=str, default=None,
                        help="Ruta a checkpoint para reanudar")
    parser.add_argument("--dry-run", action="store_true",
                        help="Smoke test sin train")
    return parser.parse_args()


def load_config(path):
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def _convert_record(rec):
    """Resuelve image paths a PIL.Image manteniendo tipo de content original."""
    new_msgs = []
    for msg in rec["messages"]:
        content = msg["content"]
        if isinstance(content, str):
            new_msgs.append({"role": msg["role"], "content": content})
        else:
            new_content = []
            for part in content:
                if part.get("type") == "image":
                    img_ref = part.get("image")
                    if isinstance(img_ref, str):
                        img = Image.open(img_ref).convert("RGB")
                        new_content.append({"type": "image", "image": img})
                    else:
                        new_content.append(part)
                else:
                    new_content.append(part)
            new_msgs.append({"role": msg["role"], "content": new_content})
    return {"messages": new_msgs}


def load_jsonl_dataset(path):
    """Devuelve List[Dict] (formato Unsloth notebook). SFTTrainer lo acepta directamente."""
    path_p = Path(path)
    if not path_p.exists():
        raise FileNotFoundError(f"Dataset JSONL no encontrado: {path}")
    records = []
    with path_p.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            records.append(_convert_record(json.loads(line)))
    return records


def maybe_mix_vqa(domain_ds, cfg):
    """Mezcla List[Dict] dominio con VQA general (anti-catastrophic-forgetting).

    NOTA: El mix runtime es OPCIONAL. El prep script `prepare_review_subset.py` ya soporta
    --mix-with/--mix-pct para mezclar en el JSONL final, por lo que normalmente
    `vqa_mix.enabled=false` aquí.

    cfg.vqa_mix:
      enabled: true|false
      ratio:   0.10
      dataset: ruta a JSONL con mismo schema multimodal
    """
    import random
    mix_cfg = cfg.get("vqa_mix", {})
    if not mix_cfg.get("enabled", False):
        return domain_ds

    vqa_path = mix_cfg.get("dataset")
    if not vqa_path:
        print("[etapa2] vqa_mix.enabled=true pero falta dataset path - skip mix")
        return domain_ds

    ratio = float(mix_cfg.get("ratio", 0.10))
    if ratio <= 0 or ratio >= 1:
        print(f"[etapa2] ratio invalido ({ratio}) - skip mix")
        return domain_ds

    print(f"[etapa2] Mezclando VQA general (ratio={ratio:.2f}) desde {vqa_path}")
    vqa_ds = load_jsonl_dataset(vqa_path)
    target_vqa_size = int(round(ratio / (1.0 - ratio) * len(domain_ds)))
    rng = random.Random(cfg.get("seed", 3407))
    if target_vqa_size < len(vqa_ds):
        vqa_ds = rng.sample(vqa_ds, target_vqa_size)
    print(f"[etapa2] domain={len(domain_ds)} vqa={len(vqa_ds)} total={len(domain_ds) + len(vqa_ds)}")
    mixed = list(domain_ds) + list(vqa_ds)
    rng.shuffle(mixed)
    return mixed


def build_model(cfg):
    """Carga base + (opc) merge adapter etapa1 + LoRA fresco para etapa2."""
    print(f"[etapa2] Cargando modelo base: {cfg['model_name']}")
    model, processor = FastVisionModel.from_pretrained(
        cfg["model_name"],
        load_in_4bit=cfg.get("load_in_4bit", False),
        use_gradient_checkpointing=cfg.get("gradient_checkpointing", "unsloth"),
        max_seq_length=cfg.get("max_seq_length", 2048),
    )

    print("[etapa2] Aplicando chat template gemma-4")
    processor = get_chat_template(processor, "gemma-4")

    # Cargar adapter de etapa 1 (opcional). Si merge=True -> merge_and_unload.
    etapa1_adapter = cfg.get("etapa1_adapter")
    if etapa1_adapter:
        adapter_path = Path(etapa1_adapter)
        if not adapter_path.exists():
            raise FileNotFoundError(f"Adapter etapa 1 no existe: {adapter_path}")
        print(f"[etapa2] Cargando adapter etapa 1 desde {adapter_path}")
        # Unsloth PeftModel API: load_adapter para anadir LoRA al modelo base
        model.load_adapter(str(adapter_path), adapter_name="etapa1")
        model.set_adapter("etapa1")

        if cfg.get("merge_etapa1_first", True):
            print("[etapa2] Merge adapter etapa 1 al modelo base (merge_and_unload)")
            model = model.merge_and_unload()

    vision_lora = cfg.get("vision_lora", True)  # En etapa 2 abrimos vision por defecto
    print(f"[etapa2] Configurando LoRA fresco (vision_layers={vision_lora}, r={cfg['lora_r']})")
    model = FastVisionModel.get_peft_model(
        model,
        finetune_vision_layers=vision_lora,
        finetune_language_layers=cfg.get("language_lora", True),
        finetune_attention_modules=cfg.get("attention_lora", True),
        finetune_mlp_modules=cfg.get("mlp_lora", True),
        r=cfg["lora_r"],
        lora_alpha=cfg.get("lora_alpha", cfg["lora_r"]),
        lora_dropout=cfg.get("lora_dropout", 0.0),
        bias="none",
        random_state=cfg.get("seed", 3407),
        use_rslora=cfg.get("use_rslora", False),
        loftq_config=None,
        target_modules=cfg.get("target_modules", "all-linear"),
    )
    return model, processor


def build_trainer(model, processor, train_ds, val_ds, cfg):
    def cget(*keys, default=None):
        for k in keys:
            if k in cfg:
                return cfg[k]
        return default

    bsz = cget("batch_size", "per_device_train_batch_size", default=1)
    sft_args = SFTConfig(
        per_device_train_batch_size=bsz,
        per_device_eval_batch_size=cget("eval_batch_size", "per_device_eval_batch_size", default=bsz),
        gradient_accumulation_steps=cget("grad_accum", "gradient_accumulation_steps", default=8),
        max_grad_norm=cget("max_grad_norm", default=0.3),
        warmup_ratio=cget("warmup_ratio", default=0.05),
        num_train_epochs=cget("epochs", "num_train_epochs", default=3),
        learning_rate=cget("lr", "learning_rate", default=5e-5),
        fp16=not is_bfloat16_supported(),
        bf16=is_bfloat16_supported(),
        logging_steps=cget("logging_steps", default=5),
        save_strategy="steps",
        save_steps=cget("save_steps", default=100),
        save_total_limit=cget("save_total_limit", default=3),
        eval_strategy="steps" if val_ds is not None else "no",
        eval_steps=cget("eval_steps", default=50),
        optim=cget("optim", default="adamw_8bit"),
        weight_decay=cget("weight_decay", default=0.001),
        lr_scheduler_type=cget("scheduler", "lr_scheduler_type", default="cosine"),
        seed=cget("seed", default=3407),
        output_dir=cfg["output_dir"],
        report_to=cget("report_to", default="none") if cget("report_to", default="none") != "wandb" or cget("use_wandb", default=False) else "wandb",
        run_name=cget("run_name", default="gemma4-oceanguard-etapa2"),
        remove_unused_columns=False,
        dataset_text_field="",
        dataset_kwargs={"skip_prepare_dataset": True},
        max_length=cget("max_seq_length", default=2048),
    )

    trainer = SFTTrainer(
        model=model,
        train_dataset=train_ds,
        eval_dataset=val_ds,
        processing_class=processor.tokenizer,
        data_collator=UnslothVisionDataCollator(
            model,
            processor,
            train_on_responses_only=cget("train_on_responses_only", default=True),
            instruction_part=cget("instruction_part", default="<|turn>user\n"),
            response_part=cget("response_part", default="<|turn>model\n"),
        ),
        args=sft_args,
    )
    return trainer


def save_outputs(model, processor, cfg):
    out_dir = Path(cfg["output_dir"])
    adapter_dir = out_dir / "adapter"
    adapter_dir.mkdir(parents=True, exist_ok=True)

    print(f"[etapa2] Guardando adapter LoRA en {adapter_dir}")
    model.save_pretrained(str(adapter_dir))
    processor.save_pretrained(str(adapter_dir))

    if cfg.get("save_merged_16bit"):
        merged_dir = out_dir / "merged_16bit"
        print(f"[etapa2] Guardando modelo merged 16bit en {merged_dir}")
        model.save_pretrained_merged(
            str(merged_dir),
            processor,
            save_method="merged_16bit",
        )


def main():
    args = parse_args()
    cfg = load_config(args.config)
    print("[etapa2] Config cargado:")
    print(json.dumps(cfg, indent=2, default=str))

    if args.resume:
        cfg["resume_from"] = args.resume

    model, processor = build_model(cfg)

    print(f"[etapa2] Cargando train dataset: {cfg['dataset']}")
    train_ds = load_jsonl_dataset(cfg["dataset"])
    print(f"[etapa2] Train samples (sin mix): {len(train_ds)}")
    train_ds = maybe_mix_vqa(train_ds, cfg)
    print(f"[etapa2] Train samples (post-mix): {len(train_ds)}")

    val_ds = None
    if cfg.get("eval_dataset"):
        print(f"[etapa2] Cargando eval dataset: {cfg['eval_dataset']}")
        val_ds = load_jsonl_dataset(cfg["eval_dataset"])
        print(f"[etapa2] Eval samples: {len(val_ds)}")

    FastVisionModel.for_training(model)
    trainer = build_trainer(model, processor, train_ds, val_ds, cfg)

    if args.dry_run:
        print("[etapa2] --dry-run: omitiendo trainer.train()")
        return 0

    if torch.cuda.is_available():
        props = torch.cuda.get_device_properties(0)
        gb = props.total_memory / (1024 ** 3)
        print(f"[etapa2] GPU: {props.name} ({gb:.1f} GB)")

    trainer_stats = trainer.train(resume_from_checkpoint=cfg.get("resume_from"))
    runtime = trainer_stats.metrics.get("train_runtime", 0)
    print(f"[etapa2] Train terminado en {runtime:.1f} s")

    save_outputs(model, processor, cfg)
    return 0


if __name__ == "__main__":
    sys.exit(main())
