"""OceanGuard AI - Quick smoke test: 1 imagen -> JSON output.

Usar tras un train para validar que el adapter funciona y produce JSON parseable.

Comando:
    cd C:/Users/aleja/Desktop/Doctorado/OceanguardAI-App/finetune
    python infer_single.py \
        --model unsloth/gemma-4-E2B-it \
        --adapter outputs/lora_etapa1/adapter \
        --image data/samples/test_image.jpg \
        --prompt "Detect all marine debris and output bounding boxes as JSON."
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
from pathlib import Path

import torch
from PIL import Image
from transformers import TextStreamer

DEFAULT_PROMPT = (
    "You are a marine debris detection system. Analyze the image and return a JSON "
    "list of detections. Each item must have: box_2d=[ymin,xmin,ymax,xmax] normalized "
    "to [0,1000], label (one of: Bottle, Can, Fishing_Net, Glove, Mask, Metal_Debris, "
    "Plastic_Debris, Tire), and material. Return ONLY the JSON array, no extra text."
)


def parse_args():
    p = argparse.ArgumentParser(description="OceanGuard Gemma 4 - infer single image")
    p.add_argument("--model", type=str, default="unsloth/gemma-4-E2B-it")
    p.add_argument("--adapter", type=str, default=None)
    p.add_argument("--image", type=str, required=True)
    p.add_argument("--prompt", type=str, default=DEFAULT_PROMPT)
    p.add_argument("--max-new-tokens", type=int, default=512)
    p.add_argument("--temperature", type=float, default=0.1)
    p.add_argument("--top-p", type=float, default=0.95)
    p.add_argument("--top-k", type=int, default=64)
    p.add_argument("--load-in-4bit", action="store_true")
    p.add_argument("--no-stream", action="store_true")
    return p.parse_args()


def main():
    args = parse_args()
    img_path = Path(args.image)
    if not img_path.exists():
        print(f"[infer] Imagen no encontrada: {img_path}", file=sys.stderr)
        return 1
    image = Image.open(img_path).convert("RGB")

    print(f"[infer] Cargando base: {args.model}")
    model, processor = FastVisionModel.from_pretrained(
        args.model,
        load_in_4bit=args.load_in_4bit,
    )
    processor = get_chat_template(processor, "gemma-4")

    if args.adapter:
        adapter_p = Path(args.adapter)
        if not adapter_p.exists():
            print(f"[infer] Adapter no existe: {adapter_p}", file=sys.stderr)
            return 1
        print(f"[infer] Cargando adapter: {adapter_p}")
        model.load_adapter(str(adapter_p), adapter_name="default")
        model.set_adapter("default")

    FastVisionModel.for_inference(model)

    messages = [{
        "role": "user",
        "content": [{"type": "image"}, {"type": "text", "text": args.prompt}],
    }]
    input_text = processor.apply_chat_template(messages, add_generation_prompt=True)
    inputs = processor(
        image,
        input_text,
        add_special_tokens=False,
        return_tensors="pt",
    ).to("cuda" if torch.cuda.is_available() else "cpu")

    streamer = None if args.no_stream else TextStreamer(processor, skip_prompt=True)

    print("[infer] === MODEL OUTPUT ===")
    with torch.inference_mode():
        out = model.generate(
            **inputs,
            max_new_tokens=args.max_new_tokens,
            temperature=args.temperature,
            top_p=args.top_p,
            top_k=args.top_k,
            use_cache=True,
            do_sample=args.temperature > 0,
            streamer=streamer,
        )

    if args.no_stream:
        prompt_len = inputs["input_ids"].shape[-1]
        text = processor.tokenizer.decode(out[0][prompt_len:], skip_special_tokens=True)
        print(text)

    # Verify JSON parseability
    prompt_len = inputs["input_ids"].shape[-1]
    text = processor.tokenizer.decode(out[0][prompt_len:], skip_special_tokens=True).strip()
    try:
        parsed = json.loads(text)
        print(f"\n[infer] JSON parse OK. {len(parsed) if isinstance(parsed, list) else 1} items.")
    except Exception as e:
        print(f"\n[infer] JSON parse FAILED: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
