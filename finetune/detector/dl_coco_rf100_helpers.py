"""
dl_coco_rf100_helpers.py — Shared COCO-to-YOLO conversion helpers for RF100 tar.gz datasets.

Used by dl_urpc_reef.py and dl_peixos.py (and any future RF100 HF mirror downloaders).
All categories are collapsed to class 0 (organism) for the class-agnostic detector.
"""

from __future__ import annotations

import json
import tarfile
from pathlib import Path

from tqdm import tqdm


def coco_bbox_to_yolo(
    bbox: list[float], img_w: int, img_h: int
) -> tuple[float, float, float, float]:
    """COCO [x_min, y_min, w, h] → YOLO normalised [cx, cy, w, h]."""
    x_min, y_min, w, h = bbox
    return (x_min + w / 2.0) / img_w, (y_min + h / 2.0) / img_h, w / img_w, h / img_h


def _parse_coco_annotations(
    tf: tarfile.TarFile,
    members: list[tarfile.TarInfo],
) -> tuple[dict[str, dict[int, list]], dict[str, dict[int, dict]]]:
    """
    Pass 1: read all *_annotations.coco.json members from an open TarFile.
    Returns (ann_by_split, meta_by_split).
    """
    coco_anns: dict[str, dict] = {}
    for m in members:
        if "_annotations.coco.json" in m.name and m.isfile():
            split_name = Path(m.name).parts[-2] if len(Path(m.name).parts) >= 2 else "train"
            fh = tf.extractfile(m)
            if fh:
                coco_anns[split_name] = json.load(fh)

    ann_by_split: dict[str, dict[int, list]] = {}
    meta_by_split: dict[str, dict[int, dict]] = {}

    for split, coco in coco_anns.items():
        ann_map: dict[int, list] = {}
        img_meta: dict[int, dict] = {}
        for img in coco.get("images", []):
            img_meta[img["id"]] = {
                "w": img["width"], "h": img["height"], "file_name": img["file_name"]
            }
            ann_map[img["id"]] = []
        for ann in coco.get("annotations", []):
            if ann["image_id"] in ann_map:
                ann_map[ann["image_id"]].append(ann)
        ann_by_split[split] = ann_map
        meta_by_split[split] = img_meta

    return ann_by_split, meta_by_split


def _resolve_image_meta(
    fname: str,
    meta_map: dict[int, dict],
) -> tuple[int | None, int, int]:
    """Look up image_id and dimensions by filename in a COCO meta dict."""
    for iid, meta in meta_map.items():
        if meta["file_name"] == fname or Path(meta["file_name"]).name == fname:
            return iid, meta["w"], meta["h"]
    return None, 640, 640


def _write_yolo_label(
    out_lbl: Path,
    img_id: int | None,
    img_w: int,
    img_h: int,
    anns: list[dict],
    organism_ids: set[int] | None,
) -> int:
    """Write YOLO label file; returns number of boxes written."""
    if out_lbl.exists():
        return 0
    lines: list[str] = []
    for ann in anns:
        cat_id = ann.get("category_id", 0)
        if organism_ids is not None and cat_id not in organism_ids:
            continue
        if cat_id == 0:
            continue
        bbox = ann.get("bbox", [])
        if len(bbox) != 4:
            continue
        cx, cy, w, h = coco_bbox_to_yolo(bbox, img_w, img_h)
        lines.append(f"0 {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}")
    out_lbl.write_text("\n".join(lines), encoding="utf-8")
    return len(lines)


def extract_rf100_tar(
    tar_path: Path,
    images_dir: Path,
    labels_dir: Path,
    desc: str,
    organism_ids: set[int] | None = None,
) -> tuple[int, int]:
    """
    Extract images and write YOLO labels from an RF100 COCO tar.gz.
    organism_ids: COCO category_ids to include (None = all except 0).
    Returns (n_images, n_boxes).
    """
    images_dir.mkdir(parents=True, exist_ok=True)
    labels_dir.mkdir(parents=True, exist_ok=True)

    n_images = 0
    n_boxes = 0

    with tarfile.open(tar_path, "r:gz") as tf:
        members = tf.getmembers()
        ann_by_split, meta_by_split = _parse_coco_annotations(tf, members)

        img_members = [
            m for m in members
            if m.isfile() and m.name.lower().endswith((".jpg", ".jpeg", ".png"))
        ]

        for m in tqdm(img_members, desc=f"  {desc}"):
            parts = Path(m.name).parts
            if len(parts) < 2:
                continue
            split_name, fname = parts[-2], parts[-1]
            stem = Path(fname).stem

            split_img_dir = images_dir / split_name
            split_lbl_dir = labels_dir / split_name
            split_img_dir.mkdir(parents=True, exist_ok=True)
            split_lbl_dir.mkdir(parents=True, exist_ok=True)

            out_img = split_img_dir / f"{stem}.jpg"
            out_lbl = split_lbl_dir / f"{stem}.txt"

            if not out_img.exists():
                fh = tf.extractfile(m)
                if fh:
                    out_img.write_bytes(fh.read())

            img_id, img_w, img_h = _resolve_image_meta(
                fname, meta_by_split.get(split_name, {})
            )
            anns = ann_by_split.get(split_name, {}).get(img_id or -1, [])
            n_boxes += _write_yolo_label(out_lbl, img_id, img_w, img_h, anns, organism_ids)
            n_images += 1

    return n_images, n_boxes
