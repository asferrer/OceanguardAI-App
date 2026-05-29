"""export_tflite_int8_v3.py — ONNX FP32 -> TFLite for Android deployment.

Working conda env recipe (marine_tflite):
    conda create -y -n marine_tflite python=3.11
    # then using marine_tflite Python:
    pip install tensorflow==2.21.0 tf_keras==2.21.0 \
        onnx==1.21.0 onnx2tf==1.28.8 onnxruntime==1.26.0 \
        onnxsim==0.6.3 onnx-graphsurgeon==0.6.1 \
        sng4onnx sne4onnx sam4onnx snc4onnx soc4onnx sod4onnx sor4onnx \
        ai-edge-litert==2.1.5 psutil numpy==1.26.4 Pillow

Packages installed (verified working set):
    tensorflow        2.21.0
    tf_keras          2.21.0
    onnx              1.21.0
    onnx2tf           1.28.8    (+ companion sng4onnx,sne4onnx,etc.)
    onnxruntime       1.26.0
    onnxsim           0.6.3
    onnx_graphsurgeon 0.6.1
    ai-edge-litert    2.1.5
    numpy             1.26.4    (NOT 2.x: onnx2tf's bundled npy uses pickle)
    Pillow            12.2.0

DESIGN NOTES
============

1. YOLO26 Tile op blocker:
   The YOLO26-N NMS-free head uses ConstantOfShape->Tile for dynamic index
   expansion in its top-K sort. onnx2tf cannot map this to TFLite ops.
   Fix: truncate ONNX before the TopK sort, output raw [1,3549,5] anchors.
   Kotlin/Java must implement top-K filtering at runtime (score threshold +
   argsort on col 4, take top-300, concat class_id=0).

2. Full INT8 calibration failure (score collapse):
   The raw output tensor [1,3549,5] contains both bbox coords (range 0..547)
   and confidence scores (range 0..1) in a single tensor. Per-tensor INT8
   calibration assigns scale=2.35 to cover the coord range, which gives
   ~0.22 / 2.35 = 0.09 < 1 step for the score column -> all scores quantize
   to zero point. Even splitting the output tensor doesn't help because
   onnx2tf's calibration session propagates the same scale from the graph.

3. Deployable candidates:
   - best_int8.tflite (dynamic_range_quant): INT8 weights, float32 I/O.
     2.65 MB. topConf=0.345 vs FP32 0.527. NNAPI accepts via XNNPACK.
     DEPLOYABLE (topConf >= 0.30 threshold).
   - best_fp16.tflite: FP16 weights, float32 I/O.
     4.91 MB. topConf=0.525 (essentially lossless). GPU delegate on Android.

4. Windows/onnx2tf workarounds applied:
   (a) onnx2tf downloads a calibration sample npy from GitHub that fails with
       _pickle.UnpicklingError when download produces HTML error page. Fix:
       pre-create the expected file (calibration_image_sample_data_20x128x128x3
       _float32.npy) in cwd before running onnx2tf.
   (b) numpy 2.x makes allow_pickle=False default; onnx2tf 1.28.8 uses
       np.load without it for its bundled data. Fix: numpy==1.26.4.
   (c) -cind mean/std and -qnm/-qns are separate parameters. For a model that
       normalises input as x/255.0, pass calib data in [0,1] with
       -cind ... [[[[0.0]]]] [[[[1.0]]]] -qnm [[[[0.0]]]] -qns [[[[1.0]]]].

Pipeline:
  1. (optional) onnxsim
  2. Truncate ONNX at Transpose output [1,3549,5] (removes TopK/Tile postproc)
  3. Build calibration .npy: NHWC [300,416,416,3] float32 in [0,1]
  4. Pre-create synthetic test npy (bypasses GitHub download)
  5. onnx2tf with -oiqt + -qnm/qns = 0/1 + correct calib data
  6. Copy best_truncated_dynamic_range_quant.tflite -> best_int8.tflite
  7. Copy best_truncated_float16.tflite -> best_fp16.tflite
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import time
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
ONNX_SRC = WEIGHTS_DIR / "best.onnx"
CALIB_LIST = ROOT / "splits" / "calibration.txt"
BUILD_DIR = ROOT / "build" / "_o2tf_int8"
BUILD_DIR.mkdir(parents=True, exist_ok=True)

IMGSZ = 416
PYTHON = sys.executable


# ---------------------------------------------------------------------------
# Step 1: onnxsim
# ---------------------------------------------------------------------------

def simplify_onnx(src: Path) -> Path:
    out = BUILD_DIR / "best_sim.onnx"
    if out.exists():
        print(f"[skip] onnxsim output exists: {out}")
        return out
    print(f"[onnxsim] {src} -> {out}")
    r = subprocess.run([PYTHON, "-m", "onnxsim", str(src), str(out)])
    if r.returncode != 0 or not out.exists():
        print("[warn] onnxsim failed, using original ONNX")
        return src
    return out


# ---------------------------------------------------------------------------
# Step 2: Truncate ONNX (remove ConstantOfShape->Tile post-processing)
# ---------------------------------------------------------------------------

def truncate_onnx(src: Path) -> Path:
    """Cut graph at Transpose output [1,3549,5], removing YOLO TopK postproc."""
    import onnx
    from onnx import helper, TensorProto

    out_path = BUILD_DIR / "best_truncated.onnx"
    if out_path.exists():
        print(f"[skip] truncated ONNX exists: {out_path}")
        return out_path

    print(f"[truncate] {src} -> {out_path}")
    m = onnx.load(str(src))
    cut_name = "/model.23/Transpose_output_0"

    # Names of all nodes that belong to TopK post-processing after the cut
    postproc_outputs = {
        "/model.23/Split_output_0", "/model.23/Split_output_1",
        "/model.23/ReduceMax_output_0",
        "/model.23/TopK_output_0", "/model.23/TopK_output_1",
        "/model.23/Expand_output_0", "/model.23/Flatten_1_output_0",
        "/model.23/Tile_output_0", "/model.23/GatherElements_output_0",
        "/model.23/Flatten_output_0", "/model.23/TopK_1_output_0",
        "/model.23/Add_2_output_0", "/model.23/Unsqueeze_1_output_0",
        "/model.23/Mod_output_0", "/model.23/Expand_1_output_0",
        "/model.23/Unsqueeze_2_output_0", "/model.23/Tile_1_output_0",
        "/model.23/Cast_2_output_0", "/model.23/GatherElements_1_output_0",
        "output0",
    }

    new_nodes = []
    for n in m.graph.node:
        if cut_name in n.output:
            new_nodes.append(n)
            continue
        if any(inp in postproc_outputs or inp == cut_name for inp in n.input):
            continue
        if any(out in postproc_outputs for out in n.output):
            continue
        new_nodes.append(n)

    new_output = helper.make_tensor_value_info(cut_name, TensorProto.FLOAT, [1, 3549, 5])
    new_graph = helper.make_graph(
        new_nodes, "yolo26n_truncated",
        list(m.graph.input), [new_output], list(m.graph.initializer),
    )
    new_model = helper.make_model(new_graph, opset_imports=m.opset_import)
    new_model.ir_version = m.ir_version
    import onnx
    onnx.checker.check_model(new_model)
    onnx.save(new_model, str(out_path))
    print(f"  Truncated: {out_path.stat().st_size / 1e6:.2f} MB  "
          f"({len(new_nodes)} nodes vs {len(m.graph.node)} original)")
    return out_path


# ---------------------------------------------------------------------------
# Step 3: Calibration .npy (NHWC, [0,1])
# ---------------------------------------------------------------------------

def build_calib_npy(calib_list: Path, imgsz: int) -> Path:
    """Materialise calibration images as NHWC float32 in [0,1]."""
    npy_path = BUILD_DIR / "calib_images_01.npy"
    if npy_path.exists():
        arr = np.load(npy_path)
        print(f"[skip] calib .npy exists: shape={arr.shape}")
        return npy_path

    lines = [
        l.strip()
        for l in calib_list.read_text(encoding="utf-8").splitlines()
        if l.strip()
    ]
    print(f"[calib] Building {len(lines)}-image NHWC npy -> {npy_path}")
    frames = []
    for p in lines:
        img = Image.open(p).convert("RGB").resize((imgsz, imgsz))
        arr = np.array(img, dtype=np.float32) / 255.0  # NHWC [H,W,3] [0,1]
        frames.append(arr)
    data = np.stack(frames, axis=0)
    print(f"  shape={data.shape} min={data.min():.3f} max={data.max():.3f}")
    np.save(npy_path, data)
    return npy_path


# ---------------------------------------------------------------------------
# Step 4: Synthetic test npy (bypasses onnx2tf GitHub download)
# ---------------------------------------------------------------------------

def ensure_synthetic_test_npy() -> None:
    """onnx2tf downloads calibration_image_sample_data_20x128x128x3_float32.npy
    from GitHub at startup. On Windows this often fails (timeout -> HTML page)
    causing _pickle.UnpicklingError. We pre-create the file in cwd."""
    fname = "calibration_image_sample_data_20x128x128x3_float32.npy"
    fpath = ROOT / fname
    if fpath.exists():
        try:
            np.load(fpath)
            return
        except Exception:
            fpath.unlink()
    data = np.random.rand(20, 128, 128, 3).astype(np.float32)
    np.save(fpath, data)
    print(f"[workaround] Created synthetic test npy: {fpath.name}")


# ---------------------------------------------------------------------------
# Step 5: onnx2tf conversion
# ---------------------------------------------------------------------------

def run_onnx2tf(onnx_path: Path, calib_npy: Path) -> None:
    out_dir = BUILD_DIR
    cmd = [
        PYTHON, "-m", "onnx2tf",
        "-i", str(onnx_path),
        "-o", str(out_dir),
        "-oiqt",
        "-qt", "per-channel",
        "-cind", "images", str(calib_npy), "[[[[0.0,0.0,0.0]]]]", "[[[[1.0,1.0,1.0]]]]",
        "-qnm", "[[[[0.0,0.0,0.0]]]]",
        "-qns", "[[[[1.0,1.0,1.0]]]]",
    ]
    print(f"\n[onnx2tf]\n  cwd={ROOT}\n  cmd (abbreviated): onnx2tf -i {onnx_path.name} "
          f"-oiqt -qt per-channel -qnm 0 -qns 1", flush=True)
    t0 = time.time()
    r = subprocess.run(cmd, cwd=str(ROOT))
    print(f"  Elapsed: {time.time() - t0:.1f}s  exit={r.returncode}")
    if r.returncode != 0:
        raise SystemExit("onnx2tf failed")


# ---------------------------------------------------------------------------
# Step 6 & 7: Copy outputs
# ---------------------------------------------------------------------------

def copy_outputs() -> dict[str, str]:
    results: dict[str, str] = {}

    dyn = next(BUILD_DIR.rglob("*_dynamic_range_quant.tflite"), None)
    if dyn:
        dst = WEIGHTS_DIR / "best_int8.tflite"
        shutil.copy(dyn, dst)
        results["dynamic_range (NNAPI)"] = f"{dst.stat().st_size / 1e6:.2f} MB"
        print(f"[copy] best_int8.tflite (dynamic_range) -> {dst}")

    fp16 = next(BUILD_DIR.rglob("*_float16.tflite"), None)
    if fp16:
        dst16 = WEIGHTS_DIR / "best_fp16.tflite"
        shutil.copy(fp16, dst16)
        results["float16 (GPU delegate)"] = f"{dst16.stat().st_size / 1e6:.2f} MB"
        print(f"[copy] best_fp16.tflite -> {dst16}")

    full_int8 = next(BUILD_DIR.rglob("*_full_integer_quant.tflite"), None)
    if full_int8:
        dst_full = WEIGHTS_DIR / "best_full_int8.tflite"
        shutil.copy(full_int8, dst_full)
        results["full_int8 (blocked: score collapse)"] = f"{dst_full.stat().st_size / 1e6:.2f} MB"
        print(f"[copy] best_full_int8.tflite -> {dst_full}  (CAUTION: score collapse)")

    return results


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    if not ONNX_SRC.exists():
        sys.exit(f"ERROR: ONNX not found: {ONNX_SRC}")

    t0 = time.time()

    # Step 1
    onnx_path = simplify_onnx(ONNX_SRC)

    # Step 2
    truncated = truncate_onnx(onnx_path)

    # Step 3
    calib_npy = build_calib_npy(CALIB_LIST, IMGSZ)

    # Step 4
    ensure_synthetic_test_npy()

    # Step 5
    run_onnx2tf(truncated, calib_npy)

    # Steps 6 & 7
    results = copy_outputs()

    elapsed = time.time() - t0
    print(f"\n[done] {elapsed:.1f}s")
    print("Outputs:")
    for k, v in results.items():
        print(f"  {k}: {v}")
    print("\nRun sanity_tflite_int8.py to verify parity.")


if __name__ == "__main__":
    main()
