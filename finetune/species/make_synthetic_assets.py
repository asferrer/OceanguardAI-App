"""
make_synthetic_assets.py — Assets BioDex en formato REAL pero sintéticos.

Genera un encoder ONNX de juguete (SIN torch) + un índice/imágenes de referencia
COHERENTES con él, para verificar end-to-end el path real de
`OceanGuardApp.buildSpeciesIdentifier()` (`OnnxSpeciesEmbedder` +
`SpeciesReferenceIndex.load` + `MarineRegionResolver`) en device, SIN datos
biológicos reales.

El encoder NO es semántico: proyecta el color medio de la imagen
(`GlobalAveragePool → Flatten → MatMul W[3,512]`). Su único fin es ejercitar la
inferencia ONNX Runtime / NNAPI y el contrato binario on-device. Las imágenes de
referencia son colores sólidos → invariantes a crop/resize → el embedding de
query reproduce exactamente el del banco → match determinista.

TODO(swap a asset real): reemplazar `clip_vitb32.onnx` por el OpenCLIP ViT-B/32
exportado (M0/B5) y reconstruir el índice desde imágenes reales (FathomNet/iNat).

Uso:
    python make_synthetic_assets.py --output-dir output_synth
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from build_distribution_filter import _load_taxon_list, build_catalog_mock  # noqa: E402
from build_meow_raster import build_mock_raster  # noqa: E402
from build_reference_bank import EMBED_DIM, ProtoEntry, _write_bin  # noqa: E402

# Constantes CLIP idénticas a OnnxSpeciesEmbedder.kt (preprocess en device).
CLIP_MEAN = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
CLIP_STD = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)
SEED = 1234

# species_key (debe existir en taxon_list.yaml) → color sólido RGB de referencia.
SYNTH_SPECIES: dict[str, tuple[int, int, int]] = {
    "amphiprion_ocellaris": (242, 110, 30),   # naranja payaso
    "octopus_vulgaris":     (150, 60, 120),   # púrpura
    "caretta_caretta":      (90, 140, 60),    # verde tortuga
    "dicentrarchus_labrax": (160, 175, 190),  # gris lubina
    "mola_mola":            (200, 205, 210),  # gris claro
}
SCI_NAMES = {
    "amphiprion_ocellaris": "Amphiprion ocellaris",
    "octopus_vulgaris": "Octopus vulgaris",
    "caretta_caretta": "Caretta caretta",
    "dicentrarchus_labrax": "Dicentrarchus labrax",
    "mola_mola": "Mola mola",
}


def _weight() -> np.ndarray:
    """Matriz de proyección fija (3→512), determinista por SEED."""
    rng = np.random.default_rng(SEED)
    return rng.standard_normal((3, EMBED_DIM)).astype(np.float32)


def export_onnx(path: Path, w: np.ndarray) -> None:
    """Exporta el encoder de juguete con el contrato I/O de OnnxSpeciesEmbedder."""
    inp = helper.make_tensor_value_info("pixel_values", TensorProto.FLOAT, [1, 3, 224, 224])
    out = helper.make_tensor_value_info("image_features", TensorProto.FLOAT, [1, EMBED_DIM])
    w_init = helper.make_tensor("W", TensorProto.FLOAT, [3, EMBED_DIM], w.flatten().tolist())
    nodes = [
        helper.make_node("GlobalAveragePool", ["pixel_values"], ["pooled"]),
        helper.make_node("Flatten", ["pooled"], ["flat"], axis=1),
        helper.make_node("MatMul", ["flat", "W"], ["image_features"]),
    ]
    graph = helper.make_graph(nodes, "synthetic_clip", [inp], [out], [w_init])
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])
    model.ir_version = 9  # compatible con onnxruntime-android estable
    onnx.checker.check_model(model)
    onnx.save(model, str(path))


def _color_embed(rgb: tuple[int, int, int], w: np.ndarray) -> np.ndarray:
    """Embedding de un color sólido = el que produce el ONNX tras el preprocess
    CLIP de Kotlin (avgpool de una imagen constante = el píxel normalizado)."""
    v = (np.array(rgb, dtype=np.float32) / 255.0 - CLIP_MEAN) / CLIP_STD
    emb = (v @ w).astype(np.float32)
    norm = np.linalg.norm(emb)
    return emb / norm if norm > 1e-8 else emb


def build_index(output_dir: Path, w: np.ndarray) -> None:
    """Índice con 1 prototipo por especie (L2-norm), coherente con el ONNX."""
    protos, entries = [], []
    for idx, (key, rgb) in enumerate(SYNTH_SPECIES.items()):
        protos.append(_color_embed(rgb, w))
        entries.append(ProtoEntry(
            proto_idx=idx, species_key=key, scientific_name=SCI_NAMES[key],
            view_tag="synthetic", n_refs=1,
        ))
    _write_bin(output_dir / "species_index_v1.bin", np.stack(protos), entries)


def write_reference_images(output_dir: Path) -> None:
    """PNG de color sólido por especie (256×256). El de la primera especie se
    copia como query.png para el test en device."""
    img_dir = output_dir / "ref_images"
    img_dir.mkdir(parents=True, exist_ok=True)
    for key, rgb in SYNTH_SPECIES.items():
        Image.new("RGB", (256, 256), rgb).save(img_dir / f"{key}.png")
    first_key = next(iter(SYNTH_SPECIES))
    Image.new("RGB", (256, 256), SYNTH_SPECIES[first_key]).save(output_dir / "query.png")


def validate_onnx(path: Path, w: np.ndarray) -> None:
    """Verifica con onnxruntime que el ONNX reproduce _color_embed (pre-norm)."""
    import onnxruntime as ort  # import perezoso

    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    rgb = next(iter(SYNTH_SPECIES.values()))
    v = (np.array(rgb, dtype=np.float32) / 255.0 - CLIP_MEAN) / CLIP_STD
    img = np.tile(v.reshape(1, 3, 1, 1), (1, 1, 224, 224)).astype(np.float32)
    out = sess.run(["image_features"], {"pixel_values": img})[0]
    expected = (v @ w).astype(np.float32)
    err = float(np.max(np.abs(out[0] - expected)))
    assert err < 1e-3, f"ONNX diverge de _color_embed: max|err|={err}"
    print(f"  validate_onnx OK (max|err|={err:.2e}, out.shape={out.shape})")


def main() -> None:
    parser = argparse.ArgumentParser(description="Genera assets BioDex sintéticos en formato real.")
    parser.add_argument("--output-dir", type=Path, default=Path("output_synth"))
    args = parser.parse_args()
    out = args.output_dir
    out.mkdir(parents=True, exist_ok=True)

    w = _weight()
    print("Generando encoder ONNX sintético...")
    export_onnx(out / "clip_vitb32.onnx", w)
    validate_onnx(out / "clip_vitb32.onnx", w)

    print("Generando índice coherente con el ONNX...")
    build_index(out, w)

    print("Generando catálogo + ráster MEOW + jerarquía (mock)...")
    taxon_list = _load_taxon_list(Path(__file__).parent / "taxon_list.yaml")
    build_catalog_mock(taxon_list, out / "species_catalog_v1.json")
    build_mock_raster(out)

    print("Generando imágenes de referencia + query.png...")
    write_reference_images(out)

    print(f"\nAssets sintéticos listos en {out}/:")
    for f in sorted(out.glob("*")):
        if f.is_file():
            print(f"  {f.name}: {f.stat().st_size:,} bytes")
    print("OK.")


if __name__ == "__main__":
    main()
