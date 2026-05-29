"""quantize_detector_int8.py — Detector v3 ONNX → INT8 dynamic quantization.

ORT dynamic quantization (`quantize_dynamic`) only quantises weights → smaller
file + faster MatMul on int-friendly backends, without the calibration dance
that onnx2tf needs. The activations remain FP32 so the rest of the graph runs
unchanged. Sweet spot for a fast deploy without the TF dep conflict.

Verification: parity vs FP32 on the iNat clownfish that v3 finally detects at
0.52 conf. If INT8 drops below ~0.30 we keep FP32 and accept the larger model.
"""
from __future__ import annotations

import argparse
import os
import time
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--in-onnx", type=Path,
                   default=ROOT / "runs" / "detect" / "output" / "runs" /
                           "train" / "marine_yolo26n_v3" / "weights" / "best.onnx")
    p.add_argument("--out-onnx", type=Path,
                   default=ROOT / "runs" / "detect" / "output" / "runs" /
                           "train" / "marine_yolo26n_v3" / "weights" / "best_int8.onnx")
    p.add_argument("--sanity-img", type=Path,
                   default=ROOT.parent / "species" / "images" /
                           "amphiprion_ocellaris" / "inat_000.jpg")
    return p.parse_args()


def quantize(in_onnx: Path, out_onnx: Path) -> tuple[float, float]:
    from onnxruntime.quantization import quantize_dynamic, QuantType
    t0 = time.time()
    out_onnx.parent.mkdir(parents=True, exist_ok=True)
    quantize_dynamic(
        model_input=str(in_onnx),
        model_output=str(out_onnx),
        weight_type=QuantType.QInt8,
        per_channel=True,
    )
    return time.time() - t0, out_onnx.stat().st_size / 1e6


def sanity_compare(fp32: Path, int8: Path, img_path: Path) -> dict:
    """Confidence parity for the clownfish iNat sample on which v3 fp32 hits 0.52."""
    import onnxruntime as ort
    img = Image.open(img_path).convert("RGB").resize((416, 416), Image.BILINEAR)
    arr = (np.array(img).astype(np.float32) / 255.0).transpose(2, 0, 1)[None]

    sess_fp32 = ort.InferenceSession(str(fp32), providers=["CPUExecutionProvider"])
    sess_int8 = ort.InferenceSession(str(int8), providers=["CPUExecutionProvider"])

    out_fp32 = sess_fp32.run(None, {"images": arr})[0][0]
    out_int8 = sess_int8.run(None, {"images": arr})[0][0]

    return {
        "fp32_top_conf":  float(out_fp32[:, 4].max()),
        "int8_top_conf":  float(out_int8[:, 4].max()),
        "fp32_above_25":  int((out_fp32[:, 4] > 0.25).sum()),
        "int8_above_25":  int((out_int8[:, 4] > 0.25).sum()),
        "fp32_top_box":   out_fp32[out_fp32[:, 4].argmax()].tolist(),
        "int8_top_box":   out_int8[out_int8[:, 4].argmax()].tolist(),
    }


def main() -> None:
    args = parse_args()
    if not args.in_onnx.exists():
        raise SystemExit(f"missing input: {args.in_onnx}")

    elapsed, size_mb = quantize(args.in_onnx, args.out_onnx)
    print(f"INT8 quantization: {elapsed:.1f}s, output {size_mb:.1f} MB "
          f"(vs FP32 {args.in_onnx.stat().st_size / 1e6:.1f} MB)")

    print("\nParity check on clownfish iNat (the v2 OOD failure case):")
    r = sanity_compare(args.in_onnx, args.out_onnx, args.sanity_img)
    print(f"  FP32 top conf: {r['fp32_top_conf']:.4f}  (>0.25: {r['fp32_above_25']} boxes)")
    print(f"  INT8 top conf: {r['int8_top_conf']:.4f}  (>0.25: {r['int8_above_25']} boxes)")
    drop = r["fp32_top_conf"] - r["int8_top_conf"]
    print(f"  conf drop: {drop:.4f}")
    if r["int8_top_conf"] >= 0.30:
        print("  -> INT8 is deployable")
    else:
        print("  -> WARN: INT8 too degraded; ship FP32 instead.")


if __name__ == "__main__":
    main()
