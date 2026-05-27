"""
quantize_int8.py — Cuantización dinámica int8 (pesos) del encoder ONNX.

Reduce ~4x el tamaño de los pesos (ViT-B/32: ~352 MB fp32 → ~90 MB int8) para
una descarga más ligera y menor footprint en device. La cuantización dinámica
(`quantize_dynamic`) cuantiza SOLO los pesos a int8; las activaciones se
cuantizan al vuelo en cada inferencia (sin dataset de calibración). Para un ViT
de embeddings con I/O fp32 esto NO cambia el contrato de OnnxSpeciesEmbedder.kt
(input fp32, output fp32 L2-norm).

POR QUÉ int8 Y NO fp16
----------------------
El intento fp16 (ver quantize_fp16.py) FALLÓ al cargar: la fusión
`SimplifiedLayerNormFusion` de ONNX Runtime choca con los Cast que inserta la
conversión fp16. Como ORT Mobile aplica las MISMAS fusiones en device, fp16
rompería allí también. La cuantización dinámica int8 usa `MatMulInteger` /
`DynamicQuantizeLinear` en lugar de envolver LayerNorm en Casts, así que en
principio NO dispara esa fusión. Este script lo VERIFICA explícitamente:

  *** Carga el modelo int8 con graph_optimization_level = ORT_ENABLE_ALL ***
  *** (el nivel por defecto, el mismo que aplica ORT Mobile en device). ***

Si carga y la similitud coseno vs fp32 ≥ 0.99 sobre imágenes reales → int8 es
desplegable. Si falla o degrada → se documenta y se recomienda fp32 (igual que
con fp16).

USO
---
  python quantize_int8.py --in output/clip_vitb32.onnx --out output/clip_vitb32_int8.onnx
  # con imágenes reales para el chequeo de coseno (recomendado):
  python quantize_int8.py --in output/clip_vitb32.onnx --out output/clip_vitb32_int8.onnx \
      --images-dir images_train --n-images 32
"""

from __future__ import annotations

import argparse
import glob
import os
from pathlib import Path

import numpy as np
import onnxruntime as ort
from onnxruntime.quantization import quantize_dynamic, QuantType

CLIP_MEAN = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
CLIP_STD = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)
IMAGE_EXTS = ("*.jpg", "*.jpeg", "*.png", "*.webp")


def _load_session_default_opt(onnx_path: Path) -> ort.InferenceSession:
    """
    Crea la sesión con graph_optimization_level = ORT_ENABLE_ALL (el DEFAULT).
    Es el mismo nivel de fusiones que aplica ORT Mobile en device → reproduce
    exactamente el escenario donde fp16 falló al cargar.
    """
    opts = ort.SessionOptions()
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(
        str(onnx_path), sess_options=opts, providers=["CPUExecutionProvider"]
    )


def _real_inputs(images_dir: Path | None, n: int) -> np.ndarray:
    """
    Devuelve un batch (n,3,224,224) fp32 normalizado CLIP. Usa imágenes reales si
    images-dir existe; si no, ruido determinista (peor caso para coseno).
    """
    if images_dir and images_dir.exists():
        from PIL import Image
        paths: list[str] = []
        for pat in IMAGE_EXTS:
            paths.extend(glob.glob(str(images_dir / "*" / pat)))
        paths = sorted(paths)[:n]
        if paths:
            batch = [_preprocess(Image.open(p).convert("RGB")) for p in paths]
            return np.stack(batch).astype(np.float32)
    rng = np.random.default_rng(0)
    return rng.standard_normal((n, 3, 224, 224)).astype(np.float32)


def _preprocess(img) -> np.ndarray:
    """Resize 224 + center-crop + normalización CLIP → (3,224,224) fp32."""
    from PIL import Image
    img = img.resize((224, 224), Image.BICUBIC)
    arr = np.asarray(img, dtype=np.float32) / 255.0  # HWC [0,1]
    arr = arr.transpose(2, 0, 1)  # CHW
    for c in range(3):
        arr[c] = (arr[c] - CLIP_MEAN[c]) / CLIP_STD[c]
    return arr


