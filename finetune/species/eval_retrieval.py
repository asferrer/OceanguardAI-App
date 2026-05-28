"""
eval_retrieval.py — Evaluación de retrieval top-k para BioDex RAG visual.

DESCRIPCIÓN
-----------
Evalúa la calidad del índice vectorial sobre un held-out de imágenes con
label de especie y (opcionalmente) lat/lon real.

Métricas reportadas:
  - Top-1 accuracy          : fracción de queries donde la especie verdadera es rank-1
  - Top-5 accuracy          : fracción de queries donde aparece en los 5 primeros
  - Avg candidates (N_cand) : media de candidatos tras aplicar el pre-filtro geográfico
  - False discard rate (FDR): fracción de queries donde la especie verdadera fue
                               excluida por el filtro geográfico (debe ser ≈0 en BALANCED)

Ablación de pre-filtro geográfico (3 modos):
  - OFF       : sin filtro, búsqueda global sobre todas las especies
  - BALANCED  : hard-filter a realm + soft geo-prior en la puntuación (default plan)
  - STRICT    : hard-filter a provincia (más agresivo, puede aumentar FDR)

USO
---
  # Modo mock (datos sintéticos, no requiere imágenes):
  python eval_retrieval.py --mock

  # Con datos reales (index es autocontenido; no se necesita --meta separado):
  python eval_retrieval.py \\
      --index output/species_index_v1.bin \\
      --catalog output/species_catalog_v1.json \\
      --raster output/meow_raster_v1.bin \\
      --hierarchy output/ecoregion_hierarchy_v1.json \\
      --held-out /path/to/held_out.jsonl \\
      --embedder openclip

  Formato held_out.jsonl (una query por línea):
    {"image_path": "/abs/path/img.jpg", "species_key": "amphiprion_ocellaris",
     "lat": 9.5, "lon": 119.3}
    (lat/lon opcionales; si ausentes → región=None → modo OFF efectivo)

FORMATOS BINARIOS LEÍDOS
-------------------------
species_index_v1.bin (header 32 bytes):
  [0:4]  magic   uint32 = 0x53504558
  [4:8]  version uint32 = 1
  [8:12] n       uint32  (nº prototipos)
  [12:16] dim    uint32 = 512
  [16:32] reserved (ceros)
  [32:]  n*512*2 bytes float16 row-major
  [32+n*512*2:] trailer JSONL — n líneas, línea i = metadata prototipo i

meow_raster_v1.bin (header 32 bytes, resolución 0.25° implícita):
  [0:4]  magic   uint32 = 0x4D455257
  [4:8]  version uint32 = 1
  [8:12] nLat    uint32 = 720
  [12:16] nLon   uint32 = 1440
  [16:32] reserved (ceros)
  [32:]  720*1440*2 bytes int16 row-major (norte→sur, oeste→este)

ESTRUCTURA DE MÓDULOS INTERNOS
--------------------------------
El módulo implementa versiones simplificadas del pipeline Android para que la
evaluación sea autocontenida. Los cálculos de geo-prior replican exactamente
la lógica de GeoPrior.kt (ver plan sección 2b).
"""

from __future__ import annotations

import argparse
import json
import struct
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

EMBED_DIM = 512
MAGIC_INDEX = 0x53504558   # 'SPEX' — species index
MAGIC_RASTER = 0x4D455257  # 'MERW' — MEOW raster
INDEX_HEADER_SIZE = 32     # bytes
RASTER_HEADER_SIZE = 32    # bytes
RASTER_CELL_SIZE = 0.25    # grados, implícito (no en header)

# Geo-prior weights (espejo de GeoPrior.kt)
GEO_PRIOR = {
    "ecoregion": 1.0,
    "province":  0.6,
    "realm":     0.3,
    "global":    0.1,   # cosmopolita / sin datos
}

TAU_HIGH = 0.35   # umbral confianza alta → ID núcleo
TAU_LOW  = 0.20   # umbral baja → tentativo


