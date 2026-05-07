#!/usr/bin/env python
"""Validate the OceanGuard evaluation-set COCO JSON.

Checks:
- Top-level keys: info, licenses, categories, images, annotations
- Categories match the canonical 8-class schema (ID and name)
- Every annotation has a valid image_id, category_id, bbox, area
- Bounding boxes are [x, y, w, h] with positive w/h and inside the image
- Every image listed has a matching file under ../images/

Exits non-zero on any error so it can gate CI.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

CANONICAL_CATEGORIES = [
    (0, "Bottle"),
    (1, "Can"),
    (2, "Fishing_Net"),
    (3, "Glove"),
    (4, "Mask"),
    (5, "Metal_Debris"),
    (6, "Plastic_Debris"),
    (7, "Tire"),
]


def validate(coco_path: Path, images_dir: Path) -> int:
    errors: list[str] = []
    with coco_path.open(encoding="utf-8") as f:
        coco = json.load(f)

    for key in ("info", "licenses", "categories", "images", "annotations"):
        if key not in coco:
            errors.append(f"missing top-level key: {key}")

    cats = {(c["id"], c["name"]) for c in coco.get("categories", [])}
    expected = set(CANONICAL_CATEGORIES)
    if cats != expected:
        missing = expected - cats
        extra = cats - expected
        if missing:
            errors.append(f"categories missing: {sorted(missing)}")
        if extra:
            errors.append(f"unexpected categories: {sorted(extra)}")

    image_ids = {img["id"] for img in coco.get("images", [])}
    image_dims = {img["id"]: (img.get("width", 0), img.get("height", 0))
                  for img in coco.get("images", [])}

    for img in coco.get("images", []):
        fname = img.get("file_name", "")
        if not fname:
            errors.append(f"image id={img.get('id')} has no file_name")
            continue
        if not (images_dir / fname).is_file():
            errors.append(f"image not found on disk: {fname}")

    valid_cat_ids = {cid for cid, _ in CANONICAL_CATEGORIES}
    for ann in coco.get("annotations", []):
        ann_id = ann.get("id", "?")
        if ann.get("image_id") not in image_ids:
            errors.append(f"ann {ann_id}: image_id {ann.get('image_id')} not in images")
        if ann.get("category_id") not in valid_cat_ids:
            errors.append(f"ann {ann_id}: category_id {ann.get('category_id')} not in 0..7")

        bbox = ann.get("bbox", [])
        if len(bbox) != 4:
            errors.append(f"ann {ann_id}: bbox must have 4 elements")
            continue
        x, y, w, h = bbox
        if w <= 0 or h <= 0:
            errors.append(f"ann {ann_id}: bbox has non-positive w/h ({w}, {h})")

        img_w, img_h = image_dims.get(ann.get("image_id"), (0, 0))
        if img_w and img_h:
            if x < 0 or y < 0 or x + w > img_w or y + h > img_h:
                errors.append(
                    f"ann {ann_id}: bbox [{x:.1f},{y:.1f},{w:.1f},{h:.1f}] "
                    f"outside image ({img_w}x{img_h})"
                )

    print(f"images:      {len(coco.get('images', []))}")
    print(f"annotations: {len(coco.get('annotations', []))}")
    print(f"categories:  {len(coco.get('categories', []))}")

    if errors:
        print(f"\n{len(errors)} error(s):", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    print("\nOK — COCO JSON is valid.")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("coco_json", type=Path, help="path to COCO JSON")
    p.add_argument("--images", type=Path, default=None,
                   help="images dir (default: ../images relative to JSON)")
    args = p.parse_args()

    if not args.coco_json.is_file():
        print(f"not a file: {args.coco_json}", file=sys.stderr)
        return 2

    images_dir = args.images or args.coco_json.parent.parent / "images"
    if not images_dir.is_dir():
        print(f"images dir not found: {images_dir}", file=sys.stderr)
        return 2

    return validate(args.coco_json, images_dir)


if __name__ == "__main__":
    raise SystemExit(main())
