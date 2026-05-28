"""
build_reference_bank.py — Construye el banco de referencia vectorial para BioDex RAG.

DESCRIPCIÓN
-----------
Dado un directorio de imágenes de referencia organizadas por especie, calcula
k prototipos por especie (k-means), L2-normaliza y escribe un único artefacto
autocontenido:

  species_index_v1.bin   — Índice binario para Android (header + cuerpo + trailer)

NO se genera index_meta.json: los metadatos se embeben como trailer JSONL dentro
del mismo .bin, inmediatamente tras la matriz de vectores.

FORMATO BINARIO species_index_v1.bin
--------------------------------------
  Little-endian. Lector Kotlin: `ByteBuffer.order(ByteOrder.LITTLE_ENDIAN)`.

  Sección    Offset  Size        Type      Campo
  Header     0       4           uint32    magic = 0x53504558  ('SPEX')
  Header     4       4           uint32    version = 1
  Header     8       4           uint32    n  (número total de prototipos)
  Header     12      4           uint32    dim = 512
  Header     16      16          bytes     reserved (ceros)
  Cuerpo     32      n*dim*2     float16   vectores L2-norm, row-major, little-endian
  Trailer    32+n*dim*2  ...     UTF-8     n líneas JSON separadas por '\\n'

  Trailer — línea i (0-based), mismo orden que fila i del cuerpo:
    {"speciesKey":"<key>","scientificName":"<name>","viewTag":"<tag>","nRefs":<int>}

  Tamaño total: 32 + n*512*2 + len(trailer_bytes)
  El lector Android lee el trailer como BufferedReader tras saltar 32 + n*512*2 bytes.

  Notas:
  - Little-endian en todo el header (Android es little-endian).
  - float16: precisión suficiente para similitud coseno; ahorra ~50% vs f32.
  - 1600 prototipos × 512 × 2 bytes = 1.6 MB cuerpo; trailer ~100 bytes/línea → ~160 KB.
  - Acceso prototipo i: offset 32 + i*512*2.

ESTRUCTURA DE DIRECTORIO DE IMÁGENES
--------------------------------------
  images_dir/
    amphiprion_ocellaris/       <- species_key
      img001.jpg
      img002.jpg
      lateral/                  <- view_tag (opcional)
        img010.jpg

  Si hay subcarpetas de view_tag, se usa el nombre de la subcarpeta.
  Si no, view_tag = "general".

USO
---
  # Con imágenes reales:
  python build_reference_bank.py --images-dir /path/to/refs --k 6 --output-dir ./output

  # Sin imágenes (modo mock, valida formato):
  python build_reference_bank.py --mock --output-dir ./output
"""

from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path
from typing import NamedTuple

import numpy as np

MAGIC = 0x53504558  # 'SPEX'
VERSION = 1
EMBED_DIM = 512
HEADER_SIZE = 32   # bytes: 4×uint32 + 16 reserved
DEFAULT_K = 6
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}
_VIEW_TAGS = {"lateral", "frontal", "dorsal", "juvenile", "general"}


class ProtoEntry(NamedTuple):
    proto_idx: int
    species_key: str
    scientific_name: str
    view_tag: str
    n_refs: int


def _discover_images(images_dir: Path) -> dict[str, dict[str, list[Path]]]:
    """
    Escanea images_dir y devuelve {species_key: {view_tag: [paths]}}.
    Respeta la estructura de subcarpetas de view_tag.
    """
    result: dict[str, dict[str, list[Path]]] = {}
    for species_dir in sorted(images_dir.iterdir()):
        if not species_dir.is_dir():
            continue
        species_key = species_dir.name
        views: dict[str, list[Path]] = {}

        direct = [
            p for p in species_dir.iterdir()
            if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS
        ]
        if direct:
            views["general"] = direct

        for sub in sorted(species_dir.iterdir()):
            if sub.is_dir():
                tag = sub.name if sub.name in _VIEW_TAGS else "general"
                imgs = [
                    p for p in sub.iterdir()
                    if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS
                ]
                if imgs:
                    views[tag] = views.get(tag, []) + imgs

        if views:
            result[species_key] = views
    return result


