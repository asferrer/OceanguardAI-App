"""
quantize_fp16.py — Cuantiza el encoder ONNX a float16 (pesos), I/O en float32.

Reduce ~2x el tamaño del modelo (ViT-B/32: 352 MB → ~176 MB) para una descarga
más ligera in-app. `keep_io_types=True` mantiene los tensores de entrada/salida
en float32, así que OnnxSpeciesEmbedder.kt (que pasa un FloatBuffer fp32 y lee
fp32) NO necesita cambios. NNAPI/ORT Mobile soporta fp16 con casts internos.

Valida que el embedding fp16 conserva ~coseno 1.0 vs el fp32 original.

LIMITACIÓN CONOCIDA (ViT-B/32, 2026-05-27): el modelo fp16 resultante NO carga
bajo las optimizaciones por defecto de ONNX Runtime — `SimplifiedLayerNormFusion`
choca con los Cast que inserta la conversión fp16 (`GetIndexFromName ... does not
exist`), y bloquear las ops de LayerNorm vía op_block_list no lo resuelve. Como
ORT Mobile aplica las mismas fusiones en el device, fp16 rompería allí también.
→ Por ahora se despliega el encoder **fp32** (352 MB). Resolver fp16 requiere
deshabilitar esa fusión (graph_optimization_level) o tooling de fusión compatible.

Uso:
    python quantize_fp16.py --in output/clip_vitb32.onnx --out output/clip_vitb32_fp16.onnx
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnxruntime.transformers.float16 import convert_float_to_float16


def _cosine(a: np.ndarray, b: np.ndarray) -> float:
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))


def main() -> None:
    p = argparse.ArgumentParser(description="Cuantiza encoder ONNX a fp16 (I/O fp32).")
    p.add_argument("--in", dest="src", type=Path, required=True)
    p.add_argument("--out", dest="dst", type=Path, required=True)
    args = p.parse_args()

    model = onnx.load(str(args.src))
    model16 = convert_float_to_float16(model, keep_io_types=True)
    onnx.save(model16, str(args.dst))

    src_mb = os.path.getsize(args.src) / 1e6
    dst_mb = os.path.getsize(args.dst) / 1e6
    print(f"fp32: {src_mb:.1f} MB  ->  fp16: {dst_mb:.1f} MB  ({dst_mb/src_mb:.0%})")

    # Validación: mismo input -> coseno fp16 vs fp32
    rng = np.random.default_rng(0)
    x = rng.standard_normal((1, 3, 224, 224)).astype(np.float32)
    y32 = ort.InferenceSession(str(args.src), providers=["CPUExecutionProvider"]).run(
        ["image_features"], {"pixel_values": x})[0][0]
    y16 = ort.InferenceSession(str(args.dst), providers=["CPUExecutionProvider"]).run(
        ["image_features"], {"pixel_values": x})[0][0]
    cos = _cosine(y32, y16)
    print(f"coseno fp16 vs fp32: {cos:.5f}")
    assert cos > 0.999, f"fp16 diverge demasiado del fp32 (coseno={cos})"
    print("OK — fp16 conserva la similitud.")


if __name__ == "__main__":
    main()
