"""
dl_urpc_reef.py — URPC (Underwater Robot Professional Contest) reef-invertebrate dataset.

Source: HuggingFace Francesco/underwater-objects-5v7p8
  https://huggingface.co/datasets/Francesco/underwater-objects-5v7p8
  File: dataset.tar.gz (430 MB, 7 600 COCO-annotated images)

Domain fit: South China Sea coral reef floor, clear-to-murky water, wide FOV.
  Fills the "reef floor + invertebrates" gap entirely.  All organisms are benthic
  (sea urchin, sea cucumber, scallop, starfish) — cephalopod / fish images are NOT
  present, but the reef backgrounds are ideal negative-hard examples and the
  bounding-box quality is research-grade.

Classes in source (all collapsed to class 0: organism):
  echinus (sea urchin), holothurian (sea cucumber), scallop, starfish, waterweeds

Splits: train 5 320 | val 760 | test 1 520  — Total 7 600 images
BBox count: ~16 000 annotations (COCO JSON format)
License: CC BY 4.0  (confirmed in dataset_info.json: "license": "CC BY 4.0")
  Source page: https://universe.roboflow.com/object-detection/underwater-objects-5v7p8
Download: 430 MB  —  at 50 Mbps ~69 s
Disk after extraction: ~870 MB

URL verified alive 2026-05-29 (HTTP 200, Content-Length: 430334977).

Caveats:
  - No fish / turtles / cephalopods — pure reef-bottom invertebrate dataset.
  - "waterweeds" class (algae/seagrass bboxes) is included → collapsed to class 0.
  - Images are 640×640 (Roboflow-resized; original resolution unavailable).
  - South China Sea teal-green colour cast differs from Caribbean/Red Sea palette.

Dependencies: pip install requests tqdm
"""

from __future__ import annotations

import sys
from pathlib import Path

import requests
from tqdm import tqdm

from dl_coco_rf100_helpers import extract_rf100_tar

SLUG = "urpc_reef"
DATASET_DIR = Path(__file__).parent / "datasets" / SLUG
IMAGES_DIR = DATASET_DIR / "images"
LABELS_DIR = DATASET_DIR / "labels"

HF_URL = (
    "https://huggingface.co/datasets/Francesco/underwater-objects-5v7p8"
    "/resolve/main/dataset.tar.gz"
)
TAR_CACHE = DATASET_DIR / "dataset.tar.gz"
DONE_SENTINEL = DATASET_DIR / ".done"

# COCO category_ids to treat as organisms (exclude 0 = scene label "underwater-objects").
# 1: echinus  2: holothurian  3: scallop  4: starfish  5: waterweeds
ORGANISM_CATEGORY_IDS: set[int] = {1, 2, 3, 4, 5}


def _download_tar() -> None:
    print("Downloading URPC reef dataset (~430 MB)...")
    resp = requests.get(HF_URL, stream=True, timeout=120)
    resp.raise_for_status()
    total = int(resp.headers.get("content-length", 0))
    TAR_CACHE.parent.mkdir(parents=True, exist_ok=True)
    with open(TAR_CACHE, "wb") as fh, tqdm(
        total=total, unit="B", unit_scale=True, desc="urpc_reef.tar.gz", leave=False
    ) as bar:
        for chunk in resp.iter_content(chunk_size=131_072):
            fh.write(chunk)
            bar.update(len(chunk))


def main() -> None:
    DATASET_DIR.mkdir(parents=True, exist_ok=True)

    if DONE_SENTINEL.exists():
        n_img = sum(1 for _ in IMAGES_DIR.rglob("*.jpg"))
        n_lbl = sum(1 for _ in LABELS_DIR.rglob("*.txt"))
        print(f"urpc_reef: {n_img} images, {n_lbl} labels  (already complete)")
        return

    (DATASET_DIR / "classes.txt").write_text("organism\n", encoding="utf-8")

    if not TAR_CACHE.exists():
        try:
            _download_tar()
        except requests.RequestException as exc:
            print(f"ERROR downloading urpc_reef: {exc}")
            sys.exit(1)
    else:
        print("urpc_reef tar.gz already cached.")

    try:
        n_img, n_box = extract_rf100_tar(
            tar_path=TAR_CACHE,
            images_dir=IMAGES_DIR,
            labels_dir=LABELS_DIR,
            desc="urpc_reef",
            organism_ids=ORGANISM_CATEGORY_IDS,
        )
    except Exception as exc:
        print(f"ERROR extracting urpc_reef: {exc}")
        sys.exit(1)

    TAR_CACHE.unlink(missing_ok=True)
    DONE_SENTINEL.touch()
    print(f"urpc_reef: {n_img} images, {n_box} organism boxes")


if __name__ == "__main__":
    main()