def _compute_prototypes(
    embeddings: np.ndarray, k: int, seed: int = 42
) -> np.ndarray:
    """
    Calcula k centroides por k-means sobre los embeddings dados.
    Si n_samples <= k, devuelve todas las muestras L2-normalizadas directamente.

    Returns:
        np.ndarray shape (min(k, n), EMBED_DIM) float32, L2-normalizado.
    """
    n = len(embeddings)
    if n == 0:
        return np.zeros((1, EMBED_DIM), dtype=np.float32)
    if n <= k:
        norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
        norms = np.where(norms < 1e-8, 1.0, norms)
        return (embeddings / norms).astype(np.float32)

    centers = _kmeans_numpy(embeddings.astype(np.float32), k=k, seed=seed)
    norms = np.linalg.norm(centers, axis=1, keepdims=True)
    norms = np.where(norms < 1e-8, 1.0, norms)
    return (centers / norms).astype(np.float32)


def _kmeans_numpy(
    x: np.ndarray, k: int, seed: int = 42, n_init: int = 4, max_iter: int = 50
) -> np.ndarray:
    """
    K-means en numpy puro (k-means++ init + Lloyd), determinista por `seed`.

    Sustituye a sklearn.cluster.KMeans para evitar arrastrar scipy/sklearn al
    camino caliente (import muy lento bajo Windows Defender). Para el k pequeño
    de los prototipos (k≈4-8) es equivalente y mucho más ligero.

    Returns:
        centroides float32 shape (k, dim), del mejor restart (menor inercia).
    """
    best_centers: np.ndarray | None = None
    best_inertia = np.inf
    for run in range(n_init):
        rng = np.random.default_rng(seed + run)
        # k-means++ init
        centers = [x[rng.integers(len(x))]]
        for _ in range(1, k):
            d2 = np.min(
                ((x[:, None, :] - np.stack(centers)[None, :, :]) ** 2).sum(-1), axis=1
            )
            probs = d2 / d2.sum() if d2.sum() > 0 else None
            centers.append(x[rng.choice(len(x), p=probs)])
        c = np.stack(centers)
        for _ in range(max_iter):
            dists = ((x[:, None, :] - c[None, :, :]) ** 2).sum(-1)
            labels = dists.argmin(axis=1)
            new_c = np.stack([
                x[labels == j].mean(axis=0) if np.any(labels == j) else c[j]
                for j in range(k)
            ])
            if np.allclose(new_c, c):
                c = new_c
                break
            c = new_c
        inertia = float(((x - c[labels]) ** 2).sum())
        if inertia < best_inertia:
            best_inertia, best_centers = inertia, c
    assert best_centers is not None
    return best_centers.astype(np.float32)


