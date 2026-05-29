"""
dl_openimages_marine.py — Open Images V6 marine-organism subset.

Source: Google Open Images Dataset (bbox annotations from GCS; images from AWS CVDF)
  Annotation CSVs: storage.googleapis.com/openimages/2018_04/...
  Images: open-images-dataset.s3.amazonaws.com/{split}/{image_id}.jpg

Domain fit: IDEAL — Flickr images from amateur/semi-pro photographers, CC-BY 2.0.
  Fish, sharks, sea turtles, jellyfish, octopus, seahorses, stingrays.
  Wide variety of water conditions, backgrounds, and camera distances.
  This is the closest proxy to Samsung S22 snorkel photos available in OI.

Marine classes downloaded (all collapsed to class 0: organism):
  /m/0ch_cf  Fish           ~31 K train images (estimated from 0.41% density)
  /m/0d8zb   Jellyfish      ~4 K  train images
  /m/0by6g   Shark          ~1 K  train images
  /m/0120dh  Sea turtle     ~700  train images
  /m/0nybt   Seahorse       ~140  train images
  /m/0df6p   Manta ray      ~200  train images
  /m/0f54r   Stingray       ~300  train images
  /m/05py0   Octopus        ~400  train images

Default cap: MAX_IMAGES_TRAIN = 5 000 (set via env OI_MAX_TRAIN, use 0 for unlimited)
  + all validation images (~448 marine imgs) always included.
  Capped download: ~5 448 images, ~2.8 GB, ~7-8 min at 50 Mbps.
  Uncapped (all 31K+ fish train): ~15 GB — not recommended for first run.

BBox format in OI CSV: XMin, XMax, YMin, YMax (already normalised 0–1).
YOLO: cx = (XMin+XMax)/2, cy = (YMin+YMax)/2, w = XMax-XMin, h = YMax-YMin.

License: CC BY 2.0 (all OI images are from Flickr with CC-BY 2.0 license, confirmed
  via the per-image metadata CSV column "License" = "creativecommons.org/licenses/by/2.0/").
  OI annotations: Apache 2.0 (Google).
  Source: https://storage.googleapis.com/openimages/web/factsfigures_v7.html

Annotation CSV sizes (downloaded by this script):
  Validation bbox: 17.1 MB
  Train bbox (first 370 MB, streaming partial read until MAX_IMAGES_TRAIN reached)

URL verification (2026-05-29):
  Annotation CSVs: GCS bucket, HTTP 206 with range requests, no auth needed.
  Images: S3 CVDF bucket, HTTP 200, no auth needed.

Caveats:
  - OI bboxes are sometimes GROUP annotations (IsGroupOf=1) with very loose boxes
    around a school of fish. We INCLUDE them (helps train multi-organism detection)
    but you may want to filter IsGroupOf=0 for strict single-organism data.
  - IsDepiction=1 marks illustrations / paintings — we EXCLUDE them.
  - OI "Fish" includes freshwater/market fish photos. Using IS_TRUNCATED and
    CONFIDENCE=1 filtering helps but does not fully eliminate them.
  - The train CSV is large (1.2 GB). This script does a streaming partial read
    stopping after MAX_IMAGES_TRAIN unique marine image IDs are collected; the
    remaining CSV bytes are dropped without download. Time to collect 5000 IDs
    from the CSV: ~25 s at 100 Mbps (parses ~370 MB of the 1.2 GB CSV).
  - Individual image download requests are rate-limited to avoid AWS 503.
    Workers: 8 threads, 200 ms sleep between batches.

Dependencies: pip install requests tqdm
"""

from __future__ import annotations

import os
import sys
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Iterator

import requests
from tqdm import tqdm

SLUG = "openimages_marine"
DATASET_DIR = Path(__file__).parent / "datasets" / SLUG
IMAGES_DIR = DATASET_DIR / "images"
LABELS_DIR = DATASET_DIR / "labels"
DONE_SENTINEL = DATASET_DIR / ".done"

# OI annotation CSV URLs (GCS, no auth, support Range requests)
VAL_BBOX_CSV = (
    "https://storage.googleapis.com/openimages/2018_04/validation/"
    "validation-annotations-bbox.csv"
)
TRAIN_BBOX_CSV = (
    "https://storage.googleapis.com/openimages/2018_04/train/"
    "train-annotations-bbox.csv"
)
# GCS train CSV is 1.2 GB; we stream until we have enough IDs.
TRAIN_CSV_SIZE_BYTES = 1_194_033_454

# S3 CVDF image URL patterns (no auth, HTTP 200 confirmed)
IMG_URL_TRAIN = "https://open-images-dataset.s3.amazonaws.com/train/{image_id}.jpg"
IMG_URL_VAL = "https://open-images-dataset.s3.amazonaws.com/validation/{image_id}.jpg"

