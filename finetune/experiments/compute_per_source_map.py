"""Per-source mAP@0.5 on the existing holdout predictions.

Uses the official `manual_map_eval` from eval_grid.py so the calculation
matches the project's own fallback path (no pycocotools dependency).
"""
from __future__ import annotations

import importlib.util
import json
import pathlib
import sys
from collections import defaultdict

import numpy as np  # noqa: F401  — used inside manual_map_eval

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

# Pull in the official manual mAP fallback so our numbers are computed
# with the exact same method as the project ever uses when pycocotools
# is missing.
spec = importlib.util.spec_from_file_location("_eg", HERE / "eval_grid.py")
_eg = importlib.util.module_from_spec(spec)
# eval_grid imports heavy ML deps at module top — patch them out so we
# can pull manual_map_eval without loading torch/unsloth.
import types as _t
for name in ("torch", "unsloth", "PIL"):
    sys.modules.setdefault(name, _t.ModuleType(name))

try:
    spec.loader.exec_module(_eg)            # type: ignore
    manual_map_eval = _eg.manual_map_eval
    LABEL_TO_CAT = _eg.LABEL_TO_CAT
    CATEGORY_MAP = _eg.CATEGORY_MAP
    CLASS_NAMES = _eg.CLASS_NAMES
except Exception:
    # Inline replicate of manual_map_eval (11-point VOC AP), in case the
    # heavy imports still fail.
    print("[warn] could not import eval_grid; using inlined manual_map_eval")
    LABEL_TO_CAT = {
        "plastic_bottle": 0, "bottle": 0,
        "metal_can": 1, "aluminum_can": 1, "tin_can": 1, "can": 1,
        "fishing_net": 2, "net": 2, "ghost_net": 2,
        "glove": 3,
        "face_mask": 4, "mask": 4,
        "metal_debris": 5, "scrap_metal": 5, "metal_container": 5,
        "metal_cable": 5, "metal_wreckage": 5,
        "plastic_debris": 6, "plastic_bag": 6, "plastic_container": 6,
        "plastic_cup": 6, "plastic_lid": 6, "plastic_pipe": 6,
        "snack_wrapper": 6, "tarp": 6, "sanitary_item": 6,
        "glass_jar": 0, "ceramic_cup": 6, "clothing": 6,
        "tire": 7,
    }
    CATEGORY_MAP = {i: (n, "") for i, n in enumerate([
        "Bottle", "Can", "Fishing_Net", "Glove", "Mask",
        "Metal_Debris", "Plastic_Debris", "Tire"])}
    CLASS_NAMES = [CATEGORY_MAP[i][0] for i in range(8)]

    def manual_map_eval(samples, predictions):
        preds_by_ic, gts_by_ic = {}, {}
        for p in predictions:
            preds_by_ic.setdefault((p["image_id"], p["category_id"]), []).append(p)
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

        per_class = {n: 0.0 for n in CLASS_NAMES}
        for cid in range(8):
            name = CLASS_NAMES[cid]
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
                continue
            tp_c = np.cumsum(tp); fp_c = np.cumsum(fp)
            recalls = tp_c / gts_count
            precisions = tp_c / np.maximum(tp_c + fp_c, 1)
            ap = 0.0
            for t in np.linspace(0, 1, 11):
                mask = recalls >= t
                ap += (precisions[mask].max() if mask.any() else 0.0) / 11
            per_class[name] = float(ap)
            for (img, c), lst in gts_by_ic.items():
                if c == cid:
                    for g in lst: g["matched"] = False
        mAP50 = float(np.mean(list(per_class.values()))) if per_class else 0.0
        return {"mAP_50": mAP50, "per_class_mAP_50": per_class,
                "n_predictions": len(predictions)}


HOLDOUT  = pathlib.Path("finetune/experiments/splits/real_test_holdout.jsonl")
PRED_DIR = pathlib.Path("finetune/experiments/results")


def detect_source(image_path: str) -> str:
    for token in ("CleanSea", "Neural_Ocean", "Ocean_garbage"):
        if token in image_path:
            return token
    return "UNK"


