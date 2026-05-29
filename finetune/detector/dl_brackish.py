"""
Brackish dataset downloader — Aalborg University, CC-BY 4.0.

Source: HuggingFace moondream/brackish_underwater
  https://huggingface.co/datasets/moondream/brackish_underwater

Format in parquet:
  - image: PIL Image (1920x1080 JPG)
  - objects: list of dicts with 'name' and 'boxes' (normalised [x_min, y_min, x_max, y_max])

Classes (6): crab, fish, jellyfish, shrimp, small_fish, starfish
Splits: train=9967, test=1238, valid=1239  (~12.4k total)

Total download: ~537 MB (3 parquet files)
Estimated time at 100 Mbps: ~45 seconds

Dependencies: pip install requests tqdm pyarrow pandas Pillow
"""

from __future__ import annotations

import io
import json
import struct
import sys
from pathlib import Path

import requests
from tqdm import tqdm

SLUG = "brackish"
DATASET_DIR = Path(__file__).parent / "datasets" / SLUG
IMAGES_DIR = DATASET_DIR / "images"
LABELS_DIR = DATASET_DIR / "labels"

HF_BASE = "https://huggingface.co/datasets/moondream/brackish_underwater/resolve/main/data"
PARQUET_FILES = {
    "train": f"{HF_BASE}/train-00000-of-00001.parquet",
    "test": f"{HF_BASE}/test-00000-of-00001.parquet",
    "valid": f"{HF_BASE}/valid-00000-of-00001.parquet",
}

# YOLO class mapping (alphabetical to keep it deterministic)
CLASS_NAMES = ["crab", "fish", "jellyfish", "shrimp", "small_fish", "starfish"]
CLASS_MAP = {name: idx for idx, name in enumerate(CLASS_NAMES)}

# Sentinel file written after successful conversion
DONE_SENTINEL = DATASET_DIR / ".done"


def _download_file(url: str, dest: Path) -> None:
    """Stream-download with progress bar."""
    resp = requests.get(url, stream=True, timeout=120)
    resp.raise_for_status()
    total = int(resp.headers.get("content-length", 0))
    dest.parent.mkdir(parents=True, exist_ok=True)
    with open(dest, "wb") as f, tqdm(
        total=total, unit="B", unit_scale=True, desc=dest.name, leave=False
    ) as bar:
        for chunk in resp.iter_content(chunk_size=65536):
            f.write(chunk)
            bar.update(len(chunk))


def _process_parquet(parquet_path: Path, split: str) -> tuple[int, int]:
    """
    Read a parquet file and write images + YOLO labels.
    Returns (n_images, n_boxes).
    """
    try:
        import pyarrow.parquet as pq
    except ImportError:
        print("ERROR: pyarrow not found. Run: pip install pyarrow pandas Pillow")
        sys.exit(1)

    from PIL import Image as PILImage

    split_img_dir = IMAGES_DIR / split
    split_lbl_dir = LABELS_DIR / split
    split_img_dir.mkdir(parents=True, exist_ok=True)
    split_lbl_dir.mkdir(parents=True, exist_ok=True)

    table = pq.read_table(parquet_path)
    df = table.to_pandas()

    n_images = 0
    n_boxes = 0
    for _, row in tqdm(df.iterrows(), total=len(df), desc=f"  {split}", leave=False):
        img_id = f"{split}_{n_images:06d}"

        # Save image
        img_path = split_img_dir / f"{img_id}.jpg"
        if not img_path.exists():
            img_data = row["image"]
            # In parquet the image column may be bytes or a dict with 'bytes'
            if isinstance(img_data, dict):
                img_bytes = img_data.get("bytes") or img_data.get("path")
                if isinstance(img_bytes, str):
                    img_bytes = img_bytes.encode()
            elif isinstance(img_data, (bytes, bytearray)):
                img_bytes = bytes(img_data)
            else:
                img_bytes = bytes(img_data)
            pil = PILImage.open(io.BytesIO(img_bytes)).convert("RGB")
            pil.save(img_path, "JPEG", quality=95)

        # Write YOLO label
        lbl_path = split_lbl_dir / f"{img_id}.txt"
        if not lbl_path.exists():
            objects = row.get("objects", [])
            lines = []
            for obj in objects:
                name = obj.get("name", "fish")
                cls_id = CLASS_MAP.get(name, CLASS_MAP.get("fish", 1))
                # 'boxes' is an ARRAY of bboxes (not a single 4-tuple); each
                # element is [x_min, y_min, x_max, y_max] normalised to [0,1].
                # Earlier version unpacked the outer array as if it were a single
                # bbox, which yielded np.ndarrays in the format string.
                boxes = obj.get("boxes", [])
                for raw_box in boxes:
                    box = list(raw_box)
                    if len(box) != 4:
                        continue
                    x_min, y_min, x_max, y_max = (float(v) for v in box)
                    cx = (x_min + x_max) / 2.0
                    cy = (y_min + y_max) / 2.0
                    w = x_max - x_min
                    h = y_max - y_min
                    lines.append(f"{cls_id} {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}")
                    n_boxes += 1
            with open(lbl_path, "w") as lf:
                lf.write("\n".join(lines))

        n_images += 1

    return n_images, n_boxes


def main() -> None:
    DATASET_DIR.mkdir(parents=True, exist_ok=True)

    if DONE_SENTINEL.exists():
        n_img = sum(1 for _ in IMAGES_DIR.rglob("*.jpg"))
        n_lbl = sum(1 for _ in LABELS_DIR.rglob("*.txt"))
        print(f"brackish: {n_img} images, {n_lbl} labels  (already complete)")
        return

    # Write class names file
    with open(DATASET_DIR / "classes.txt", "w") as f:
        f.write("\n".join(CLASS_NAMES))

    total_images = 0
    total_boxes = 0

    for split, url in PARQUET_FILES.items():
        parquet_cache = DATASET_DIR / f"{split}.parquet"
        if not parquet_cache.exists():
            print(f"Downloading {split} parquet ({url.split('/')[-1]})...")
            _download_file(url, parquet_cache)
        else:
            print(f"  {split} parquet already cached.")

        print(f"Processing {split}...")
        n_img, n_box = _process_parquet(parquet_cache, split)
        print(f"  {split}: {n_img} images, {n_box} boxes")
        total_images += n_img
        total_boxes += n_box

    DONE_SENTINEL.touch()
    print(f"brackish: {total_images} images, {total_boxes} labels")


if __name__ == "__main__":
    main()