# ---------------------------------------------------------------------------
# Estructuras de datos
# ---------------------------------------------------------------------------

@dataclass
class ProtoMeta:
    proto_idx: int
    species_key: str
    view_tag: str
    n_refs: int


@dataclass
class SpeciesDistrib:
    species_key: str
    ecoregions: list[int]
    cosmopolitan: bool
    province_ids: set[int] = field(default_factory=set)
    realm_ids: set[int] = field(default_factory=set)


@dataclass
class ResolvedRegion:
    ecoregion_id: int
    province_id: int
    realm_id: int


# ---------------------------------------------------------------------------
# Lectura de artefactos
# ---------------------------------------------------------------------------

def _load_index(bin_path: Path) -> tuple[np.ndarray, list[ProtoMeta]]:
    """
    Lee species_index_v1.bin (formato autocontenido).

    Returns:
        (matrix float32 (N, 512), metas list[ProtoMeta])
        El trailer JSONL embebido tras el cuerpo proporciona los metadatos.
    """
    with open(bin_path, "rb") as f:
        raw_hdr = f.read(INDEX_HEADER_SIZE)

    magic, ver, n_proto, dim = struct.unpack_from("<IIII", raw_hdr, 0)
    assert magic == MAGIC_INDEX, f"Magic indice incorrecto: 0x{magic:08X}"
    assert ver == 1,             f"Version incorrecta: {ver}"
    assert dim == EMBED_DIM,     f"Dim incorrecta: {dim}"

    body_size = n_proto * EMBED_DIM * 2
    with open(bin_path, "rb") as f:
        f.seek(INDEX_HEADER_SIZE)
        body_raw = f.read(body_size)
        trailer_raw = f.read()  # todo lo que quede tras el cuerpo

    matrix = np.frombuffer(body_raw, dtype=np.float16).reshape(n_proto, EMBED_DIM)
    matrix = matrix.astype(np.float32)

    # Parsear trailer JSONL
    trailer_text = trailer_raw.decode("utf-8").strip()
    metas: list[ProtoMeta] = []
    for idx, line in enumerate(trailer_text.split("\n")):
        line = line.strip()
        if not line:
            continue
        obj = json.loads(line)
        metas.append(ProtoMeta(
            proto_idx=idx,
            species_key=obj["speciesKey"],
            view_tag=obj.get("viewTag", "general"),
            n_refs=obj.get("nRefs", 0),
        ))

    assert len(metas) == n_proto, (
        f"Trailer tiene {len(metas)} entradas, header dice {n_proto}"
    )
    return matrix, metas


def _load_catalog(cat_path: Path) -> dict[str, SpeciesDistrib]:
    with open(cat_path, encoding="utf-8") as f:
        data = json.load(f)
    result: dict[str, SpeciesDistrib] = {}
    for sp in data["species"]:
        result[sp["speciesKey"]] = SpeciesDistrib(
            species_key=sp["speciesKey"],
            ecoregions=sp.get("ecoregions", []),
            cosmopolitan=sp.get("cosmopolitan", False),
        )
    return result


def _load_raster(bin_path: Path) -> tuple[np.ndarray, float, float]:
    """
    Lee meow_raster_v1.bin (header 32 bytes, resolución 0.25° implícita).

    Returns:
        (grid int16 (nLat, nLon), lat_max=90.0, lon_min=-180.0)
        lat_max y lon_min son constantes implícitas del formato.
    """
    with open(bin_path, "rb") as f:
        raw_hdr = f.read(RASTER_HEADER_SIZE)

    magic, ver, n_lat, n_lon = struct.unpack_from("<IIII", raw_hdr, 0)
    assert magic == MAGIC_RASTER, f"Magic raster incorrecto: 0x{magic:08X}"
    assert ver == 1,              f"Version incorrecta: {ver}"

    with open(bin_path, "rb") as f:
        f.seek(RASTER_HEADER_SIZE)
        raw_data = f.read(n_lat * n_lon * 2)

    grid = np.frombuffer(raw_data, dtype="<i2").reshape(n_lat, n_lon)
    # Constantes implícitas del formato (resolución 0.25° fija)
    lat_max = 90.0
    lon_min = -180.0
    return grid, lat_max, lon_min