def _write_bin(output_path: Path, matrix: np.ndarray, entries: list[ProtoEntry]) -> None:
    """
    Escribe el fichero autocontenido species_index_v1.bin.

    Layout:
      [Header 32 bytes] [Cuerpo n*512*2 bytes float16] [Trailer n líneas JSONL UTF-8]

    Args:
        output_path: Ruta de destino.
        matrix: float32 array (n, 512), L2-normalizado.
        entries: Metadatos de cada prototipo, en el mismo orden que las filas.
    """
    n_prototypes, dim = matrix.shape
    assert dim == EMBED_DIM, f"Dim esperada {EMBED_DIM}, obtenida {dim}"
    assert len(entries) == n_prototypes, "entries y matrix deben tener la misma longitud"

    # Header: magic(4) + version(4) + n(4) + dim(4) + reserved(16) = 32 bytes
    header = struct.pack(
        "<IIII",
        MAGIC,
        VERSION,
        n_prototypes,
        EMBED_DIM,
    ) + b"\x00" * 16

    assert len(header) == HEADER_SIZE

    # Cuerpo: float16 row-major little-endian
    body = matrix.astype(np.float16).tobytes()

    # Trailer: n líneas JSON UTF-8, separadas por '\n'
    trailer_lines = []
    for e in entries:
        line = json.dumps(
            {
                "speciesKey": e.species_key,
                "scientificName": e.scientific_name,
                "viewTag": e.view_tag,
                "nRefs": e.n_refs,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
        trailer_lines.append(line)
    trailer = "\n".join(trailer_lines).encode("utf-8")

    with open(output_path, "wb") as f:
        f.write(header)
        f.write(body)
        f.write(trailer)


def _mock_embeddings(n: int, seed: int) -> np.ndarray:
    """Genera n embeddings sintéticos L2-normalizados para modo --mock."""
    rng = np.random.default_rng(seed)
    vecs = rng.standard_normal((n, EMBED_DIM)).astype(np.float32)
    norms = np.linalg.norm(vecs, axis=1, keepdims=True)
    return vecs / np.where(norms < 1e-8, 1.0, norms)


# Mapeo species_key → nombre científico para el mock (subset de taxon_list.yaml)
_MOCK_SCI_NAMES: dict[str, str] = {
    "amphiprion_ocellaris": "Amphiprion ocellaris",
    "caretta_caretta":      "Caretta caretta",
    "octopus_vulgaris":     "Octopus vulgaris",
    "aurelia_aurita":       "Aurelia aurita",
    "rhincodon_typus":      "Rhincodon typus",
}


def build_mock(output_dir: Path, k: int) -> Path:
    """
    Crea species_index_v1.bin sintético para validar el formato sin imágenes reales.
    Usa 5 especies × k prototipos cada una.

    Returns:
        Path al .bin generado.
    """
    mock_taxa = list(_MOCK_SCI_NAMES.keys())
    all_protos: list[np.ndarray] = []
    entries: list[ProtoEntry] = []
    proto_idx = 0

    for i, species_key in enumerate(mock_taxa):
        n_refs = 10 + i * 7
        raw = _mock_embeddings(n_refs, seed=i * 100)
        protos = _compute_prototypes(raw, k=k)
        sci_name = _MOCK_SCI_NAMES[species_key]
        for j, proto in enumerate(protos):
            all_protos.append(proto)
            view_tag = ["lateral", "dorsal", "frontal", "juvenile"][j % 4]
            entries.append(ProtoEntry(
                proto_idx=proto_idx,
                species_key=species_key,
                scientific_name=sci_name,
                view_tag=view_tag,
                n_refs=n_refs,
            ))
            proto_idx += 1

    matrix = np.stack(all_protos, axis=0)
    output_dir.mkdir(parents=True, exist_ok=True)
    bin_path = output_dir / "species_index_v1.bin"
    _write_bin(bin_path, matrix, entries)
    return bin_path


def build_from_images(
    images_dir: Path,
    output_dir: Path,
    k: int,
    embedder_type: str,
    sci_name_map: dict[str, str] | None = None,
    ckpt_path: str | None = None,
    encoder: str | None = None,
) -> Path:
    """
    Construye el banco real desde un directorio de imágenes.

    Args:
        sci_name_map: {species_key: scientific_name}. Si None, usa species_key como nombre.
        embedder_type: 'openclip' | 'fake'
        ckpt_path: ckpt de fine-tune (train_encoder.py) para OpenCLIPEmbedder. None=base.
        encoder: clave en embedder.ENCODERS. None → default (back-compat).

    Returns:
        Path al .bin generado.
    """
    import sys
    sys.path.insert(0, str(Path(__file__).parent))
    from embedder import FakeEmbedder, OpenCLIPEmbedder, DEFAULT_ENCODER  # type: ignore[import]

    if embedder_type == "fake":
        emb = FakeEmbedder()
    else:
        emb = OpenCLIPEmbedder(
            ckpt_path=ckpt_path, encoder=encoder or DEFAULT_ENCODER
        )
    image_map = _discover_images(images_dir)
    if not image_map:
        raise FileNotFoundError(f"No se encontraron imágenes en {images_dir}")

    all_protos: list[np.ndarray] = []
    entries: list[ProtoEntry] = []
    proto_idx = 0
    name_map = sci_name_map or {}

    for species_key, views in sorted(image_map.items()):
        sci_name = name_map.get(species_key, species_key)
        for view_tag, paths in sorted(views.items()):
            str_paths = [str(p) for p in paths]
            print(f"  {species_key}/{view_tag}: {len(str_paths)} imgs...")
            raw = emb.embed(str_paths)
            protos = _compute_prototypes(raw, k=k)
            for proto in protos:
                all_protos.append(proto)
                entries.append(ProtoEntry(
                    proto_idx=proto_idx,
                    species_key=species_key,
                    scientific_name=sci_name,
                    view_tag=view_tag,
                    n_refs=len(str_paths),
                ))
                proto_idx += 1

    matrix = np.stack(all_protos, axis=0)
    output_dir.mkdir(parents=True, exist_ok=True)
    bin_path = output_dir / "species_index_v1.bin"
    _write_bin(bin_path, matrix, entries)
    return bin_path


def verify_bin(bin_path: Path) -> dict:
    """
    Lee y valida el header del .bin.
    Devuelve un dict con los campos del header y n_prototypes para diagnóstico.
    """
    with open(bin_path, "rb") as f:
        raw_hdr = f.read(HEADER_SIZE)

    magic, version, n_proto, dim = struct.unpack_from("<IIII", raw_hdr, 0)
    assert magic == MAGIC,       f"Magic incorrecto: 0x{magic:08X} (esperado 0x{MAGIC:08X})"
    assert version == VERSION,   f"Version incorrecta: {version}"
    assert dim == EMBED_DIM,     f"Dim incorrecta: {dim}"
    assert raw_hdr[16:] == b"\x00" * 16, "Bytes reserved no son cero"

    body_size = n_proto * EMBED_DIM * 2
    actual_size = bin_path.stat().st_size
    # Tamaño mínimo: header + body (sin contar trailer variable)
    assert actual_size >= HEADER_SIZE + body_size, (
        f"Fichero demasiado pequeño: {actual_size} < {HEADER_SIZE + body_size}"
    )

    trailer_size = actual_size - HEADER_SIZE - body_size
    print(
        f"  Verificado: {bin_path.name} — "
        f"n={n_proto} dim={dim} "
        f"header={HEADER_SIZE}B body={body_size}B trailer={trailer_size}B "
        f"total={actual_size}B"
    )
    return {"n_prototypes": n_proto, "dim": dim, "trailer_bytes": trailer_size}


def _read_trailer(bin_path: Path, n_proto: int) -> list[dict]:
    """Lee el trailer JSONL del .bin y devuelve la lista de metadatos."""
    offset = HEADER_SIZE + n_proto * EMBED_DIM * 2
    with open(bin_path, "rb") as f:
        f.seek(offset)
        raw = f.read()
    lines = raw.decode("utf-8").strip().split("\n")
    return [json.loads(line) for line in lines if line.strip()]


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Construye species_index_v1.bin (autocontenido) para BioDex RAG."
    )
    parser.add_argument(
        "--images-dir",
        type=Path,
        help="Directorio raíz con subdirectorios por especie (species_key/).",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("output"),
        help="Directorio de salida (default: ./output).",
    )
    parser.add_argument(
        "--k",
        type=int,
        default=DEFAULT_K,
        help=f"Número de prototipos por especie/vista (default: {DEFAULT_K}).",
    )
    parser.add_argument(
        "--embedder",
        choices=["openclip", "fake"],
        default="openclip",
        help="Embedder a usar (default: openclip).",
    )
    parser.add_argument(
        "--mock",
        action="store_true",
        help="Modo mock: genera datos sintéticos para validar el formato.",
    )
    parser.add_argument(
        "--load-ckpt",
        metavar="PATH",
        default=None,
        help="ckpt de fine-tune (train_encoder.py) para el embedder openclip.",
    )
    parser.add_argument(
        "--encoder",
        type=str,
        default=None,
        help="Clave en embedder.ENCODERS (default: openclip-b32-laion2b).",
    )
    args = parser.parse_args()

    if args.mock:
        print("Modo MOCK: generando indice sintetico...")
        bin_p = build_mock(args.output_dir, k=args.k)
    else:
        if not args.images_dir:
            parser.error("--images-dir es obligatorio cuando no se usa --mock.")
        print(f"Construyendo banco desde {args.images_dir}...")
        bin_p = build_from_images(
            args.images_dir, args.output_dir, k=args.k, embedder_type=args.embedder,
            ckpt_path=args.load_ckpt, encoder=args.encoder,
        )

    info = verify_bin(bin_p)
    # Mostrar primeras 2 líneas del trailer para confirmación
    trailer = _read_trailer(bin_p, info["n_prototypes"])
    print(f"  Trailer muestra (primeras 2 lineas):")
    for row in trailer[:2]:
        print(f"    {json.dumps(row, ensure_ascii=False)}")
    print(f"\nArtefacto escrito: {bin_p.resolve()}  ({bin_p.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
