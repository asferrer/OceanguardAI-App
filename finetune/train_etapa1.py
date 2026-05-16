"""OceanGuard AI - Fine-tuning Etapa 1: warmup geometria + JSON output.

Modelo:       unsloth/gemma-4-E2B-it (vision-language)
Dataset:      ~10k samples COCO 8-class (debris marino)
Estrategia:   LoRA r=16 sobre language layers; vision encoder congelado.
Hardware:     RTX 5090 32GB (Blackwell sm_120, torch 2.7.0+cu128)
Tiempo est.:  ~3-5h con 10k samples (1 epoch, bs=1, grad_accum=8)

Comando para lanzar (cuando GPU libre):
    cd C:/Users/aleja/Desktop/Doctorado/OceanguardAI-App/finetune
    python train_etapa1.py --config configs/etapa1.yaml

Validacion sin GPU (smoke test imports):
    python -c "from unsloth import FastVisionModel; print(0)"

Resume desde checkpoint:
    python train_etapa1.py --config configs/etapa1.yaml --resume outputs/lora_etapa1/checkpoint-500

Referencias (mayo 2026):
- Notebook oficial: https://github.com/unslothai/notebooks/blob/main/nb/Gemma4_(E2B)-Vision.ipynb
- Docs Gemma 4:     https://unsloth.ai/docs/models/gemma-4/train
- Vision fine-tune: https://unsloth.ai/docs/basics/vision-fine-tuning
- Blackwell setup:  https://unsloth.ai/docs/blog/fine-tuning-llms-with-blackwell-rtx-50-series-and-unsloth.md
"""
from __future__ import annotations

import os
import sys

os.environ.setdefault("TORCH_CUDA_ARCH_LIST", "12.0")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
os.environ.setdefault("HF_HUB_ENABLE_HF_TRANSFER", "1")
# Workaround Blackwell sm_120 + triton 3.6.0 ImportError 'triton_key' (issue unsloth #5154)
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
from datasets import Dataset
from PIL import Image
from trl import SFTConfig, SFTTrainer


def parse_args():
    parser = argparse.ArgumentParser(description="OceanGuard Gemma 4 E2B - Etapa 1")
    parser.add_argument("--config", type=str, required=True,
                        help="Ruta a YAML config (configs/etapa1.yaml)")
    parser.add_argument("--resume", type=str, default=None,
                        help="Ruta a checkpoint para reanudar (opcional)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Carga modelo y dataset, no entrena (smoke test)")
    parser.add_argument("--max-steps", type=int, default=None,
                        help="Cap total steps (overrides epochs). Used for chunked resume.")
    return parser.parse_args()


def load_config(path):
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def load_jsonl_dataset(path):
    """Lee JSONL multimodal y resuelve image paths a PIL.Image."""
    path_p = Path(path)
    if not path_p.exists():
        raise FileNotFoundError(f"Dataset JSONL no encontrado: {path}")

    def _convert(rec):
        """Resuelve image paths a PIL.Image manteniendo el tipo de content original.

        - content=str (system/assistant): se mantiene como string (apply_chat_template
          Gemma 4 NO maneja list-of-dicts para system role correctamente).
        - content=list (user multimodal): resuelve cada image path a PIL.Image.
        """
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

    records = []
    with path_p.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            records.append(_convert(json.loads(line)))

    # Devuelve lista plana (formato esperado por Unsloth notebook); SFTTrainer la acepta directamente.
    return records