def _load_hierarchy(hier_path: Path) -> list[dict]:
    """Carga ecoregion_hierarchy_v1.json como lista de objetos."""
    with open(hier_path, encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------------------
# Geo-resolución
# ---------------------------------------------------------------------------

def _resolve_region(
    lat: float, lon: float,
    grid: np.ndarray,
    lat_max: float, lon_min: float,
    hierarchy: dict,
) -> ResolvedRegion | None:
    """
    Convierte lat/lon → ResolvedRegion usando la resolución implícita 0.25°.
    Devuelve None si la celda no tiene ecoregión MEOW (oceano abierto o tierra).
    """
    n_rows, n_cols = grid.shape
    row = int((lat_max - lat) / RASTER_CELL_SIZE)
    col = int((lon - lon_min) / RASTER_CELL_SIZE)
    row = max(0, min(n_rows - 1, row))
    col = max(0, min(n_cols - 1, col))
    eco_id = int(grid[row, col])

    # Buscar en anillo si cae en tierra
    if eco_id == 0:
        for radius in range(1, 8):
            for dr in range(-radius, radius + 1):
                for dc in range(-radius, radius + 1):
                    r2, c2 = row + dr, col + dc
                    if 0 <= r2 < n_rows and 0 <= c2 < n_cols:
                        val = int(grid[r2, c2])
                        if val > 0:
                            eco_id = val
                            break
                if eco_id > 0:
                    break
            if eco_id > 0:
                break

    if eco_id <= 0:
        return None

    # hierarchy is the eco_lookup dict {ecoregionId: {provinceId, realmId}}
    entry = hierarchy.get(eco_id, {})
    province_id = int(entry.get("provinceId", -1))
    realm_id = int(entry.get("realmId", -1))
    return ResolvedRegion(ecoregion_id=eco_id, province_id=province_id, realm_id=realm_id)


def _build_eco_lookup(hierarchy: list[dict]) -> dict[int, dict]:
    """
    Convierte el array plano de ecoregion_hierarchy_v1.json en un dict
    {ecoregionId: {provinceId, realmId}} para lookup O(1).
    """
    return {
        row["ecoregionId"]: {
            "provinceId": row["provinceId"],
            "realmId":    row["realmId"],
        }
        for row in hierarchy
    }


def _enrich_distribs(
    distribs: dict[str, SpeciesDistrib], eco_lookup: dict[int, dict]
) -> None:
    """Rellena province_ids y realm_ids en cada SpeciesDistrib."""
    for distrib in distribs.values():
        for eco_id in distrib.ecoregions:
            entry = eco_lookup.get(eco_id, {})
            prov = entry.get("provinceId", -1)
            realm = entry.get("realmId", -1)
            if prov > 0:
                distrib.province_ids.add(int(prov))
            if realm > 0:
                distrib.realm_ids.add(int(realm))


# ---------------------------------------------------------------------------
# Pre-filtro y geo-prior
# ---------------------------------------------------------------------------

def _geo_prior(distrib: SpeciesDistrib, region: ResolvedRegion | None) -> float:
    """Calcula g(especie) ∈ {1.0, 0.6, 0.3, 0.1} según plan sección 2b."""
    if distrib.cosmopolitan or not distrib.ecoregions:
        return GEO_PRIOR["global"]
    if region is None:
        return GEO_PRIOR["global"]
    if region.ecoregion_id in distrib.ecoregions:
        return GEO_PRIOR["ecoregion"]
    if region.province_id in distrib.province_ids:
        return GEO_PRIOR["province"]
    if region.realm_id in distrib.realm_ids:
        return GEO_PRIOR["realm"]
    return GEO_PRIOR["global"] * 0.01  # fuera de rango → casi-excluida


def _candidate_keys(
    distribs: dict[str, SpeciesDistrib],
    region: ResolvedRegion | None,
    mode: str,
) -> set[str]:
    """
    Devuelve el conjunto de species_key candidatos para el modo dado.
    mode: 'OFF' | 'BALANCED' | 'STRICT'
    """
    if mode == "OFF" or region is None:
        return set(distribs.keys())

    result = set()
    for key, distrib in distribs.items():
        if distrib.cosmopolitan or not distrib.ecoregions:
            result.add(key)
            continue
        if mode == "BALANCED" and region.realm_id in distrib.realm_ids:
            result.add(key)
        elif mode == "STRICT" and region.province_id in distrib.province_ids:
            result.add(key)
    return result


# ---------------------------------------------------------------------------
# Búsqueda vectorial
# ---------------------------------------------------------------------------

def _search(
    query: np.ndarray,
    index: np.ndarray,
    metas: list[ProtoMeta],
    distribs: dict[str, SpeciesDistrib],
    candidate_keys: set[str],
    region: ResolvedRegion | None,
    top_k: int = 5,
) -> list[tuple[str, float]]:
    """
    Brute-force coseno (producto punto, vectores L2-norm) con geo-prior.
    Devuelve lista (species_key, adjusted_score) top_k, ordenada desc.
    """
    # Filtrar prototipos por candidatos
    active_idx = [
        i for i, m in enumerate(metas) if m.species_key in candidate_keys
    ]
    if not active_idx:
        active_idx = list(range(len(metas)))

    sub_index = index[active_idx]
    scores = sub_index @ query.reshape(-1, 1)  # (M, 1)
    scores = scores.flatten()

    # Agregar por especie (max score entre prototipos)
    species_scores: dict[str, float] = {}
    for local_i, global_i in enumerate(active_idx):
        key = metas[global_i].species_key
        s = float(scores[local_i])
        if key not in species_scores or s > species_scores[key]:
            species_scores[key] = s

    # Aplicar geo-prior
    adjusted: dict[str, float] = {}
    for key, s in species_scores.items():
        distrib = distribs.get(key)
        g = _geo_prior(distrib, region) if distrib else GEO_PRIOR["global"]
        adjusted[key] = s * g

    ranked = sorted(adjusted.items(), key=lambda x: -x[1])
    return ranked[:top_k]


# ---------------------------------------------------------------------------
# Evaluación
# ---------------------------------------------------------------------------

@dataclass
class AblationResult:
    mode: str
    top1: float
    top5: float
    avg_candidates: float
    false_discard_rate: float
    n_queries: int


def _eval_mode(
    queries: list[dict],
    index: np.ndarray,
    metas: list[ProtoMeta],
    distribs: dict[str, SpeciesDistrib],
    grid: np.ndarray,
    lat_max: float, lon_min: float,
    eco_lookup: dict[int, dict],
    embedder: Any,
    mode: str,
) -> AblationResult:
    top1_hits = 0
    top5_hits = 0
    total_candidates = 0
    false_discards = 0

    for q in queries:
        gt_key = q["species_key"]
        lat = q.get("lat")
        lon = q.get("lon")

        region = None
        if lat is not None and lon is not None:
            region = _resolve_region(
                float(lat), float(lon), grid, lat_max, lon_min, eco_lookup
            )

        candidates = _candidate_keys(distribs, region, mode)
        total_candidates += len(candidates)

        # Falso descarte: la especie verdadera fue excluida por el filtro
        if gt_key not in candidates:
            false_discards += 1

        query_vec = embedder.embed([q["image_path"]])[0]
        ranked = _search(query_vec, index, metas, distribs, candidates, region, top_k=5)
        ranked_keys = [k for k, _ in ranked]

        if ranked_keys and ranked_keys[0] == gt_key:
            top1_hits += 1
        if gt_key in ranked_keys:
            top5_hits += 1

    n = len(queries)
    return AblationResult(
        mode=mode,
        top1=top1_hits / n if n else 0.0,
        top5=top5_hits / n if n else 0.0,
        avg_candidates=total_candidates / n if n else 0.0,
        false_discard_rate=false_discards / n if n else 0.0,
        n_queries=n,
    )


def run_ablation(
    queries: list[dict],
    index: np.ndarray,
    metas: list[ProtoMeta],
    distribs: dict[str, SpeciesDistrib],
    grid: np.ndarray,
    lat_max: float, lon_min: float,
    eco_lookup: dict[int, dict],
    embedder: Any,
) -> list[AblationResult]:
    results = []
    for mode in ("OFF", "BALANCED", "STRICT"):
        print(f"  Evaluando modo {mode}...")
        r = _eval_mode(
            queries, index, metas, distribs, grid, lat_max, lon_min,
            eco_lookup, embedder, mode
        )
        results.append(r)
    return results


def _print_table(results: list[AblationResult]) -> None:
    print("\n" + "=" * 65)
    print("ABLACIÓN PRE-FILTRO BIOGEOGRÁFICO — BioDex RAG")
    print("=" * 65)
    print(f"{'Modo':<12} {'Top-1':>6} {'Top-5':>6} {'Cand.':>7} {'FDR':>7}  {'N':>5}")
    print("-" * 65)
    for r in results:
        print(
            f"{r.mode:<12} "
            f"{r.top1:>5.1%} "
            f"{r.top5:>5.1%} "
            f"{r.avg_candidates:>7.1f} "
            f"{r.false_discard_rate:>6.1%}  "
            f"{r.n_queries:>5}"
        )
    print("=" * 65)
    print("FDR = False Discard Rate (especie verdadera excluida por filtro geo)")
    print("Cand. = media de candidatos tras el filtro")


# ---------------------------------------------------------------------------
# Modo mock: genera datos sintéticos autocontenidos
# ---------------------------------------------------------------------------

def _mock_eval(output_dir: Path) -> None:
    """
    Ejecuta ablación end-to-end sobre datos 100% sintéticos.
    Reconstruye todos los artefactos en output_dir antes de evaluar.
    Genera 30 queries (6 por especie × 5 especies) con lat/lon plausibles.
    """
    import sys
    sys.path.insert(0, str(Path(__file__).parent))
    from build_reference_bank import build_mock
    from build_meow_raster import build_mock_raster
    from build_distribution_filter import build_catalog_mock, _load_taxon_list
    from embedder import FakeEmbedder

    output_dir.mkdir(parents=True, exist_ok=True)
    bin_path    = output_dir / "species_index_v1.bin"
    cat_path    = output_dir / "species_catalog_v1.json"
    raster_path = output_dir / "meow_raster_v1.bin"
    hier_path   = output_dir / "ecoregion_hierarchy_v1.json"

    print("Mock: construyendo artefactos...")
    build_mock(output_dir, k=4)
    build_mock_raster(output_dir)

    taxon_yaml = Path(__file__).parent / "taxon_list.yaml"
    taxon_list = _load_taxon_list(taxon_yaml)
    build_catalog_mock(taxon_list, cat_path)

    # Cargar artefactos con las nuevas signaturas
    index, metas = _load_index(bin_path)
    distribs    = _load_catalog(cat_path)
    grid, lat_max, lon_min = _load_raster(raster_path)
    hierarchy   = _load_hierarchy(hier_path)
    eco_lookup  = _build_eco_lookup(hierarchy)
    _enrich_distribs(distribs, eco_lookup)

    available_species = sorted({m.species_key for m in metas})

    # Coordenada representativa por especie: cae en una ecorregión donde la
    # especie está documentada (in-region) → BALANCED nunca debe descartarla
    # (FDR=0, propiedad de seguridad del plan §2b). Las cosmopolitas/sin-datos
    # están exentas del filtro en cualquier coordenada. Una de cada 6 queries
    # va sin GPS (region=None ≡ ruta OFF) para ejercer la degradación elegante.
    in_region_coord: dict[str, tuple[float, float]] = {
        "amphiprion_ocellaris": (2.0, 124.0),   # Coral Triangle (eco 84)
        "octopus_vulgaris":     (38.5, 15.0),    # Mediterraneo (eco 25)
        "caretta_caretta":      (18.0, -70.0),   # cosmopolita → Caribe
        "aurelia_aurita":       (38.5, 15.0),    # cosmopolita → Mediterraneo
        "rhincodon_typus":      (2.0, 124.0),    # cosmopolita → Coral Triangle
    }

    queries: list[dict] = []
    for sp_key in available_species:
        coord = in_region_coord.get(sp_key)
        for j in range(6):
            q: dict = {
                "image_path": f"mock/{sp_key}/img_{j:03d}.jpg",
                "species_key": sp_key,
            }
            if coord is not None and j != 5:  # j==5 → sin GPS (ruta region=None)
                q["lat"], q["lon"] = coord
            queries.append(q)

    embedder = FakeEmbedder()
    results = run_ablation(
        queries, index, metas, distribs,
        grid, lat_max, lon_min, eco_lookup, embedder
    )
    _print_table(results)
    print("\nMock completado OK.")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Evalua top-k retrieval con ablacion de pre-filtro geografico."
    )
    parser.add_argument(
        "--index", type=Path,
        help="Path a species_index_v1.bin (autocontenido: incluye metadatos en trailer).",
    )
    parser.add_argument(
        "--catalog", type=Path, help="Path a species_catalog_v1.json."
    )
    parser.add_argument(
        "--raster", type=Path, help="Path a meow_raster_v1.bin."
    )
    parser.add_argument(
        "--hierarchy", type=Path, help="Path a ecoregion_hierarchy_v1.json."
    )
    parser.add_argument(
        "--held-out", type=Path,
        help="JSONL con queries (image_path, species_key, lat, lon).",
    )
    parser.add_argument(
        "--embedder",
        choices=["openclip", "fake"],
        default="openclip",
        help="Embedder (default: openclip).",
    )
    parser.add_argument(
        "--mock",
        action="store_true",
        help="Ejecutar ablacion sobre datos sinteticos (no requiere imagenes).",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("output"),
        help="Directorio de artefactos en modo --mock (default: ./output).",
    )
    parser.add_argument(
        "--load-ckpt",
        type=str,
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
        _mock_eval(args.output_dir)
        return

    # Modo real: todos los paths son obligatorios (--meta ya no existe)
    for attr, name in [
        ("index", "--index"), ("catalog", "--catalog"),
        ("raster", "--raster"), ("hierarchy", "--hierarchy"), ("held_out", "--held-out"),
    ]:
        if getattr(args, attr) is None:
            parser.error(f"{name} es obligatorio en modo real.")

    index, metas = _load_index(args.index)
    distribs     = _load_catalog(args.catalog)
    grid, lat_max, lon_min = _load_raster(args.raster)
    hierarchy    = _load_hierarchy(args.hierarchy)
    eco_lookup   = _build_eco_lookup(hierarchy)
    _enrich_distribs(distribs, eco_lookup)

    queries: list[dict] = []
    with open(args.held_out, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                queries.append(json.loads(line))
    print(f"Queries cargadas: {len(queries)}")

    import sys
    sys.path.insert(0, str(Path(__file__).parent))
    if args.embedder == "fake":
        from embedder import FakeEmbedder
        embedder: Any = FakeEmbedder()
    else:
        from embedder import OpenCLIPEmbedder, DEFAULT_ENCODER
        embedder = OpenCLIPEmbedder(
            ckpt_path=args.load_ckpt, encoder=args.encoder or DEFAULT_ENCODER
        )

    results = run_ablation(
        queries, index, metas, distribs,
        grid, lat_max, lon_min, eco_lookup, embedder
    )
    _print_table(results)


if __name__ == "__main__":
    main()
