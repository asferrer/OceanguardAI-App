"""quantize_detector_static.py — Static INT8 PTQ of the detector via ORT.

Why static and not dynamic: NNAPI EP on Exynos rejects `ConvInteger` (the op
that `quantize_dynamic` emits), so the device run falls back to VLM. Static
PTQ instead emits `QLinearConv` everywhere, which NNAPI vendor drivers
implement natively. Cost: needs a calibration set (we already have one at
`splits/calibration.txt`).

Outputs:
  best_int8_static.onnx — deployable on Android via ORT + NNAPI EP.
"""
from __future__ import annotations

import argparse
import time
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent
DEFAULT_FP32 = (ROOT / "runs" / "detect" / "output" / "runs" / "train" /
                "marine_yolo26n_v3" / "weights" / "best.onnx")
DEFAULT_OUT  = DEFAULT_FP32.parent / "best_int8_static.onnx"
DEFAULT_CALIB = ROOT / "splits" / "calibration.txt"
DEFAULT_SANITY = (ROOT.parent / "species" / "images" /
                  "amphiprion_ocellaris" / "inat_000.jpg")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--in-onnx",   type=Path, default=DEFAULT_FP32)
    p.add_argument("--out-onnx",  type=Path, default=DEFAULT_OUT)
    p.add_argument("--calib-list", type=Path, default=DEFAULT_CALIB)
    p.add_argument("--sanity-img", type=Path, default=DEFAULT_SANITY)
    p.add_argument("--max-calib", type=int, default=100,
                   help="Cap calibration images (faster).")
    return p.parse_args()


def preprocess(img_path: Path, size: int = 416) -> np.ndarray:
    img = Image.open(img_path).convert("RGB").resize((size, size), Image.BILINEAR)
    arr = np.array(img).astype(np.float32) / 255.0
    return arr.transpose(2, 0, 1)[None]  # [1, 3, H, W]


class ImageCalibrator:
    """ORT CalibrationDataReader over a newline-delimited path list."""

    def __init__(self, paths: list[Path]) -> None:
        self._iter = iter(paths)

    def get_next(self) -> dict | None:
        for p in self._iter:
            if p.exists():
                return {"images": preprocess(p)}
        return None

    def rewind(self) -> None:
        pass


def quantize(in_onnx: Path, out_onnx: Path, calib_list: Path,
             max_calib: int) -> float:
    from onnxruntime.quantization import (
        quantize_static, QuantType, CalibrationDataReader, QuantFormat,
    )
    paths = [Path(p) for p in calib_list.read_text(encoding="utf-8").splitlines()
             if p.strip()][:max_calib]
    print(f"calibration set: {len(paths)} images", flush=True)

    class _Reader(CalibrationDataReader):
        def __init__(self, items: list[Path]) -> None:
            self._items = iter(items)

        def get_next(self) -> dict | None:
            for p in self._items:
                if not p.exists():
                    continue
                return {"images": preprocess(p)}
            return None

        def rewind(self) -> None:
            pass

    t0 = time.time()
    out_onnx.parent.mkdir(parents=True, exist_ok=True)
    quantize_static(
        model_input=str(in_onnx),
        model_output=str(out_onnx),
        calibration_data_reader=_Reader(paths),
        # QOperator emits QLinearConv (NNAPI-supported); QDQ injects Q/DQ
        # nodes that NNAPI also supports but lengthens the graph. QOperator
        # is the right call for our 1-class detector.
        quant_format=QuantFormat.QOperator,
        weight_type=QuantType.QInt8,
        activation_type=QuantType.QUInt8,
        per_channel=True,
    )
    return time.time() - t0


def sanity_compare(fp32: Path, int8: Path, img: Path) -> dict:
    import onnxruntime as ort
    arr = preprocess(img)
    sess32 = ort.InferenceSession(str(fp32), providers=["CPUExecutionProvider"])
    sess8 = ort.InferenceSession(str(int8), providers=["CPUExecutionProvider"])
    o32 = sess32.run(None, {"images": arr})[0][0]
    o8 = sess8.run(None, {"images": arr})[0][0]
    return {
        "fp32_top": float(o32[:, 4].max()),
        "int8_top": float(o8[:, 4].max()),
        "fp32_n025": int((o32[:, 4] > 0.25).sum()),
        "int8_n025": int((o8[:, 4] > 0.25).sum()),
    }


def main() -> None:
    args = parse_args()
    if not args.in_onnx.exists():
        raise SystemExit(f"missing input: {args.in_onnx}")

    elapsed = quantize(args.in_onnx, args.out_onnx, args.calib_list, args.max_calib)
    size_mb = args.out_onnx.stat().st_size / 1e6
    fp32_mb = args.in_onnx.stat().st_size / 1e6
    print(f"static INT8: {elapsed:.1f}s, {size_mb:.1f} MB (vs FP32 {fp32_mb:.1f} MB)")

    print("\nParity check on clownfish iNat:")
    r = sanity_compare(args.in_onnx, args.out_onnx, args.sanity_img)
    print(f"  FP32 top conf: {r['fp32_top']:.4f}  (>0.25: {r['fp32_n025']} boxes)")
    print(f"  INT8 top conf: {r['int8_top']:.4f}  (>0.25: {r['int8_n025']} boxes)")
    if r["int8_top"] >= 0.20:
        print("  -> deployable (NNAPI EP can run QLinearConv)")
    else:
        print("  -> WARN: INT8 too degraded, fall back to FP32 deploy.")


if __name__ == "__main__":
    main()