def gemma_box_to_pixel(box_2d, w, h):
    ymin, xmin, ymax, xmax = box_2d
    x = xmin / 1000.0 * w
    y = ymin / 1000.0 * h
    bw = max(0.0, (xmax - xmin) / 1000.0 * w)
    bh = max(0.0, (ymax - ymin) / 1000.0 * h)
    return [x, y, bw, bh]


def predictions_for(pred_rows, allowed_ids, dims):
    out = []
    for r in pred_rows:
        img_id = int(r["img_id"])
        if img_id not in allowed_ids:
            continue
        W, H = dims[img_id]
        for det in (r.get("parsed") or []):
            if not isinstance(det, dict):
                continue
            box = det.get("box_2d")
            label = det.get("label")
            if not box or label is None: continue
            if not isinstance(box, list) or len(box) != 4: continue
            cat_id = LABEL_TO_CAT.get(str(label).lower())
            if cat_id is None: continue
            try:
                bb = gemma_box_to_pixel([int(v) for v in box], W, H)
            except (TypeError, ValueError):
                continue
            if bb[2] <= 0 or bb[3] <= 0: continue
            out.append({"image_id": img_id, "category_id": int(cat_id),
                        "bbox": bb, "score": 1.0})
    return out


def evaluate(samples, pred_rows):
    allowed = {int(s["img_id"]) for s in samples}
    dims = {int(s["img_id"]): (int(s["width"]), int(s["height"])) for s in samples}
    preds = predictions_for(pred_rows, allowed, dims)
    return manual_map_eval(samples, preds)


def main():
    holdout = [json.loads(l) for l in HOLDOUT.open(encoding="utf-8")]
    holdout_by_id = {int(r["img_id"]): r for r in holdout}
    print("=" * 72)
    print("Per-source mAP@0.5 — restricted to the 200 ids actually predicted")
    print("=" * 72)

    table = []
    for pred_name, pred_path in [
        ("BASE  Gemma 4 E2B",          PRED_DIR / "predictions_base.jsonl"),
        ("LoRA  exp12_vision_lora",    PRED_DIR / "predictions_exp12_vision_lora.jsonl"),
    ]:
        if not pred_path.exists():
            print(f"  SKIP {pred_path} not found")
            continue
        pred_rows = [json.loads(l) for l in pred_path.open(encoding="utf-8")]
        evaluated_ids = {int(r["img_id"]) for r in pred_rows}
        evaluated_samples = [holdout_by_id[i] for i in evaluated_ids if i in holdout_by_id]
        by_source = defaultdict(list)
        for r in evaluated_samples:
            by_source[detect_source(r["image_path"])].append(r)
        print(f"\n>>> {pred_name}")
        print(f"   n_evaluated={len(evaluated_samples)}  by source: " + ", ".join(
            f"{k}={len(v)}" for k, v in sorted(by_source.items())))
        row = {"model": pred_name, "_n": {}}
        m_all = evaluate(evaluated_samples, pred_rows)
        row["GLOBAL"] = m_all["mAP_50"]
        row["_n"]["GLOBAL"] = len(evaluated_samples)
        for source in ("CleanSea", "Neural_Ocean", "Ocean_garbage"):
            rows = by_source.get(source, [])
            if not rows: continue
            row[source] = evaluate(rows, pred_rows)["mAP_50"]
            row["_n"][source] = len(rows)
        table.append(row)

    # Print
    sources = ["GLOBAL", "CleanSea", "Neural_Ocean", "Ocean_garbage"]
    print()
    print(f"{'Model':<28} " + " ".join(f"{s:>14}" for s in sources))
    print("-" * (28 + 16 * len(sources)))
    for row in table:
        print(f"{row['model']:<28} " + " ".join(
            f"{row.get(s, 0.0):>14.4f}" for s in sources))
    print()
    # Deltas
    if len(table) == 2:
        base, lora = table[0], table[1]
        print(f"{'Delta LoRA - Base':<28} " + " ".join(
            f"{(lora.get(s,0)-base.get(s,0)):>+14.4f}" for s in sources))
        print(f"{'Relative LoRA / Base':<28} " + " ".join(
            (f"{(lora.get(s,0)/base.get(s,0)):>13.2f}x" if base.get(s,0) > 0
             else f"{'n/a':>14}") for s in sources))


if __name__ == "__main__":
    main()
