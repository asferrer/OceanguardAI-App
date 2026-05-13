"""eval_grid.py — inference + COCOeval mAP sobre TEST hold-out 1000 (REAL).

Modos:
    --exp <name>     : carga outputs/lora_<name>/adapter
    --base           : carga modelo base unsloth/gemma-4-E2B-it sin adapter
    --adapter <path> : usa ruta arbitraria

Outputs (en experiments/results/):
    metrics_<key>.json
    predictions_<key>.jsonl

Métricas: mAP@0.5, mAP@0.5:.95, per-class mAP, JSON-validity, n_preds, latency.
"""
from __future__ import annotations

import os
import sys

os.environ.setdefault("TORCH_CUDA_ARCH_LIST", "12.0")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
os.environ.setdefault("TORCHDYNAMO_DISABLE", "1")
os.environ.setdefault("UNSLOTH_COMPILE_DISABLE", "1")
os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True,max_split_size_mb:512")

import argparse
import contextlib
import io
import json
import re
import time
from pathlib import Path

import numpy as np
import torch
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from config import (  # noqa: E402
    CATEGORY_MAP,
    CLASS_NAMES,
    EXP_OUTPUTS_BASE,
    LABEL_TO_CAT,
    RESULTS_DIR,
    TEST_HOLDOUT_JSONL,
)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--exp", type=str, default=None, help="Nombre del experimento (lora_<exp>/adapter)")
    p.add_argument("--adapter", type=str, default=None, help="Ruta explícita a adapter")
    p.add_argument("--base", action="store_true", help="Eval base sin adapter")
    p.add_argument("--model", type=str, default="unsloth/gemma-4-E2B-it")
    p.add_argument("--holdout", type=str, default=str(TEST_HOLDOUT_JSONL))
    p.add_argument("--limit", type=int, default=200)
    p.add_argument("--max-new-tokens", type=int, default=256)
    p.add_argument("--temperature", type=float, default=0.0)
    p.add_argument("--key", type=str, default=None, help="Override key/nombre")
    return p.parse_args()


def load_model(model_name: str, adapter_path: str | None):
    from unsloth import FastVisionModel, get_chat_template
    if adapter_path is not None:
        adapter_p = Path(adapter_path)
        if not adapter_p.exists():
            raise FileNotFoundError(f"adapter no encontrado: {adapter_p}")
        print(f"[eval] cargando base+adapter via FastVisionModel: {adapter_p}")
        model, processor = FastVisionModel.from_pretrained(str(adapter_p))
    else:
        print(f"[eval] cargando base: {model_name}")
        model, processor = FastVisionModel.from_pretrained(model_name)
    processor = get_chat_template(processor, "gemma-4")
    FastVisionModel.for_inference(model)
    return model, processor


def extract_image_prompt_system(sample: dict) -> tuple[Image.Image | None, str, str | None]:
    image = None
    prompt_text = ""
    system_text = None
    for msg in sample["messages"]:
        if msg["role"] == "system":
            c = msg["content"]
            system_text = c if isinstance(c, str) else " ".join(p.get("text", "") for p in c if p.get("type") == "text")
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
            break
    return image, prompt_text, system_text


def run_once(model, processor, image, prompt_text, system_text, gen_kwargs) -> tuple[str, float]:
    messages = []
    if system_text:
        messages.append({"role": "system", "content": [{"type": "text", "text": system_text}]})
    messages.append({"role": "user", "content": [{"type": "image"}, {"type": "text", "text": prompt_text}]})
    input_text = processor.apply_chat_template(messages, add_generation_prompt=True)
    inputs = processor(image, input_text, add_special_tokens=False, return_tensors="pt").to(
        "cuda" if torch.cuda.is_available() else "cpu"
    )
    t0 = time.time()
    with torch.inference_mode():
        out = model.generate(
            **inputs,
            max_new_tokens=gen_kwargs["max_new_tokens"],
            temperature=gen_kwargs["temperature"],
            do_sample=gen_kwargs["temperature"] > 0,
            use_cache=True,
        )
    dt = time.time() - t0
    prompt_len = inputs["input_ids"].shape[-1]
    text = processor.tokenizer.decode(out[0][prompt_len:], skip_special_tokens=True)
    return text, dt


JSON_BLOCK_RE = re.compile(r"\[.*\]", re.DOTALL)


def parse_pred_json(text: str) -> list[dict] | None:
    txt = text.strip()
    # remueve markdown fences si hay
    txt = re.sub(r"^```(?:json)?", "", txt, flags=re.IGNORECASE).strip()
    txt = re.sub(r"```$", "", txt).strip()
    try:
        data = json.loads(txt)
        return data if isinstance(data, list) else None
    except Exception:
        pass
    m = JSON_BLOCK_RE.search(txt)
    if m:
        try:
            data = json.loads(m.group(0))
            return data if isinstance(data, list) else None
        except Exception:
            return None
    return None