# Marine OI class IDs to download
MARINE_OI_IDS: set[str] = {
    "/m/0ch_cf",   # Fish
    "/m/09g7lh",   # Coral reef fish
    "/m/076t48l",  # Coral reef fish (alt id)
    "/m/0by6g",    # Shark
    "/m/01hxbg",   # Tiger shark
    "/m/0by7c",    # Great white shark
    "/m/0120dh",   # Sea turtle
    "/m/06qhl2",   # Green sea turtle
    "/m/03jhmk",   # Hawksbill sea turtle
    "/m/064_lq",   # Loggerhead sea turtle
    "/m/05py0",    # Octopus
    "/m/02q2rdb",  # Common octopus
    "/m/0d8zb",    # Jellyfish
    "/m/0nybt",    # Seahorse
    "/m/0df6p",    # Manta ray
    "/m/0f54r",    # Stingray
    "/m/025wwx9",  # Atlantic stingray
}

# Rows with IsDepiction=1 are illustrations → exclude them.
# Column indices (0-based) in the OI bbox CSV:
# ImageID(0), Source(1), LabelName(2), Confidence(3),
# XMin(4), XMax(5), YMin(6), YMax(7),
# IsOccluded(8), IsTruncated(9), IsGroupOf(10), IsDepiction(11), IsInside(12)
COL_IMAGE_ID = 0
COL_LABEL = 2
COL_CONF = 3
COL_XMIN = 4
COL_XMAX = 5
COL_YMIN = 6
COL_YMAX = 7
COL_IS_DEPICTION = 11

# Maximum images to download from train split (0 = unlimited).
MAX_IMAGES_TRAIN: int = int(os.environ.get("OI_MAX_TRAIN", "5000"))

# Parallel workers for image download
DOWNLOAD_WORKERS = 8
BATCH_SLEEP_S = 0.2  # polite pause between worker batches


# ---------------------------------------------------------------------------
# CSV streaming helpers
# ---------------------------------------------------------------------------

def _stream_csv_lines(url: str, chunk_bytes: int = 4 * 1024 * 1024) -> Iterator[str]:
    """Yield lines from a potentially large remote CSV via streaming GET."""
    resp = requests.get(url, stream=True, timeout=120)
    resp.raise_for_status()
    remainder = ""
    for raw_chunk in resp.iter_content(chunk_size=chunk_bytes):
        text = remainder + raw_chunk.decode("utf-8", errors="replace")
        lines = text.split("\n")
        remainder = lines[-1]
        for line in lines[:-1]:
            yield line
    if remainder:
        yield remainder


def _parse_oi_line(
    parts: list[str],
) -> tuple[str, float, float, float, float] | None:
    """
    Parse one OI bbox CSV row. Returns (image_id, cx, cy, w, h) or None to skip.
    Filters: label must be in MARINE_OI_IDS, IsDepiction must be 0.
    """
    if len(parts) < 12:
        return None
    if parts[COL_LABEL] not in MARINE_OI_IDS:
        return None
    if parts[COL_IS_DEPICTION] == "1":
        return None
    try:
        xmin, xmax = float(parts[COL_XMIN]), float(parts[COL_XMAX])
        ymin, ymax = float(parts[COL_YMIN]), float(parts[COL_YMAX])
    except ValueError:
        return None
    cx = (xmin + xmax) / 2.0
    cy = (ymin + ymax) / 2.0
    return parts[COL_IMAGE_ID], cx, cy, xmax - xmin, ymax - ymin


def _collect_marine_annotations(
    url: str,
    split: str,
    max_images: int = 0,
) -> dict[str, list[tuple[float, float, float, float]]]:
    """
    Stream a bbox CSV and collect marine-organism annotations.
    Returns {image_id: [(cx, cy, w, h), ...]} in YOLO normalised coords.
    Stops after max_images unique image IDs (0 = unlimited).
    """
    img_boxes: dict[str, list[tuple[float, float, float, float]]] = defaultdict(list)
    header_skipped = False
    csv_size = TRAIN_CSV_SIZE_BYTES if split == "train" else 0
    pbar = tqdm(desc=f"Scanning {split} CSV", unit="B", unit_scale=True, total=csv_size or None)

    for line in _stream_csv_lines(url):
        pbar.update(len(line.encode("utf-8", errors="replace")) + 1)
        if not header_skipped:
            header_skipped = True
            continue
        parsed = _parse_oi_line(line.split(","))
        if parsed is None:
            continue
        image_id, cx, cy, w, h = parsed
        if max_images > 0 and image_id not in img_boxes and len(img_boxes) >= max_images:
            break
        img_boxes[image_id].append((cx, cy, w, h))

    pbar.close()
    return dict(img_boxes)


# ---------------------------------------------------------------------------
# Image download helpers
# ---------------------------------------------------------------------------

