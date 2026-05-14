"""smoke_inference.py — 5 samples GT vs Pred sanity check del best adapter.

Carga outputs/grid_best/adapter (o adapter explícito), infiere sobre primeros 5
samples del test hold-out, imprime tabla GT vs Pred y la escribe en SMOKE_RESULTS.md.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

os.environ.setdefault("TORCHDYNAMO_DISABLE", "1")
os.environ.setdefault("UNSLOTH_COMPILE_DISABLE", "1")
os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True,max_split_size_mb:512")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

sys.path.insert(0, str(Path(__file__).resolve().parent))
from config import EXP_OUTPUTS_BASE, REPO_ROOT, TEST_HOLDOUT_JSONL  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adapter", type=str, default=str(EXP_OUTPUTS_BASE / "grid_best" / "adapter"))
    parser.add_argument("--n", type=int, default=5)
    parser.add_argument("--holdout", type=str, default=str(TEST_HOLDOUT_JSONL))
    parser.add_argument("--output", type=str, default=str((REPO_ROOT.parent / "docs" / "submission" / "SMOKE_RESULTS.md")))
    args = parser.parse_args()

    # lazy imports
    from PIL import Image
    import torch
    from unsloth import FastVisionModel, get_chat_template

    adapter_p = Path(args.adapter)
    if not adapter_p.exists():
        print(f"[smoke] adapter no encontrado: {adapter_p}")
        return 1

    print(f"[smoke] cargando base+adapter: {adapter_p}")
    model, processor = FastVisionModel.from_pretrained(str(adapter_p))
    processor = get_chat_template(processor, "gemma-4")
    FastVisionModel.for_inference(model)

    # leer N samples
    samples: list[dict] = []
    with open(args.holdout, encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i >= args.n:
                break
            samples.append(json.loads(line))

    rows: list[dict] = []
    n_json_ok = 0
    for i, s in enumerate(samples):
        image = None
        prompt = ""
        sysmsg = None
        for msg in s["messages"]:
            if msg["role"] == "system":
                sysmsg = msg["content"] if isinstance(msg["content"], str) else " ".join(p.get("text", "") for p in msg["content"] if p.get("type") == "text")
            elif msg["role"] == "user":
                c = msg["content"]
                if isinstance(c, list):
                    for part in c:
                        if part.get("type") == "image":
                            r = part["image"]
                            image = Image.open(r).convert("RGB") if isinstance(r, str) else r
                        elif part.get("type") == "text":
                            prompt = part["text"]
                break
        gt = ""
        for msg in s["messages"]:
            if msg["role"] == "assistant":
                gt = msg["content"] if isinstance(msg["content"], str) else json.dumps([p for p in msg["content"] if p.get("type") == "text"])
                break

        messages = [{"role": "user", "content": [{"type": "image"}, {"type": "text", "text": prompt}]}]
        if sysmsg:
            messages.insert(0, {"role": "system", "content": [{"type": "text", "text": sysmsg}]})
        input_text = processor.apply_chat_template(messages, add_generation_prompt=True)
        inputs = processor(image, input_text, add_special_tokens=False, return_tensors="pt").to(
            "cuda" if torch.cuda.is_available() else "cpu"
        )
        t0 = time.time()
        with torch.inference_mode():
            out = model.generate(**inputs, max_new_tokens=512, do_sample=False, use_cache=True)
        dt = time.time() - t0
        prompt_len = inputs["input_ids"].shape[-1]
        pred_text = processor.tokenizer.decode(out[0][prompt_len:], skip_special_tokens=True).strip()
        json_ok = True
        try:
            json.loads(pred_text)
            n_json_ok += 1
        except Exception:
            json_ok = False
        rows.append({
            "idx": i, "img_id": s["img_id"], "gt": gt, "pred": pred_text,
            "json_ok": json_ok, "latency_s": dt,
        })
        print(f"[smoke] sample {i}: latency={dt:.2f}s json_ok={json_ok}")

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8") as f:
        f.write("# Smoke Inference — Best LoRA Adapter (Gemma 4 E2B)\n\n")
        f.write(f"Adapter: `{adapter_p}`\n\nMuestras: {len(rows)}\n\n")
        validity = n_json_ok / max(1, len(rows))
        f.write(f"JSON-validity (n={len(rows)}): **{validity*100:.0f}%**\n\n")
        if validity < 0.95:
            f.write("> WARNING: JSON-validity por debajo del 95%. Revisar parser y prompt.\n\n")
        for r in rows:
            f.write(f"## Sample {r['idx']} — img_id={r['img_id']}  latency={r['latency_s']:.2f}s json_ok={r['json_ok']}\n\n")
            f.write("**Ground truth**\n\n```json\n" + r["gt"] + "\n```\n\n")
            f.write("**Prediction**\n\n```\n" + r["pred"] + "\n```\n\n")
    print(f"[smoke] SMOKE_RESULTS.md -> {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
