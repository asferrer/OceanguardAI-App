"""OceanGuard AI - Eval offline: corre inference sobre val set y genera predictions.jsonl.

NO se llama desde train. Se ejecuta manualmente despues de cada etapa.

Comando:
    cd C:/Users/aleja/Desktop/Doctorado/OceanguardAI-App/finetune
    python eval_model.py \
        --model unsloth/gemma-4-E2B-it \
        --adapter outputs/lora_etapa1/adapter \
        --dataset data/etapa1_val.jsonl \
        --output outputs/lora_etapa1/predictions.jsonl

Despues, calcular mAP con:
    python data/eval_map.py outputs/lora_etapa1/predictions.jsonl

Referencias:
- Cookbook HF (VLM grounding): https://huggingface.co/learn/cookbook/en/fine_tuning_vlm_object_detection_grounding
"""
from __future__ import annotations

import os
import sys

os.environ.setdefault("TORCH_CUDA_ARCH_LIST", "12.0")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
# Workaround Blackwell sm_120 + triton 3.6.0 (issue unsloth #5154)
os.environ.setdefault("TORCHDYNAMO_DISABLE", "1")
os.environ.setdefault("UNSLOTH_COMPILE_DISABLE", "1")

from unsloth import FastVisionModel, get_chat_template

import argparse
import json
import time
from pathlib import Path

import torch
from PIL import Image


def parse_args():
    p = argparse.ArgumentParser(description="OceanGuard Gemma 4 - eval offline")
    p.add_argument("--model", type=str, default="unsloth/gemma-4-E2B-it",
                   help="Modelo base (o ruta a merged)")
    p.add_argument("--adapter", type=str, default=None,
                   help="Ruta a adapter LoRA (opcional, se hace merge en runtime)")
    p.add_argument("--dataset", type=str, required=True,
                   help="JSONL con val set (mismo schema que train)")
    p.add_argument("--output", type=str, required=True,
                   help="JSONL donde escribir predictions")
    p.add_argument("--max-new-tokens", type=int, default=512)
    p.add_argument("--temperature", type=float, default=0.1)
    p.add_argument("--top-p", type=float, default=0.95)
    p.add_argument("--top-k", type=int, default=64)
    p.add_argument("--limit", type=int, default=None,
                   help="Limitar a N samples (debug)")
    return p.parse_args()


def load_model(model_name, adapter_path=None, load_in_4bit=False):
    if adapter_path:
        # Truco: pasar el adapter directamente como model_name a FastVisionModel.
        # Unsloth auto-detecta el adapter_config.json y carga base + adapter con sus wrapped modules.
        # Esto evita el bug Gemma4ClippableLinear de peft.load_adapter y PeftModel.from_pretrained
        # cuando los target_modules (q_proj, k_proj, ...) existen también en el vision encoder.
        adapter_p = Path(adapter_path)
        if not adapter_p.exists():
            raise FileNotFoundError(f"Adapter no encontrado: {adapter_p}")
        print(f"[eval] Cargando base+adapter via FastVisionModel: {adapter_p}")
        model, processor = FastVisionModel.from_pretrained(
            str(adapter_p),
            load_in_4bit=load_in_4bit,
        )
    else:
        print(f"[eval] Cargando base: {model_name}")
        model, processor = FastVisionModel.from_pretrained(
            model_name,
            load_in_4bit=load_in_4bit,
        )
    processor = get_chat_template(processor, "gemma-4")

    FastVisionModel.for_inference(model)
    return model, processor


def extract_image_and_prompt(sample):
    image = None
    prompt_text = ""
    system_text = None
    for msg in sample["messages"]:
        if msg["role"] == "system":
            system_text = msg["content"] if isinstance(msg["content"], str) else \
                          " ".join(p.get("text", "") for p in msg["content"] if p.get("type") == "text")
        elif msg["role"] == "user":
            content = msg["content"]
            if isinstance(content, list):
                for part in content:
                    if part.get("type") == "image":
                        img_ref = part["image"]
                        if isinstance(img_ref, str):
                            image = Image.open(img_ref).convert("RGB")
                        else:
                            image = img_ref
                    elif part.get("type") == "text":
                        prompt_text = part["text"]
            else:
                prompt_text = content
            break  # solo usamos primer user msg para inference
    return image, prompt_text, system_text


def extract_gt(sample):
    for msg in sample["messages"]:
        if msg["role"] == "assistant":
            content = msg["content"]
            if isinstance(content, list):
                return " ".join(p.get("text", "") for p in content if p.get("type") == "text")
            return content
    return None


def run_inference(model, processor, image, prompt_text, system_text, gen_kwargs):
    messages = []
    if system_text:
        messages.append({"role": "system", "content": [{"type": "text", "text": system_text}]})
    messages.append({
        "role": "user",
        "content": [{"type": "image"}, {"type": "text", "text": prompt_text}],
    })

    input_text = processor.apply_chat_template(messages, add_generation_prompt=True)
    inputs = processor(
        image,
        input_text,
        add_special_tokens=False,
        return_tensors="pt",
    ).to("cuda" if torch.cuda.is_available() else "cpu")

    with torch.inference_mode():
        out = model.generate(
            **inputs,
            max_new_tokens=gen_kwargs["max_new_tokens"],
            temperature=gen_kwargs["temperature"],
            top_p=gen_kwargs["top_p"],
            top_k=gen_kwargs["top_k"],
            use_cache=True,
            do_sample=gen_kwargs["temperature"] > 0,
        )

    # Decode solo los tokens generados (no el prompt)
    prompt_len = inputs["input_ids"].shape[-1]
    generated = out[0][prompt_len:]
    text = processor.tokenizer.decode(generated, skip_special_tokens=True)
    return text


def main():
    args = parse_args()
    model, processor = load_model(args.model, args.adapter)

    # Lee JSONL
    samples = []
    with open(args.dataset, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                samples.append(json.loads(line))
    if args.limit:
        samples = samples[:args.limit]
    print(f"[eval] Total samples: {len(samples)}")

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    gen_kwargs = {
        "max_new_tokens": args.max_new_tokens,
        "temperature": args.temperature,
        "top_p": args.top_p,
        "top_k": args.top_k,
    }

    n_ok = 0
    t0 = time.time()
    with out_path.open("w", encoding="utf-8") as f:
        for i, sample in enumerate(samples):
            image, prompt_text, system_text = extract_image_and_prompt(sample)
            if image is None:
                print(f"[eval] sample {i}: sin imagen - skip")
                continue
            gt = extract_gt(sample)
            try:
                pred = run_inference(model, processor, image, prompt_text, system_text, gen_kwargs)
                # Intento parse para validar JSON
                json_ok = False
                try:
                    json.loads(pred.strip())
                    json_ok = True
                    n_ok += 1
                except Exception:
                    pass
                rec = {
                    "idx": i,
                    "prompt": prompt_text,
                    "ground_truth": gt,
                    "prediction": pred,
                    "json_parse_ok": json_ok,
                }
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                f.flush()
                if (i + 1) % 25 == 0:
                    rate = (i + 1) / (time.time() - t0)
                    print(f"[eval] {i+1}/{len(samples)} (json_ok={n_ok}, {rate:.2f} samples/s)")
            except Exception as e:
                print(f"[eval] sample {i}: ERROR {e}")
                f.write(json.dumps({"idx": i, "error": str(e)}) + "\n")

    print(f"[eval] DONE. JSON parse OK: {n_ok}/{len(samples)} ({100*n_ok/max(1,len(samples)):.1f}%)")
    print(f"[eval] Output: {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