def _download_image(
    image_id: str, split: str, session: requests.Session
) -> tuple[str, bytes | None]:
    """Download a single image; return (image_id, bytes) or (image_id, None) on error."""
    url = IMG_URL_TRAIN.format(image_id=image_id) if split == "train" else IMG_URL_VAL.format(image_id=image_id)
    try:
        resp = session.get(url, timeout=30)
        resp.raise_for_status()
        return image_id, resp.content
    except Exception:
        return image_id, None


def _fetch_images_parallel(
    pending: list[str],
    split: str,
    split_img_dir: Path,
    session: requests.Session,
) -> None:
    """Download images in parallel batches into split_img_dir."""
    batch_size = DOWNLOAD_WORKERS * 4
    with tqdm(total=len(pending), desc=f"  Downloading OI {split} images") as bar:
        for i in range(0, len(pending), batch_size):
            batch = pending[i : i + batch_size]
            with ThreadPoolExecutor(max_workers=DOWNLOAD_WORKERS) as pool:
                futures = {
                    pool.submit(_download_image, iid, split, session): iid
                    for iid in batch
                }
                for fut in as_completed(futures):
                    image_id, img_bytes = fut.result()
                    if img_bytes is not None:
                        (split_img_dir / f"{image_id}.jpg").write_bytes(img_bytes)
                    bar.update(1)
            time.sleep(BATCH_SLEEP_S)


def _write_labels(
    img_boxes: dict[str, list[tuple[float, float, float, float]]],
    split_lbl_dir: Path,
) -> int:
    """Write YOLO label files; returns total box count."""
    n_boxes = 0
    for image_id, boxes in img_boxes.items():
        out_lbl = split_lbl_dir / f"{image_id}.txt"
        if not out_lbl.exists():
            lines = [f"0 {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}" for cx, cy, w, h in boxes]
            out_lbl.write_text("\n".join(lines), encoding="utf-8")
        n_boxes += len(boxes)
    return n_boxes


def _download_and_write(
    img_boxes: dict[str, list[tuple[float, float, float, float]]],
    split: str,
) -> tuple[int, int]:
    split_img_dir = IMAGES_DIR / split
    split_lbl_dir = LABELS_DIR / split
    split_img_dir.mkdir(parents=True, exist_ok=True)
    split_lbl_dir.mkdir(parents=True, exist_ok=True)

    pending = [
        iid for iid in img_boxes
        if not (split_img_dir / f"{iid}.jpg").exists()
    ]
    session = requests.Session()
    session.headers.update({"User-Agent": "OceanGuard-v3-dataset-downloader/1.0"})
    _fetch_images_parallel(pending, split, split_img_dir, session)
    n_boxes = _write_labels(img_boxes, split_lbl_dir)
    return len(img_boxes), n_boxes


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> None:
    DATASET_DIR.mkdir(parents=True, exist_ok=True)

    if DONE_SENTINEL.exists():
        n_img = sum(1 for _ in IMAGES_DIR.rglob("*.jpg"))
        n_lbl = sum(1 for _ in LABELS_DIR.rglob("*.txt"))
        print(f"openimages_marine: {n_img} images, {n_lbl} labels  (already complete)")
        return

    (DATASET_DIR / "classes.txt").write_text("organism\n", encoding="utf-8")

    total_images = 0
    total_boxes = 0

    # --- Validation split (always full, ~448 marine images) ---
    print("\n[openimages_marine] Validation split (~448 images)...")
    try:
        val_boxes = _collect_marine_annotations(VAL_BBOX_CSV, "validation", max_images=0)
    except requests.RequestException as exc:
        print(f"ERROR streaming val CSV: {exc}")
        sys.exit(1)

    print(f"  Found {len(val_boxes)} unique marine val images.")
    n_img, n_box = _download_and_write(val_boxes, "validation")
    total_images += n_img
    total_boxes += n_box

    # --- Train split (capped by MAX_IMAGES_TRAIN) ---
    cap_str = str(MAX_IMAGES_TRAIN) if MAX_IMAGES_TRAIN > 0 else "unlimited"
    print(f"\n[openimages_marine] Train split (cap={cap_str})...")
    try:
        train_boxes = _collect_marine_annotations(TRAIN_BBOX_CSV, "train", max_images=MAX_IMAGES_TRAIN)
    except requests.RequestException as exc:
        print(f"ERROR streaming train CSV: {exc}")
        sys.exit(1)

    print(f"  Found {len(train_boxes)} unique marine train images.")
    n_img, n_box = _download_and_write(train_boxes, "train")
    total_images += n_img
    total_boxes += n_box

    DONE_SENTINEL.touch()
    print(f"\nopenimages_marine: {total_images} images, {total_boxes} organism boxes")
    print(f"  Env OI_MAX_TRAIN={MAX_IMAGES_TRAIN}  (set to 0 for all ~31K train images)")


if __name__ == "__main__":
    main()