def gemma_box_to_pixel(box_2d: list[int], w: int, h: int) -> list[float]:
    """gemma [ymin, xmin, ymax, xmax] (0-1000) -> COCO [x, y, w, h] píxeles."""
    ymin, xmin, ymax, xmax = box_2d
    x = xmin / 1000.0 * w
    y = ymin / 1000.0 * h
    bw = max(0.0, (xmax - xmin) / 1000.0 * w)
    bh = max(0.0, (ymax - ymin) / 1000.0 * h)
    return [x, y, bw, bh]


def build_coco_eval(samples: list[dict], predictions: list[dict]) -> dict:
    """Construye estructuras COCO GT y DT en memoria y corre pycocotools.

    GT: cada sample tiene gt_dets con bbox_pixel.
    DT: lista de predicciones [{image_id, category_id, bbox, score}].
    """
    try:
        from pycocotools.coco import COCO
        from pycocotools.cocoeval import COCOeval
    except Exception as e:
        print(f"[eval] pycocotools no disponible ({e}), fallback IoU manual")
        return manual_map_eval(samples, predictions)

    gt_images = []
    gt_anns = []
    ann_id = 1
    for s in samples:
        gt_images.append({
            "id": s["img_id"],
            "width": s["width"],
            "height": s["height"],
            "file_name": s.get("image_path", str(s["img_id"])),
        })
        for d in s["gt_dets"]:
            gt_anns.append({
                "id": ann_id,
                "image_id": s["img_id"],
                "category_id": int(d["category_id"]),
                "bbox": d["bbox_pixel"],
                "area": float(d["bbox_pixel"][2] * d["bbox_pixel"][3]),
                "iscrowd": 0,
            })
            ann_id += 1
    gt_dict = {
        "images": gt_images,
        "annotations": gt_anns,
        "categories": [{"id": cid, "name": CATEGORY_MAP[cid][0]} for cid in sorted(CATEGORY_MAP.keys())],
    }

    coco_gt = COCO()
    coco_gt.dataset = gt_dict
    coco_gt.createIndex()

    if not predictions:
        return {
            "mAP_50": 0.0, "mAP_50_95": 0.0, "mAP_75": 0.0,
            "per_class_mAP_50": {n: 0.0 for n in CLASS_NAMES},
            "n_predictions": 0,
        }

    coco_dt = coco_gt.loadRes(predictions)
    coco_eval = COCOeval(coco_gt, coco_dt, "bbox")
    coco_eval.params.iouThrs = np.linspace(0.5, 0.95, 10)
    with contextlib.redirect_stdout(io.StringIO()):
        coco_eval.evaluate()
        coco_eval.accumulate()
        coco_eval.summarize()
    stats = coco_eval.stats.tolist()

    # per-class mAP @0.5
    precision = coco_eval.eval["precision"]  # [T, R, K, A, M]
    per_class_50 = {}
    iou_idx = 0  # 0.5
    area_idx = 0  # all
    max_det_idx = -1  # 100
    for k, cid in enumerate(sorted(CATEGORY_MAP.keys())):
        p = precision[iou_idx, :, k, area_idx, max_det_idx]
        p = p[p > -1]
        ap = float(p.mean()) if p.size else 0.0
        per_class_50[CLASS_NAMES[cid]] = ap

    return {
        "mAP_50_95": stats[0],
        "mAP_50": stats[1],
        "mAP_75": stats[2],
        "per_class_mAP_50": per_class_50,
        "n_predictions": len(predictions),
    }


def manual_map_eval(samples: list[dict], predictions: list[dict]) -> dict:
    """Fallback simple mAP@0.5 sin pycocotools."""
    # group preds & gts por imagen y clase
    preds_by_ic: dict[tuple[int, int], list[dict]] = {}
    gts_by_ic: dict[tuple[int, int], list[dict]] = {}
    for p in predictions:
        k = (p["image_id"], p["category_id"])
        preds_by_ic.setdefault(k, []).append(p)
    for s in samples:
        for d in s["gt_dets"]:
            k = (s["img_id"], int(d["category_id"]))
            gts_by_ic.setdefault(k, []).append({"bbox": d["bbox_pixel"], "matched": False})

    def iou(b1, b2):
        x1, y1, w1, h1 = b1; x2, y2, w2, h2 = b2
        xa, ya = max(x1, x2), max(y1, y2)
        xb, yb = min(x1 + w1, x2 + w2), min(y1 + h1, y2 + h2)
        inter = max(0, xb - xa) * max(0, yb - ya)
        return inter / max(1e-6, w1 * h1 + w2 * h2 - inter)

    per_class_ap: dict[str, float] = {n: 0.0 for n in CLASS_NAMES}
    for cid in sorted(CATEGORY_MAP.keys()):
        name = CLASS_NAMES[cid]
        # recolectar todas las predicciones de esta clase ordenadas por score
        preds = []
        gts_count = 0
        for (img, c), lst in preds_by_ic.items():
            if c == cid:
                preds.extend([(img, p) for p in lst])
        for (img, c), lst in gts_by_ic.items():
            if c == cid:
                gts_count += len(lst)
        preds.sort(key=lambda x: -x[1]["score"])
        tp, fp = [], []
        for img, p in preds:
            ious = []
            for g in gts_by_ic.get((img, cid), []):
                if not g["matched"]:
                    ious.append((iou(p["bbox"], g["bbox"]), g))
            ious.sort(key=lambda x: -x[0])
            if ious and ious[0][0] >= 0.5:
                ious[0][1]["matched"] = True
                tp.append(1); fp.append(0)
            else:
                tp.append(0); fp.append(1)
        if gts_count == 0 or not preds:
            per_class_ap[name] = 0.0
            continue
        tp_cum = np.cumsum(tp); fp_cum = np.cumsum(fp)
        recalls = tp_cum / gts_count
        precisions = tp_cum / np.maximum(tp_cum + fp_cum, 1)
        ap = 0.0
        for t in np.linspace(0, 1, 11):
            mask = recalls >= t
            ap += (precisions[mask].max() if mask.any() else 0.0) / 11
        per_class_ap[name] = float(ap)
        # reset matches for next class iteration
        for (img, c), lst in gts_by_ic.items():
            if c == cid:
                for g in lst:
                    g["matched"] = False

    mAP50 = float(np.mean(list(per_class_ap.values()))) if per_class_ap else 0.0
    return {
        "mAP_50": mAP50, "mAP_50_95": 0.0, "mAP_75": 0.0,
        "per_class_mAP_50": per_class_ap,
        "n_predictions": len(predictions),
    }


