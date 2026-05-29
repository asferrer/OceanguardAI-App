"""build_calibration_set.py — Stratified 300-image calibration set for INT8 PTQ.

Why this matters:
  - onnx2tf INT8 quantisation calibrates per-tensor scales from a representative
    sample of the deployment input distribution.
  - Using only one dataset's images for calibration biases the activation
    histograms toward that source → INT8 will degrade on the others.
  - Research warning (Ultralytics docs): the calibration images MUST be loaded
    in the SAME colour order as the runtime preprocess. Underwater datasets vary
    (some PIL = RGB, some cv2.imread = BGR). We standardise to RGB here and
    document it for the deploy preprocess.

Stratification (~300 total):
  Brackish: 180 imgs (60 train + 60 valid + 60 test if present)
  Aquarium:  60 imgs (20 train + 20 valid + 20 test)
  SUIM (negatives): 60 imgs (random subset)

Output:
  splits/calibration.txt — newline-delimited absolute paths.
  splits/calibration_meta.json — counts per source.

Run after build_merged_yaml.py and before the TFLite INT8 export.
"""
from __future__ import annotations

import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "datasets"
SPLITS = ROOT / "splits"
SPLITS.mkdir(parents=True, exist_ok=True)

SEED = 42
IMG_EXTS = {".jpg", ".jpeg", ".png"}

TARGETS = {
    "brackish": {"train": 60, "valid": 60, "test": 60},
    "aquarium": {"train": 20, "valid": 20, "test": 20},
    "suim":     {"_all_":  60},
    "deepfish": {"_all_":  60},  # included if present
}


def _sample_dir(root: Path, n: int, rng: random.Random) -> list[Path]:
    imgs = sorted(
        p for p in root.rglob("*")
        if p.is_file() and p.suffix.lower() in IMG_EXTS
    )
    if not imgs or n <= 0:
        return []
    if len(imgs) <= n:
        return imgs
    return rng.sample(imgs, n)


def main() -> None:
    rng = random.Random(SEED)
    picks: dict[str, list[str]] = {}
    total = 0

    # Brackish + Aquarium (per-split structure)
    for name in ("brackish", "aquarium"):
        per_split = TARGETS[name]
        all_imgs: list[Path] = []
        for split, n in per_split.items():
            d = DATA / name / "images" / split
            if not d.exists():
                continue
            all_imgs.extend(_sample_dir(d, n, rng))
        picks[name] = [p.as_posix() for p in all_imgs]
        total += len(all_imgs)

    # SUIM + DeepFish (flat structure)
    for name in ("suim", "deepfish"):
        n = TARGETS[name].get("_all_", 0)
        d = DATA / name / "images"
        if d.exists():
            imgs = _sample_dir(d, n, rng)
            picks[name] = [p.as_posix() for p in imgs]
            total += len(imgs)

    out_txt = SPLITS / "calibration.txt"
    lines: list[str] = []
    for name, paths in picks.items():
        lines.extend(paths)
    out_txt.write_text("\n".join(lines), encoding="utf-8")

    meta = {
        "total": total,
        "per_source": {k: len(v) for k, v in picks.items()},
        "rng_seed": SEED,
        "colour_order": "RGB",  # matters for onnx2tf calibration
        "note": (
            "These paths are absolute. Loader must produce RGB tensors (not BGR) "
            "matching the deployment preprocess. Resize to model input (416) with "
            "bilinear + letterbox per Ultralytics convention."
        ),
    }
    (SPLITS / "calibration_meta.json").write_text(
        json.dumps(meta, indent=2), encoding="utf-8"
    )
    print(f"{'source':<10}  {'imgs':>5}")
    print("-" * 18)
    for name, paths in picks.items():
        print(f"{name:<10}  {len(paths):>5}")
    print("-" * 18)
    print(f"{'TOTAL':<10}  {total:>5}")
    print(f"\nWrote {out_txt.name} + calibration_meta.json")


if __name__ == "__main__":
    main()