def _cosines(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Coseno fila a fila entre dos batches de embeddings (no asume L2-norm)."""
    an = a / (np.linalg.norm(a, axis=1, keepdims=True) + 1e-9)
    bn = b / (np.linalg.norm(b, axis=1, keepdims=True) + 1e-9)
    return np.sum(an * bn, axis=1)


def quantize_and_verify(src: Path, dst: Path, images_dir: Path | None,
                        n_images: int) -> dict:
    """
    Cuantiza src→dst (int8 dinámico), verifica carga con optimizaciones DEFAULT y
    mide coseno int8 vs fp32. Devuelve un dict con el reporte.
    """
    quantize_dynamic(
        model_input=str(src), model_output=str(dst),
        weight_type=QuantType.QInt8, per_channel=True,
    )
    src_mb = os.path.getsize(src) / 1e6
    dst_mb = os.path.getsize(dst) / 1e6
    report = {"src_mb": src_mb, "dst_mb": dst_mb, "ratio": dst_mb / src_mb}

    # *** CHEQUEO CLAVE: carga con optimizaciones DEFAULT (igual que ORT Mobile) ***
    try:
        sess_q = _load_session_default_opt(dst)
        report["load_default_opt"] = True
    except Exception as exc:  # noqa: BLE001 — queremos reportar cualquier fallo de carga
        report["load_default_opt"] = False
        report["load_error"] = f"{type(exc).__name__}: {exc}"
        return report

    sess_f = _load_session_default_opt(src)
    x = _real_inputs(images_dir, n_images)
    y_f = sess_f.run(["image_features"], {"pixel_values": x})[0]
    y_q = sess_q.run(["image_features"], {"pixel_values": x})[0]
    cos = _cosines(y_f, y_q)
    report.update({
        "n_images": int(len(x)),
        "cos_mean": float(cos.mean()),
        "cos_min": float(cos.min()),
        "cos_p05": float(np.percentile(cos, 5)),
    })
    return report


def _print_report(rep: dict) -> None:
    print("=" * 60)
    print("CUANTIZACIÓN INT8 DINÁMICA — encoder ONNX")
    print("=" * 60)
    print(f"fp32: {rep['src_mb']:.1f} MB  ->  int8: {rep['dst_mb']:.1f} MB  "
          f"({rep['ratio']:.0%})")
    if not rep.get("load_default_opt"):
        print(f"\n[X] FALLO DE CARGA con optimizaciones DEFAULT (ORT Mobile):")
        print(f"    {rep.get('load_error')}")
        print("\n    RECOMENDACIÓN: desplegar fp32 (igual que con fp16).")
        return
    print("[OK] Carga con graph_optimization_level=ORT_ENABLE_ALL (= ORT Mobile)")
    print(f"\nCoseno int8 vs fp32 sobre {rep['n_images']} imágenes:")
    print(f"    media={rep['cos_mean']:.5f}  min={rep['cos_min']:.5f}  "
          f"p05={rep['cos_p05']:.5f}")
    ok = rep["cos_min"] >= 0.99
    verdict = "int8 DESPLEGABLE" if ok else "degrada demasiado, recomendar fp32"
    rel = ">=" if ok else "<"
    print(f"\n{'[OK]' if ok else '[!]'} cos_min {rel} 0.99 -> {verdict}")


def main() -> None:
    p = argparse.ArgumentParser(description="Cuantiza encoder ONNX a int8 dinámico y verifica.")
    p.add_argument("--in", dest="src", type=Path, required=True)
    p.add_argument("--out", dest="dst", type=Path, required=True)
    p.add_argument("--images-dir", type=Path, default=None,
                   help="Dir con {especie}/*.jpg para el chequeo de coseno (recomendado).")
    p.add_argument("--n-images", type=int, default=32)
    args = p.parse_args()

    rep = quantize_and_verify(args.src, args.dst, args.images_dir, args.n_images)
    _print_report(rep)


if __name__ == "__main__":
    main()
