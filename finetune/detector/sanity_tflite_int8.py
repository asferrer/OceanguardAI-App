"""sanity_tflite_int8.py — Parity check: TFLite INT8 vs FP32 ORT on inat_000.jpg.

TFLite model notes:
  - Input: NHWC [1, 416, 416, 3] float32 (onnx2tf transposes NCHW->NHWC at export)
  - Output: [1, 3549, 5] = [x1, y1, x2, y2, score] for all 3549 raw anchors
    (NMS-free head; Kotlin does top-K filtering at runtime)
  - The original ONNX output0 [1, 300, 6] was post the top-300 sort + class concat
    which onnx2tf cannot handle due to dynamic ConstantOfShape->Tile pattern.
    Graph was truncated before that sort at the Transpose output [1,3549,5].

Quantization findings:
  - full_integer_quant (INT8 I/O): FAILS — per-tensor scale 2.35 from bbox coords
    crushes score column (0.0-0.53 range) to a single INT8 bucket -> topConf=0.0
  - dynamic_range_quant (INT8 weights, float32 I/O): WORKS -> topConf=0.345
  - float16 (FP16 weights, float32 I/O): BEST PARITY -> topConf=0.525

NNAPI note:
  dynamic_range_quant uses INT8 weights but float32 activations. Exynos 2200
  NNAPI vendor driver accepts this via XNNPACK fallback. Full INT8 (all INT8 ops)
  is blocked by the mixed bbox+score output tensor range incompatibility.

Usage (marine_tflite env):
    python sanity_tflite_int8.py [--tflite PATH]
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
ROOT = Path(__file__).resolve().parent
WEIGHTS_DIR = (
    ROOT
    / "runs"
    / "detect"
    / "output"
    / "runs"
    / "train"
    / "marine_yolo26n_v3"
    / "weights"
)
TRUNCATED_ONNX = ROOT / "build" / "_o2tf_int8" / "best_truncated.onnx"
SAMPLE_IMG = (
    ROOT.parent
    / "species"
    / "images"
    / "amphiprion_ocellaris"
    / "inat_000.jpg"
)

IMGSZ = 416
DEPLOY_THRESHOLD = 0.30


# ---------------------------------------------------------------------------
# Preprocessing
# ---------------------------------------------------------------------------

def preprocess_nchw(img_path: Path, imgsz: int = IMGSZ) -> np.ndarray:
    """[1, 3, H, W] float32 in [0, 1] — for ONNX/ORT."""
    img = Image.open(img_path).convert("RGB").resize((imgsz, imgsz))
    arr = np.array(img, dtype=np.float32) / 255.0
    arr = arr.transpose(2, 0, 1)
    return arr[np.newaxis]


def preprocess_nhwc(img_path: Path, imgsz: int = IMGSZ) -> np.ndarray:
    """[1, H, W, 3] float32 in [0, 1] — for TFLite (onnx2tf transposes NCHW->NHWC)."""
    img = Image.open(img_path).convert("RGB").resize((imgsz, imgsz))
    arr = np.array(img, dtype=np.float32) / 255.0
    return arr[np.newaxis]


# ---------------------------------------------------------------------------
# Inference helpers
# ---------------------------------------------------------------------------

def run_ort(onnx_path: Path, inp_nchw: np.ndarray) -> np.ndarray:
    """FP32 ORT inference. Returns [1, 3549, 5] from truncated ONNX."""
    import onnxruntime as ort
    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    name = sess.get_inputs()[0].name
    return sess.run(None, {name: inp_nchw})[0]


def run_tflite(tflite_path: Path, inp_nhwc: np.ndarray) -> np.ndarray:
    """TFLite inference. Handles float32, int8 I/O automatically."""
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        import tensorflow as tf
        Interpreter = tf.lite.Interpreter  # type: ignore[assignment]

    interp = Interpreter(model_path=str(tflite_path))
    interp.allocate_tensors()

    inp_detail = interp.get_input_details()[0]
    out_detail = interp.get_output_details()[0]

    print(f"  input  dtype={inp_detail['dtype'].__name__}  "
          f"shape={inp_detail['shape'].tolist()}  "
          f"quant={inp_detail['quantization']}")
    print(f"  output dtype={out_detail['dtype'].__name__}  "
          f"shape={out_detail['shape'].tolist()}  "
          f"quant={out_detail['quantization']}")

    # Cast/quantise input
    if inp_detail["dtype"] == np.int8:
        scale, zp = inp_detail["quantization"]
        inp_q = np.clip(np.round(inp_nhwc / scale + zp), -128, 127).astype(np.int8)
    else:
        inp_q = inp_nhwc.astype(inp_detail["dtype"])

    interp.set_tensor(inp_detail["index"], inp_q)
    interp.invoke()
    raw = interp.get_tensor(out_detail["index"])

    # Dequantise output if integer
    if out_detail["dtype"] == np.int8:
        scale, zp = out_detail["quantization"]
        raw = (raw.astype(np.float32) - zp) * scale
    elif out_detail["dtype"] == np.int16:
        scale, zp = out_detail["quantization"]
        raw = (raw.astype(np.float32) - zp) * scale

    return raw.astype(np.float32)


def top_conf(output: np.ndarray) -> float:
    """Max score from [1, 3549, 5] tensor (col 4 = score)."""
    return float(output[0, :, 4].max())


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tflite", type=Path,
                        default=WEIGHTS_DIR / "best_int8.tflite",
                        help="Path to TFLite model to test")
    args = parser.parse_args()

    tflite_path = args.tflite

    for path, label in [(tflite_path, "TFLite"), (SAMPLE_IMG, "sample")]:
        if not path.exists():
            sys.exit(f"ERROR: {label} not found: {path}")

    print(f"Sample image  : {SAMPLE_IMG}")
    print(f"TFLite        : {tflite_path}  ({tflite_path.stat().st_size / 1e6:.2f} MB)")

    ort_onnx = TRUNCATED_ONNX
    if not ort_onnx.exists():
        sys.exit(f"ERROR: truncated ONNX not found: {ort_onnx}")
    print(f"ORT ONNX      : {ort_onnx}  ({ort_onnx.stat().st_size / 1e6:.2f} MB)")
    print()

    inp_nchw = preprocess_nchw(SAMPLE_IMG)
    inp_nhwc = preprocess_nhwc(SAMPLE_IMG)

    # FP32 ORT
    print("[ORT FP32 - truncated ONNX]")
    ort_out = run_ort(ort_onnx, inp_nchw)
    fp32_conf = top_conf(ort_out)
    print(f"  topConf = {fp32_conf:.4f}")
    print()

    # TFLite
    print(f"[TFLite: {tflite_path.name}]")
    tfl_out = run_tflite(tflite_path, inp_nhwc)
    tfl_conf = top_conf(tfl_out)
    print(f"  topConf = {tfl_conf:.4f}")
    print()

    drop = fp32_conf - tfl_conf
    verdict = "DEPLOYABLE" if tfl_conf >= DEPLOY_THRESHOLD else "DEGRADED"

    print("=" * 55)
    print(f"FP32 ORT topConf   : {fp32_conf:.4f}")
    print(f"TFLite topConf     : {tfl_conf:.4f}")
    print(f"Drop               : {drop:+.4f}")
    print(f"Verdict            : {verdict}  (threshold >= {DEPLOY_THRESHOLD})")
    print("=" * 55)
    print()
    print("NOTE: TFLite output is [1,3549,5] raw anchors (no top-K sort).")
    print("      Original ONNX top-300 sort must be implemented in Kotlin.")
    print("      Quantization caveat: full INT8 (all-integer) collapses")
    print("      score column due to shared tensor scale with bbox coords.")
    print("      Use dynamic_range_quant (INT8 weights + float32 I/O) for")
    print("      NNAPI, or float16 for GPU delegate on Android.")


if __name__ == "__main__":
    main()
