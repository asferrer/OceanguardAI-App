"""
Aquarium Combined downloader — Roboflow RF100, license: CC (Roboflow's RF100 terms).

Source: HuggingFace Francesco/aquarium-qlnqy
  https://huggingface.co/datasets/Francesco/aquarium-qlnqy
  File: dataset.tar.gz (38.7 MB, ~649 members, COCO JSON format)

Classes (7): fish, jellyfish, penguin, puffin, shark, starfish, stingray
  (note: "aquarium" is also present as a class ID 0 — it's scene-level, not an animal;
   we exclude it from marine-species YOLO output and remap IDs 1-7 → 0-6)

Splits: train (~450 imgs), valid (~115 imgs), test (~75 imgs)
Total: ~638 images, ~3,000 annotations

LICENSE CAVEAT:
  The HuggingFace card says 'cc' (unversioned). The Roboflow RF100 page shows
  CC BY 4.0 for data attribution. The README.roboflow.txt inside the archive reads:
  "Aquarium Combined. 638 images." with Roboflow export credits.
  This dataset is generally treated as CC-BY 4.0 in published benchmarks (RF100 paper,
  ODinW leaderboard) but Roboflow has not published a specific SPDX identifier.
  Do NOT use commercially without reviewing the Roboflow Terms of Service.

Estimated download: 38.7 MB. Time at 100 Mbps: ~3 seconds.

Dependencies: pip install requests tqdm
"""

from __future__ import annotations

import io
import json
import sys
import tarfile
from pathlib import Path

import requests
from tqdm import tqdm

SLUG = "aquarium"
DATASET_DIR = Path(__file__).parent / "datasets" / SLUG
IMAGES_DIR = DATASET_DIR / "images"
LABELS_DIR = DATASET_DIR / "labels"

HF_URL = "https://huggingface.co/datasets/Francesco/aquarium-qlnqy/resolve/main/dataset.tar.gz"
TAR_CACHE = DATASET_DIR / "dataset.tar.gz"
DONE_SENTINEL = DATASET_DIR / ".done"

# Class remapping: COCO category_id → YOLO class id
# category_id 0 = "aquarium" (scene class) — EXCLUDED
# category_ids 1-7 map to 0-6
COCO_TO_YOLO: dict[int, int] = {
    1: 0,  # fish
    2: 1,  # jellyfish
    3: 2,  # penguin
    4: 3,  # puffin
    5: 4,  # shark
    6: 5,  # starfish
    7: 6,  # stingray
}
CLASS_NAMES = ["fish", "jellyfish", "penguin", "puffin", "shark", "starfish", "stingray"]


def _download_tar() -> None:
    print(f"Downloading aquarium dataset.tar.gz (~38.7 MB)...")
    resp = requests.get(HF_URL, stream=True, timeout=60)
    resp.raise_for_status()
    total = int(resp.headers.get("content-length", 0))
    TAR_CACHE.parent.mkdir(parents=True, exist_ok=True)
    with open(TAR_CACHE, "wb") as f, tqdm(
        total=total, unit="B", unit_scale=True, desc="aquarium.tar.gz", leave=False
    ) as bar:
        for chunk in resp.iter_content(chunk_size=65536):
            f.write(chunk)
            bar.update(len(chunk))


def _coco_bbox_to_yolo(bbox: list[float], img_w: int, img_h: int) -> tuple[float, float, float, float]:
    """COCO bbox [x_min, y_min, w, h] → YOLO normalised [cx, cy, w, h]."""
    x_min, y_min, w, h = bbox
    cx = (x_min + w / 2.0) / img_w
    cy = (y_min + h / 2.0) / img_h
    nw = w / img_w
    nh = h / img_h
    return cx, cy, nw, nh