def main() -> int:
    args = parse_args()
    if args.base:
        adapter_path = None
        key = args.key or "base"
    elif args.adapter:
        adapter_path = args.adapter
        key = args.key or Path(args.adapter).parent.name
    elif args.exp:
        adapter_path = str(EXP_OUTPUTS_BASE / f"lora_{args.exp}" / "adapter")
        key = args.key or args.exp
    else:
        raise SystemExit("debe especificar --exp o --base o --adapter")

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    model, processor = load_model(args.model, adapter_path)

    samples: list[dict] = []
    with open(args.holdout, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            samples.append(json.loads(line))
    if args.limit:
        samples = samples[: args.limit]
    print(f"[eval] hold-out samples: {len(samples)}")

    gen_kwargs = {"max_new_tokens": args.max_new_tokens, "temperature": args.temperature}

    predictions: list[dict] = []
    pred_jsonl_path = RESULTS_DIR / f"predictions_{key}.jsonl"
    n_ok = 0
    latencies: list[float] = []
    t0 = time.time()

    with pred_jsonl_path.open("w", encoding="utf-8") as fp:
        for i, sample in enumerate(samples):
            image, prompt_text, system_text = extract_image_prompt_system(sample)
            if image is None:
                print(f"[eval] sample {i}: sin imagen, skip")
                continue
            try:
                text, dt = run_once(model, processor, image, prompt_text, system_text, gen_kwargs)
                latencies.append(dt)
            except Exception as e:
                print(f"[eval] sample {i}: ERROR generate: {e}")
                fp.write(json.dumps({"idx": i, "img_id": sample["img_id"], "error": str(e)}) + "\n")
                continue

            parsed = parse_pred_json(text)
            json_ok = parsed is not None
            if json_ok:
                n_ok += 1
                for det in parsed:
                    if not isinstance(det, dict):
                        continue
                    box = det.get("box_2d")
                    label = det.get("label")
                    if box is None or label is None:
                        continue
                    if not isinstance(box, list) or len(box) != 4:
                        continue
                    cat_id = LABEL_TO_CAT.get(str(label).lower())
                    if cat_id is None:
                        continue
                    try:
                        coco_bbox = gemma_box_to_pixel([int(v) for v in box], sample["width"], sample["height"])
                    except Exception:
                        continue
                    if coco_bbox[2] <= 0 or coco_bbox[3] <= 0:
                        continue
                    predictions.append({
                        "image_id": sample["img_id"],
                        "category_id": int(cat_id),
                        "bbox": coco_bbox,
                        "score": 1.0,
                    })
            fp.write(json.dumps({
                "idx": i, "img_id": sample["img_id"],
                "raw": text, "parsed": parsed, "json_ok": json_ok,
                "latency_s": dt,
            }, ensure_ascii=False) + "\n")
            fp.flush()
            if (i + 1) % 25 == 0:
                rate = (i + 1) / (time.time() - t0)
                print(f"[eval] {i+1}/{len(samples)} json_ok={n_ok} {rate:.2f} samples/s")

    elapsed = time.time() - t0
    metrics = build_coco_eval(samples, predictions)
    metrics.update({
        "key": key,
        "adapter_path": adapter_path,
        "n_samples": len(samples),
        "json_validity": n_ok / max(1, len(samples)),
        "n_predictions": len(predictions),
        "mean_latency_s": float(np.mean(latencies)) if latencies else 0.0,
        "elapsed_s": elapsed,
    })
    out_path = RESULTS_DIR / f"metrics_{key}.json"
    with out_path.open("w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2, ensure_ascii=False)
    print(f"[eval] DONE. mAP@0.5={metrics['mAP_50']:.4f} json_validity={metrics['json_validity']:.3f}")
    print(f"[eval] metrics -> {out_path}")
    print(f"[eval] predictions -> {pred_jsonl_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