def build_model(cfg):
    print(f"[etapa1] Cargando modelo base: {cfg['model_name']}")
    model, processor = FastVisionModel.from_pretrained(
        cfg["model_name"],
        load_in_4bit=cfg.get("load_in_4bit", False),
        use_gradient_checkpointing=cfg.get("gradient_checkpointing", "unsloth"),
        max_seq_length=cfg.get("max_seq_length", 2048),
    )

    print("[etapa1] Aplicando chat template gemma-4")
    processor = get_chat_template(processor, "gemma-4")

    vision_lora = cfg.get("vision_lora", False)
    print(f"[etapa1] Configurando LoRA (vision_layers={vision_lora})")
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
    # Aliases: el YAML puede usar nombres HF largos o cortos
    def cget(*keys, default=None):
        for k in keys:
            if k in cfg:
                return cfg[k]
        return default

    bsz = cget("batch_size", "per_device_train_batch_size", default=2)
    max_steps_val = cget("max_steps", default=None)
    if max_steps_val is None or max_steps_val <= 0:
        max_steps_val = -1  # HF Trainer convention: -1 means ignore, use epochs
    sft_args = SFTConfig(
        per_device_train_batch_size=bsz,
        per_device_eval_batch_size=cget("eval_batch_size", "per_device_eval_batch_size", default=bsz),
        gradient_accumulation_steps=cget("grad_accum", "gradient_accumulation_steps", default=8),
        max_grad_norm=cget("max_grad_norm", default=0.3),
        warmup_ratio=cget("warmup_ratio", default=0.03),
        num_train_epochs=cget("epochs", "num_train_epochs", default=1),
        max_steps=max_steps_val,
        learning_rate=cget("lr", "learning_rate", default=1e-4),
        fp16=not is_bfloat16_supported(),
        bf16=is_bfloat16_supported(),
        logging_steps=cget("logging_steps", default=10),
        save_strategy="steps",
        save_steps=cget("save_steps", default=500),
        save_total_limit=cget("save_total_limit", default=3),
        eval_strategy="steps" if val_ds is not None else "no",
        eval_steps=cget("eval_steps", default=500),
        optim=cget("optim", default="adamw_8bit"),
        weight_decay=cget("weight_decay", default=0.001),
        lr_scheduler_type=cget("scheduler", "lr_scheduler_type", default="cosine"),
        seed=cget("seed", default=3407),
        output_dir=cfg["output_dir"],
        report_to=cget("report_to", default="none") if cget("report_to", default="none") != "wandb" or cget("use_wandb", default=False) else "wandb",
        run_name=cget("run_name", default="gemma4-oceanguard-etapa1"),
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
            train_on_responses_only=cfg.get("train_on_responses_only", True),
            instruction_part=cfg.get("instruction_part", "<|turn>user\n"),
            response_part=cfg.get("response_part", "<|turn>model\n"),
        ),
        args=sft_args,
    )
    return trainer


def save_outputs(model, processor, cfg):
    out_dir = Path(cfg["output_dir"])
    adapter_dir = out_dir / "adapter"
    adapter_dir.mkdir(parents=True, exist_ok=True)

    print(f"[etapa1] Guardando adapter LoRA en {adapter_dir}")
    model.save_pretrained(str(adapter_dir))
    processor.save_pretrained(str(adapter_dir))

    if cfg.get("save_merged_16bit"):
        merged_dir = out_dir / "merged_16bit"
        print(f"[etapa1] Guardando modelo merged 16bit en {merged_dir}")
        model.save_pretrained_merged(
            str(merged_dir),
            processor,
            save_method="merged_16bit",
        )


def main():
    args = parse_args()
    cfg = load_config(args.config)
    print("[etapa1] Config cargado:")
    print(json.dumps(cfg, indent=2, default=str))

    if args.resume:
        cfg["resume_from"] = args.resume
    if args.max_steps is not None:
        cfg["max_steps"] = args.max_steps
        print(f"[etapa1] CLI override: max_steps={args.max_steps}")

    model, processor = build_model(cfg)

    print(f"[etapa1] Cargando train dataset: {cfg['dataset']}")
    train_ds = load_jsonl_dataset(cfg["dataset"])
    print(f"[etapa1] Train samples: {len(train_ds)}")

    val_ds = None
    if cfg.get("eval_dataset"):
        print(f"[etapa1] Cargando eval dataset: {cfg['eval_dataset']}")
        val_ds = load_jsonl_dataset(cfg["eval_dataset"])
        print(f"[etapa1] Eval samples: {len(val_ds)}")

    FastVisionModel.for_training(model)
    trainer = build_trainer(model, processor, train_ds, val_ds, cfg)

    if args.dry_run:
        print("[etapa1] --dry-run: omitiendo trainer.train()")
        return 0

    if torch.cuda.is_available():
        props = torch.cuda.get_device_properties(0)
        gb = props.total_memory / (1024 ** 3)
        print(f"[etapa1] GPU: {props.name} ({gb:.1f} GB)")

    trainer_stats = trainer.train(resume_from_checkpoint=cfg.get("resume_from"))
    runtime = trainer_stats.metrics.get("train_runtime", 0)
    print(f"[etapa1] Train terminado en {runtime:.1f} s")

    save_outputs(model, processor, cfg)
    return 0


if __name__ == "__main__":
    sys.exit(main())