def _extract_and_convert() -> tuple[int, int]:
    """Extract the tar.gz and convert COCO JSON annotations to per-image YOLO txt files."""
    IMAGES_DIR.mkdir(parents=True, exist_ok=True)
    LABELS_DIR.mkdir(parents=True, exist_ok=True)

    n_images = 0
    n_boxes = 0

    with tarfile.open(TAR_CACHE, "r:gz") as tf:
        members = tf.getmembers()

        # Pass 1: find annotation JSON files and parse them
        coco_anns: dict[str, dict] = {}  # split_name -> parsed COCO dict
        for m in members:
            if "_annotations.coco.json" in m.name and m.isfile():
                # extract split from path: .../train/_annotations.coco.json
                parts = Path(m.name).parts
                split_name = parts[-2] if len(parts) >= 2 else "train"
                f = tf.extractfile(m)
                if f:
                    coco_anns[split_name] = json.load(f)

        # Build filename → annotations index per split
        ann_by_split: dict[str, dict[int, list]] = {}  # split -> {image_id: [anns]}
        img_meta_by_split: dict[str, dict[int, dict]] = {}  # split -> {image_id: {w,h,fname}}

        for split_name, coco in coco_anns.items():
            ann_map: dict[int, list] = {}
            img_meta: dict[int, dict] = {}
            for img in coco.get("images", []):
                img_meta[img["id"]] = {
                    "w": img["width"],
                    "h": img["height"],
                    "file_name": img["file_name"],
                }
                ann_map[img["id"]] = []
            for ann in coco.get("annotations", []):
                iid = ann["image_id"]
                if iid in ann_map:
                    ann_map[iid].append(ann)
            ann_by_split[split_name] = ann_map
            img_meta_by_split[split_name] = img_meta

        # Pass 2: extract images and write YOLO labels
        img_members = [m for m in members if m.isfile() and
                       m.name.lower().endswith((".jpg", ".jpeg", ".png"))]

        for m in tqdm(img_members, desc="  aquarium"):
            parts = Path(m.name).parts
            if len(parts) < 2:
                continue
            split_name = parts[-2]
            fname = parts[-1]

            # Find image ID via metadata
            meta_map = img_meta_by_split.get(split_name, {})
            img_id = None
            img_w, img_h = 640, 480
            for iid, meta in meta_map.items():
                if meta["file_name"] == fname or Path(meta["file_name"]).name == fname:
                    img_id = iid
                    img_w = meta["w"]
                    img_h = meta["h"]
                    break

            # Determine output filenames
            stem = Path(fname).stem
            out_key = f"{split_name}_{stem}"
            split_img_dir = IMAGES_DIR / split_name
            split_lbl_dir = LABELS_DIR / split_name
            split_img_dir.mkdir(parents=True, exist_ok=True)
            split_lbl_dir.mkdir(parents=True, exist_ok=True)

            out_img = split_img_dir / f"{stem}.jpg"
            out_lbl = split_lbl_dir / f"{stem}.txt"

            if not out_img.exists():
                f = tf.extractfile(m)
                if f:
                    img_bytes = f.read()
                    with open(out_img, "wb") as fp:
                        fp.write(img_bytes)

            if not out_lbl.exists():
                lines = []
                if img_id is not None:
                    anns = ann_by_split.get(split_name, {}).get(img_id, [])
                    for ann in anns:
                        cat_id = ann.get("category_id", 0)
                        yolo_cls = COCO_TO_YOLO.get(cat_id)
                        if yolo_cls is None:
                            continue  # skip "aquarium" scene class
                        bbox = ann.get("bbox", [])
                        if len(bbox) != 4:
                            continue
                        cx, cy, w, h = _coco_bbox_to_yolo(bbox, img_w, img_h)
                        lines.append(f"{yolo_cls} {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}")
                        n_boxes += 1
                with open(out_lbl, "w") as lf:
                    lf.write("\n".join(lines))

            n_images += 1

    return n_images, n_boxes


def main() -> None:
    DATASET_DIR.mkdir(parents=True, exist_ok=True)

    if DONE_SENTINEL.exists():
        n_img = sum(1 for _ in IMAGES_DIR.rglob("*.jpg"))
        n_lbl = sum(1 for _ in LABELS_DIR.rglob("*.txt"))
        print(f"aquarium: {n_img} images, {n_lbl} labels  (already complete)")
        return

    with open(DATASET_DIR / "classes.txt", "w") as f:
        f.write("\n".join(CLASS_NAMES))

    try:
        if not TAR_CACHE.exists():
            _download_tar()
        else:
            print("aquarium dataset.tar.gz already cached.")
    except requests.RequestException as e:
        print(f"ERROR downloading aquarium: {e}")
        sys.exit(1)

    try:
        n_img, n_box = _extract_and_convert()
    except Exception as e:
        print(f"ERROR extracting aquarium: {e}")
        sys.exit(1)

    DONE_SENTINEL.touch()
    print(f"aquarium: {n_img} images, {n_box} labels")


if __name__ == "__main__":
    main()
