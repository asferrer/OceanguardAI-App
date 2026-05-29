"""
run_v3_downloads.py — Orchestrates the v3 additional dataset downloads.

Downloads three new datasets that fill the amateur underwater photography domain gap
for the v3 YOLO26-N marine-organism detector (target: 25–30 K combined training images).

New datasets (this script):
  1. urpc_reef         — URPC reef invertebrates (CC-BY 4.0, 7 600 imgs, 430 MB)
  2. peixos            — Mediterranean reef fish (CC-BY 4.0, 1 200 imgs, 125 MB)
  3. openimages_marine — Open Images marine subset (CC-BY 2.0, ~5 448 imgs, ~2.8 GB)

Estimated total download: ~3.4 GB  at 50 Mbps → ~9 minutes.
Estimated total images:   ~14 248 new images (on top of existing v2 mix).

Environment variables:
  OI_MAX_TRAIN   (default 5000) — Cap on OI train images. Set to 0 for all ~31 K.
                                   Each extra 1K images adds ~0.5 GB and ~2 min.

Usage:
  python run_v3_downloads.py
  python run_v3_downloads.py --skip-openimages          # fast local test (535 MB only)
  OI_MAX_TRAIN=10000 python run_v3_downloads.py         # larger OI subset

Flags:
  --skip-urpc          Skip URPC reef dataset
  --skip-peixos        Skip Peixos fish dataset
  --skip-openimages    Skip Open Images marine subset

Output layout:
  datasets/urpc_reef/images/{train,valid,test}/*.jpg
  datasets/urpc_reef/labels/{train,valid,test}/*.txt
  datasets/peixos/images/{train,valid,test}/*.jpg
  datasets/peixos/labels/{train,valid,test}/*.txt
  datasets/openimages_marine/images/{train,validation}/*.jpg
  datasets/openimages_marine/labels/{train,validation}/*.txt

All label files use class 0 (organism) — class-agnostic, consistent with v2 mix.

Dependencies: pip install requests tqdm
"""

from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

SCRIPTS: list[tuple[str, str]] = [
    ("urpc_reef",         "dl_urpc_reef"),
    ("peixos",            "dl_peixos"),
    ("openimages_marine", "dl_openimages_marine"),
]

SKIP_FLAGS: dict[str, str] = {
    "urpc_reef":         "--skip-urpc",
    "peixos":            "--skip-peixos",
    "openimages_marine": "--skip-openimages",
}

EXPECTED_IMAGES: dict[str, int] = {
    "urpc_reef":         7_600,
    "peixos":            1_200,
    "openimages_marine": 5_448,   # default cap; varies with OI_MAX_TRAIN
}

EXPECTED_SIZES: dict[str, str] = {
    "urpc_reef":         "430 MB",
    "peixos":            "125 MB",
    "openimages_marine": "~2.8 GB (capped at OI_MAX_TRAIN=5000)",
}


def _count_dataset(slug: str) -> tuple[int, int]:
    """Return (n_images, n_labels) for an already-downloaded dataset."""
    base = Path(__file__).parent / "datasets" / slug
    n_img = sum(1 for _ in base.rglob("*.jpg")) if base.exists() else 0
    n_lbl = sum(1 for _ in base.rglob("*.txt") if _.stat().st_size > 0) if base.exists() else 0
    return n_img, n_lbl


def main() -> None:
    skipped = {slug for slug, flag in SKIP_FLAGS.items() if flag in sys.argv}
    scripts_dir = Path(__file__).parent

    results: list[tuple[str, str]] = []

    print("=" * 70)
    print("  OceanGuard v3 Dataset Downloads")
    print("  Target: fill amateur underwater photography domain gap")
    print("=" * 70)

    for slug, module_name in SCRIPTS:
        if slug in skipped:
            results.append((slug, "SKIPPED (flag)"))
            continue

        print(f"\n{'=' * 70}")
        print(f"  {slug.upper()}")
        print(f"  Expected: ~{EXPECTED_IMAGES[slug]:,} images, {EXPECTED_SIZES[slug]}")
        print(f"{'=' * 70}")

        script_path = scripts_dir / f"{module_name}.py"
        if not script_path.exists():
            results.append((slug, f"MISSING script {script_path.name}"))
            continue

        t0 = time.monotonic()
        result = subprocess.run(
            [sys.executable, str(script_path)],
            capture_output=False,
            env={**__import__("os").environ},  # pass through OI_MAX_TRAIN etc.
        )
        elapsed = time.monotonic() - t0

        if result.returncode == 0:
            n_img, n_lbl = _count_dataset(slug)
            results.append((slug, f"OK  {n_img:>6,} imgs  {n_lbl:>6,} labels  ({elapsed:.0f}s)"))
        else:
            results.append((slug, f"FAILED (exit {result.returncode}, {elapsed:.0f}s)"))

    print(f"\n{'=' * 70}")
    print("  FINAL SUMMARY")
    print(f"{'=' * 70}")
    for slug, status in results:
        print(f"  {slug:<22}  {status}")

    total_new = sum(
        _count_dataset(slug)[0]
        for slug, status in results
        if status.startswith("OK")
    )
    print(f"\n  New images downloaded: {total_new:,}")
    print(f"  Add to existing v2 mix (~11 000) → v3 train: ~{total_new + 11_000:,} images")
    print()

    failures = [s for s, st in results if "FAILED" in st]
    if failures:
        print(f"WARNING: {len(failures)} dataset(s) failed: {', '.join(failures)}")
        sys.exit(1)


if __name__ == "__main__":
    main()
