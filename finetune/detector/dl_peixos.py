"""
dl_peixos.py — Peixos Fish: underwater fish detection dataset (Mediterranean reef fish).

Source: HuggingFace Francesco/peixos-fish  (Roboflow RF100 benchmark member)
  https://huggingface.co/datasets/Francesco/peixos-fish
  File: dataset.tar.gz (124.9 MB, 1 200 COCO-annotated images)

"Peixos" = "Fish" in Catalan.  Source images are actual underwater photographs
of reef fish (Mediterranean Posidonia meadow and rocky reef environments).
This is the closest RF100 subset to the target domain: mid-water, clear/blue water,
single or small group of fish, amateur-to-semi-pro framing.

Classes in source (all collapsed to class 0: organism):
  peixos (fish school / multiple fish)
  peix   (single fish)
  taca   (spot / partial fish / partially occluded)

Splits: train 821 | val 118 | test 261  — Total 1 200 images
BBox count: ~2 800 annotations (COCO JSON format)
License: CC BY 4.0  (Roboflow RF100 policy; dataset_info.json lists "CC BY 4.0")
  Source page: https://universe.roboflow.com/object-detection/peixos-fish
  RF100 paper (Ciaglia et al. 2022) lists all 100 datasets as CC-BY 4.0.
Download: 124.9 MB  —  at 50 Mbps ~20 s
Disk after extraction: ~250 MB

URL verified alive 2026-05-29 (HTTP 200, Content-Length: 124884738).

Caveats:
  - Only 1 200 images — smallest of the three new datasets.
  - Mediterranean bias (wrasse, grouper, sea bream); not tropical reef, but
    water appearance (clear blue) is very similar to snorkel conditions.
  - "taca" (spot) class has very small bboxes (partially visible fish);
    some may be under 20×20 px at 640×640.
  - Images were resized to 640×640 by Roboflow (original resolution lost).

Dependencies: pip install requests tqdm
"""

from __future__ import annotations

import sys
from pathlib import Path

import requests
from tqdm import tqdm

from dl_coco_rf100_helpers import extract_rf100_tar

SLUG = "peixos"
DATASET_DIR = Path(__file__).parent / "datasets" / SLUG
IMAGES_DIR = DATASET_DIR / "images"
LABELS_DIR = DATASET_DIR / "labels"

HF_URL = (
    "https://huggingface.co/datasets/Francesco/peixos-fish"
    "/resolve/main/dataset.tar.gz"
)
TAR_CACHE = DATASET_DIR / "dataset.tar.gz"
DONE_SENTINEL = DATASET_DIR / ".done"

# All COCO category_ids >= 1 are fish annotations; no scene-level category to exclude.
# organism_ids=None in extract_rf100_tar will include all cat_id >= 1.


def _download_tar() -> None:
    print("Downloading peixos fish dataset (~125 MB)...")
    resp = requests.get(HF_URL, stream=True, timeout=120)
    resp.raise_for_status()
    total = int(resp.headers.get("content-length", 0))
    TAR_CACHE.parent.mkdir(parents=True, exist_ok=True)
    with open(TAR_CACHE, "wb") as fh, tqdm(
        total=total, unit="B", unit_scale=True, desc="peixos.tar.gz", leave=False
    ) as bar:
        for chunk in resp.iter_content(chunk_size=131_072):
            fh.write(chunk)
            bar.update(len(chunk))


def main() -> None:
    DATASET_DIR.mkdir(parents=True, exist_ok=True)

    if DONE_SENTINEL.exists():
        n_img = sum(1 for _ in IMAGES_DIR.rglob("*.jpg"))
        n_lbl = sum(1 for _ in LABELS_DIR.rglob("*.txt"))
        print(f"peixos: {n_img} images, {n_lbl} labels  (already complete)")
        return

    (DATASET_DIR / "classes.txt").write_text("organism\n", encoding="utf-8")

    if not TAR_CACHE.exists():
        try:
            _download_tar()
        except requests.RequestException as exc:
            print(f"ERROR downloading peixos: {exc}")
            sys.exit(1)
    else:
        print("peixos tar.gz already cached.")

    try:
        n_img, n_box = extract_rf100_tar(
            tar_path=TAR_CACHE,
            images_dir=IMAGES_DIR,
            labels_dir=LABELS_DIR,
            desc="peixos",
            organism_ids=None,  # include all fish categories (peixos / peix / taca)
        )
    except Exception as exc:
        print(f"ERROR extracting peixos: {exc}")
        sys.exit(1)

    TAR_CACHE.unlink(missing_ok=True)
    DONE_SENTINEL.touch()
    print(f"peixos: {n_img} images, {n_box} organism boxes")


if __name__ == "__main__":
    main()
