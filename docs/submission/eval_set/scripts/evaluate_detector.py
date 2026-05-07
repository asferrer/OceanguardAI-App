#!/usr/bin/env python
"""Evaluate the RT-DETRv2 TFLite detector on the OceanGuard eval set.

Mirrors the Android pipeline in `android/app/src/main/java/com/oceanguard/
inference/RTDETRInference.kt`:
- Input:  640x640 RGB, normalised to [0, 1] (no mean/std subtraction)
- Output: boxes (1, 150, 4) in cxcywh normalised + logits (1, 150, 8)
- Decode: sigmoid(logits) -> per-class confidence, threshold + NMS

Reports COCO mAP@0.5, mAP@0.5:0.95, AR@100, plus per-class AP@0.5.

Usage:
  python evaluate_detector.py \\
      --annotations annotations/eval_set_v1.json \\
      --images images/ \\
      --model ../../../android/app/src/main/assets/models/rtdetrv2_detector.tflite \\
      --threshold 0.30 \\
      --iou 0.50
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image
from pycocotools.coco import COCO
from pycocotools.cocoeval import COCOeval
from tabulate import tabulate
from tqdm import tqdm

try:
    import tensorflow as tf
except ImportError:
    print("tensorflow is required. pip install -r requirements.txt", file=sys.stderr)
    raise

INPUT_SIZE = 640
NUM_QUERIES = 150
NUM_CLASSES = 8

CLASS_NAMES = [
    "Bottle", "Can", "Fishing_Net", "Glove", "Mask",
    "Metal_Debris", "Plastic_Debris", "Tire",
]


def load_interpreter(model_path: Path) -> tf.lite.Interpreter:
    interp = tf.lite.Interpreter(model_path=str(model_path), num_threads=8)
    interp.allocate_tensors()
    return interp


def preprocess(img_path: Path) -> tuple[np.ndarray, tuple[int, int]]:
    img = Image.open(img_path).convert("RGB")
    orig_w, orig_h = img.size
    resized = img.resize((INPUT_SIZE, INPUT_SIZE), Image.BILINEAR)
    arr = np.asarray(resized, dtype=np.float32) / 255.0
    arr = arr[np.newaxis, ...]
    return arr, (orig_w, orig_h)


def cxcywh_norm_to_xyxy_pixels(boxes: np.ndarray, orig_w: int, orig_h: int) -> np.ndarray:
    """Convert (N, 4) cxcywh in [0,1] to (N, 4) xyxy in pixels of the
    original image (no letterbox: matches the Android resize-only pipeline).
    """
    cx, cy, w, h = boxes[:, 0], boxes[:, 1], boxes[:, 2], boxes[:, 3]
    x1 = (cx - w / 2.0) * orig_w
    y1 = (cy - h / 2.0) * orig_h
    x2 = (cx + w / 2.0) * orig_w
    y2 = (cy + h / 2.0) * orig_h
    return np.stack([x1, y1, x2, y2], axis=-1)


def nms(boxes_xyxy: np.ndarray, scores: np.ndarray, iou_thresh: float) -> list[int]:
    if len(boxes_xyxy) == 0:
        return []
    x1, y1, x2, y2 = boxes_xyxy.T
    areas = (x2 - x1) * (y2 - y1)
    order = scores.argsort()[::-1]
    keep: list[int] = []
    while order.size > 0:
        i = int(order[0])
        keep.append(i)
        if order.size == 1:
            break
        rest = order[1:]
        xx1 = np.maximum(x1[i], x1[rest])
        yy1 = np.maximum(y1[i], y1[rest])
        xx2 = np.minimum(x2[i], x2[rest])
        yy2 = np.minimum(y2[i], y2[rest])
        w = np.clip(xx2 - xx1, 0, None)
        h = np.clip(yy2 - yy1, 0, None)
        inter = w * h
        iou = inter / (areas[i] + areas[rest] - inter + 1e-6)
        order = rest[iou <= iou_thresh]
    return keep


def detect(
    interp: tf.lite.Interpreter,
    img_arr: np.ndarray,
    orig_w: int,
    orig_h: int,
    score_thresh: float,
    iou_thresh: float,
) -> list[tuple[float, float, float, float, float, int]]:
    """Run inference. Returns list of (x, y, w, h, score, class_id) in
    pixel coordinates of the original image.
    """
    in_details = interp.get_input_details()
    out_details = interp.get_output_details()
    interp.set_tensor(in_details[0]["index"], img_arr)
    interp.invoke()

    raw0 = interp.get_tensor(out_details[0]["index"])
    raw1 = interp.get_tensor(out_details[1]["index"])
    if raw0.shape[-1] == 4:
        boxes = raw0.squeeze(0)
        logits = raw1.squeeze(0)
    else:
        boxes = raw1.squeeze(0)
        logits = raw0.squeeze(0)

    scores_all = 1.0 / (1.0 + np.exp(-logits))
    cls_ids = scores_all.argmax(axis=1)
    cls_scores = scores_all[np.arange(NUM_QUERIES), cls_ids]

    mask = cls_scores >= score_thresh
    if not mask.any():
        return []

    boxes_xyxy = cxcywh_norm_to_xyxy_pixels(boxes[mask], orig_w, orig_h)
    cls_ids = cls_ids[mask]
    cls_scores = cls_scores[mask]

    detections: list[tuple[float, float, float, float, float, int]] = []
    for c in range(NUM_CLASSES):
        cm = cls_ids == c
        if not cm.any():
            continue
        keep = nms(boxes_xyxy[cm], cls_scores[cm], iou_thresh)
        for k in keep:
            x1, y1, x2, y2 = boxes_xyxy[cm][k]
            detections.append((
                float(x1), float(y1),
                float(x2 - x1), float(y2 - y1),
                float(cls_scores[cm][k]),
                c,
            ))
    return detections


def run_eval(args: argparse.Namespace) -> int:
    coco_gt = COCO(str(args.annotations))
    interp = load_interpreter(args.model)

    predictions = []
    for img_id in tqdm(coco_gt.getImgIds(), desc="inference"):
        img_meta = coco_gt.loadImgs(img_id)[0]
        img_path = args.images / img_meta["file_name"]
        if not img_path.is_file():
            print(f"  skip (missing): {img_path}", file=sys.stderr)
            continue

        arr, (w, h) = preprocess(img_path)
        dets = detect(interp, arr, w, h, args.threshold, args.iou)
        for x, y, bw, bh, score, cls in dets:
            predictions.append({
                "image_id": img_id,
                "category_id": cls,
                "bbox": [x, y, bw, bh],
                "score": score,
            })

    if not predictions:
        print("\nNo predictions produced — check threshold / model / images.")
        return 1

    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(predictions, f)
        pred_path = f.name

    coco_dt = coco_gt.loadRes(pred_path)
    evaluator = COCOeval(coco_gt, coco_dt, iouType="bbox")
    evaluator.evaluate()
    evaluator.accumulate()
    evaluator.summarize()

    # Per-class AP@0.5
    print("\nPer-class AP@0.5:")
    rows = []
    precisions = evaluator.eval["precision"]  # [T, R, K, A, M]
    iou50_idx = 0  # first IoU threshold = 0.50
    for k, name in enumerate(CLASS_NAMES):
        p = precisions[iou50_idx, :, k, 0, -1]
        p = p[p > -1]
        ap = float(p.mean()) if p.size else float("nan")
        rows.append([k, name, f"{ap:.3f}"])
    print(tabulate(rows, headers=["id", "class", "AP@0.5"], tablefmt="github"))

    # Headline numbers (drop into the writeup)
    stats = evaluator.stats
    print("\nHeadline metrics for the writeup:")
    print(f"  mAP@0.5:0.95   = {stats[0]:.3f}")
    print(f"  mAP@0.5        = {stats[1]:.3f}")
    print(f"  mAP@0.75       = {stats[2]:.3f}")
    print(f"  AR@100         = {stats[8]:.3f}")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--annotations", type=Path, required=True)
    p.add_argument("--images", type=Path, required=True)
    p.add_argument("--model", type=Path, required=True,
                   help="path to rtdetrv2_detector.tflite (FP16 or INT8)")
    p.add_argument("--threshold", type=float, default=0.30,
                   help="confidence threshold (matches Android default)")
    p.add_argument("--iou", type=float, default=0.50,
                   help="NMS IoU threshold")
    args = p.parse_args()

    for path in (args.annotations, args.images, args.model):
        if not path.exists():
            print(f"not found: {path}", file=sys.stderr)
            return 2

    return run_eval(args)


if __name__ == "__main__":
    raise SystemExit(main())
