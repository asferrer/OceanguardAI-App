"""export_tflite_int8.py — best.pt → ONNX (opset 14) → TFLite INT8 calibrated.

Two-stage so we can inspect/repair the ONNX before the INT8 pass:
  1. Ultralytics YOLO export to ONNX (opset 14, dynamic_batch=False, nms=False).
     `nms=False` keeps the NNAPI graph clean — embedded NMS pushes ops to CPU
     fallback on Exynos vendor drivers. We run NMS in Kotlin via top-K.
  2. onnx2tf with INT8 PTQ using the 300-image calibration set we built.

Outputs:
  output/export/best.onnx              (FP32 ONNX intermediate)
  output/export/best_int8.tflite       (INT8 TFLite, deployable)
  output/export/best_float32.tflite    (FP32 TFLite, fallback / debug)
  output/export/metadata.json          (export args + ONNX I/O + sizes)

Verification (manual, post-run):
  - Open best_int8.tflite in netron → confirm all conv tensors are INT8
    (Ultralytics #18511: int8=True can silently emit FP32 sometimes).
  - Compare best_int8 vs best.pt outputs on 10 sample images → cosine ≥ 0.99.

Run AFTER training completes (best.pt exists).
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EXPORT_DIR = ROOT / "output" / "export"
EXPORT_DIR.mkdir(parents=True, exist_ok=True)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--weights", type=Path,
                   default=ROOT / "output" / "runs" / "train" /
                           "marine_yolo26n_v1" / "weights" / "best.pt")
    p.add_argument("--imgsz", type=int, default=416)
    p.add_argument("--calib-list", type=Path,
                   default=ROOT / "splits" / "calibration.txt")
    p.add_argument("--skip-int8", action="store_true",
                   help="Skip INT8 stage (export FP32 ONNX + FP32 TFLite only).")
    return p.parse_args()


def export_onnx(weights: Path, imgsz: int) -> Path:
    """Stage 1: ultralytics → ONNX. opset 14, no NMS embedded, batch=1."""
    from ultralytics import YOLO

    out = EXPORT_DIR / "best.onnx"
    if out.exists():
        out.unlink()
    model = YOLO(str(weights))
    # Ultralytics returns the exported file path.
    exported = model.export(
        format="onnx",
        imgsz=imgsz,
        opset=14,
        dynamic=False,
        simplify=True,
        nms=False,           # Kotlin runs top-K + score gate
    )
    # Ultralytics writes next to weights — move into export dir for tidiness.
    src = Path(exported) if isinstance(exported, str) else weights.with_suffix(".onnx")
    if src.exists() and src != out:
        shutil.move(str(src), out)
    print(f"ONNX: {out} ({out.stat().st_size / 1e6:.1f} MB)")
    return out


def calibration_image_dir(calib_list: Path) -> Path:
    """onnx2tf wants a directory of calibration images. We materialise a temp
    folder of symlinks (or copies on Windows where symlinks need elevation).

    Wait — onnx2tf actually accepts a `.npy` of preprocessed tensors OR a
    directory of raw images. We use directory mode and let onnx2tf preprocess.
    """
    out_dir = EXPORT_DIR / "_calib_imgs"
    out_dir.mkdir(parents=True, exist_ok=True)
    if any(out_dir.iterdir()):
        return out_dir
    paths = [Path(p) for p in calib_list.read_text(encoding="utf-8").splitlines() if p.strip()]
    for i, src in enumerate(paths):
        if not src.exists():
            continue
        dst = out_dir / f"{i:04d}{src.suffix}"
        try:
            dst.symlink_to(src)
        except (OSError, NotImplementedError):
            shutil.copy(src, dst)
    return out_dir


def export_tflite(onnx_path: Path, imgsz: int, calib_list: Path,
                  do_int8: bool) -> dict:
    """Stage 2: onnx2tf. FP32 + (optional) INT8 PTQ from calibration imgs.

    onnx2tf is installed via pip and exposes a CLI; we call it as a subprocess
    so its progress streams cleanly and any error message is visible.
    """
    fp32_tflite = EXPORT_DIR / "best_float32.tflite"
    int8_tflite = EXPORT_DIR / "best_int8.tflite"
    out = {"fp32": None, "int8": None}

    # FP32 first
    cmd_fp32 = [
        sys.executable, "-m", "onnx2tf",
        "-i", str(onnx_path),
        "-o", str(EXPORT_DIR / "_o2tf_fp32"),
        "-cotof",
        "-osd",
    ]
    t0 = time.time()
    print(f"\n[onnx2tf FP32]\n  $ {' '.join(cmd_fp32)}", flush=True)
    r = subprocess.run(cmd_fp32, capture_output=False)
    if r.returncode != 0:
        raise SystemExit(f"onnx2tf FP32 failed (exit {r.returncode})")
    # Locate the produced .tflite (onnx2tf names it after the input).
    cand = next((EXPORT_DIR / "_o2tf_fp32").rglob("*_float32.tflite"), None)
    if cand is not None:
        shutil.move(str(cand), fp32_tflite)
        out["fp32"] = str(fp32_tflite)
        print(f"  FP32 TFLite: {fp32_tflite} "
              f"({fp32_tflite.stat().st_size / 1e6:.1f} MB)  [{time.time() - t0:.1f}s]")

    if not do_int8:
        return out

    calib_dir = calibration_image_dir(calib_list)
    print(f"\n[onnx2tf INT8] calib_dir={calib_dir} ({sum(1 for _ in calib_dir.iterdir())} imgs)",
          flush=True)
    cmd_int8 = [
        sys.executable, "-m", "onnx2tf",
        "-i", str(onnx_path),
        "-o", str(EXPORT_DIR / "_o2tf_int8"),
        "-oiqt",                       # output INT8 quantised tensorflow lite
        "-qt", "per-channel",          # tighter than per-tensor
        "-cind", "images", str(calib_dir), "[[[[0.0,0.0,0.0]]]]", "[[[[255.0,255.0,255.0]]]]",
        "-osd",
    ]
    t1 = time.time()
    print(f"  $ {' '.join(cmd_int8)}", flush=True)
    r = subprocess.run(cmd_int8, capture_output=False)
    if r.returncode != 0:
        raise SystemExit(f"onnx2tf INT8 failed (exit {r.returncode})")
    cand = next((EXPORT_DIR / "_o2tf_int8").rglob("*_full_integer_quant.tflite"), None)
    if cand is not None:
        shutil.move(str(cand), int8_tflite)
        out["int8"] = str(int8_tflite)
        print(f"  INT8 TFLite: {int8_tflite} "
              f"({int8_tflite.stat().st_size / 1e6:.1f} MB)  [{time.time() - t1:.1f}s]")
    return out


def main() -> None:
    args = parse_args()
    if not args.weights.exists():
        print(f"ERROR: weights not found at {args.weights}", file=sys.stderr)
        print("Hint: run after `train_detector.py` completes.", file=sys.stderr)
        sys.exit(1)

    t0 = time.time()
    onnx_path = export_onnx(args.weights, args.imgsz)
    tflite_paths = export_tflite(
        onnx_path=onnx_path,
        imgsz=args.imgsz,
        calib_list=args.calib_list,
        do_int8=not args.skip_int8,
    )

    meta = {
        "elapsed_seconds": round(time.time() - t0, 1),
        "weights": str(args.weights),
        "imgsz": args.imgsz,
        "onnx": str(onnx_path),
        "onnx_mb": round(onnx_path.stat().st_size / 1e6, 1),
        "tflite": tflite_paths,
        "calibration_list": str(args.calib_list),
        "note": (
            "NNAPI EP on Exynos 2200 should accept INT8 graph end-to-end "
            "(no NMS embedded). Run NMS in Kotlin (top-K + score threshold)."
        ),
    }
    (EXPORT_DIR / "metadata.json").write_text(
        json.dumps(meta, indent=2), encoding="utf-8"
    )
    print(f"\nDONE in {meta['elapsed_seconds']}s. metadata.json written.")


if __name__ == "__main__":
    main()
