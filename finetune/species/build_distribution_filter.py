"""
build_distribution_filter.py — Distribución biogeográfica MEOW por taxón.

DESCRIPCIÓN
-----------
Por cada taxón en taxon_list.yaml, determina el conjunto de ecoregiones MEOW
(Marine Ecoregions of the World) donde la especie aparece documentada.

FUENTES DE DATOS (en orden de prioridad)
-----------------------------------------
1. OBIS (Ocean Biodiversity Information System) — https://api.obis.org
   Licencia: CC BY 4.0. Primario para fauna marina.
   API doc: https://api.obis.org/#/Occurrence/get_occurrence
   Uso: GET https://api.obis.org/v3/occurrence?scientificname=<name>&size=500

2. GBIF (Global Biodiversity Information Facility) — https://api.gbif.org
   Licencia: CC BY 4.0. Secundario/complementario.
   API doc: https://api.gbif.org/v1/occurrence/search
   Uso: GET https://api.gbif.org/v1/occurrence/search?scientificName=<name>&limit=300

3. AquaMaps (rangos modelados) — https://www.aquamaps.org
   Licencia: CC BY-NC. Rellena huecos donde OBIS/GBIF tienen escasos registros.
   NOTA: uso no-comercial; revisar licencia antes de distribución comercial.

MEOW (Marine Ecoregions of the World)
---------------------------------------
Spalding et al. (2007) "Marine Ecoregions of the World: A Bioregionalization of
Coastal and Shelf Areas." BioScience 57(7):573-583.
Datos vectoriales: https://www.marineregions.org/sources.php#meow
Licencia: The Nature Conservancy (TNC) — libre uso con atribución.
Shapefile: 'MEOW_FINAL.shp' descargable desde la URL anterior.

El cruce de ocurrencias con MEOW requiere el shapefile local.
En modo --online, el script descarga ocurrencias y hace point-in-polygon.
En modo --mock (default), asigna ecoregiones plausibles fijas para validar formato.

SALIDA: species_catalog_v1.json
-------------------------------
  {
    "version": 1,
    "sources": ["OBIS", "GBIF", "AquaMaps", "Manual"],
    "license_note": "...",
    "species": [
      {
        "speciesKey": "amphiprion_ocellaris",
        "aphiaId": 159559,
        "scientificName": "Amphiprion ocellaris",
        "commonNames": {"en": "Ocellaris clownfish", "es": "Pez payaso", ...},
        "ecoregions": [84, 85, 87],    // IDs MEOW donde documentada
        "cosmopolitan": false,
        "spritePath": "sprites/amphiprion_ocellaris.webp",
        "descriptions": {              // placeholder, rellenar con enriquecimiento
          "en": "", "es": "", "fr": "", "de": "", "it": "", "pt": ""
        },
        "iucnStatus": null             // "LC" | "NT" | "VU" | "EN" | "CR" | null
      },
      ...
    ]
  }

USO
---
  # Modo mock (no hace llamadas de red):
  python build_distribution_filter.py --taxon-list taxon_list.yaml --mock

  # Modo online (requiere red + shapefile MEOW local):
  python build_distribution_filter.py \\
      --taxon-list taxon_list.yaml \\
      --meow-shapefile /path/to/MEOW_FINAL.shp \\
      --online \\
      --output species_catalog_v1.json
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

import yaml  # type: ignore[import]

# Asignaciones mock plausibles: ecoregión MEOW fijas por especie
# Los IDs siguen el esquema MEOW (1-232). Ver ecoregion_hierarchy_v1.json para nombres.
_MOCK_ECOREGIONS: dict[str, list[int]] = {
    "posidonia_oceanica":    [22, 23, 24, 25],   # Mar Mediterráneo
    "caretta_caretta":       [],                  # cosmopolita → lista vacía (exento)
    "dicentrarchus_labrax":  [22, 23, 24, 25, 3, 4],
    "octopus_vulgaris":      [22, 23, 24, 25, 3, 4, 5],
    "sepia_officinalis":     [22, 23, 24, 25, 3, 4],
    "epinephelus_marginatus":[22, 23, 24, 25, 8, 9],
    "mullus_surmuletus":     [22, 23, 24, 25, 3],
    "paracentrotus_lividus": [22, 23, 24, 25, 3, 4],
    "pinna_nobilis":         [22, 23, 24, 25],
    "coris_julis":           [22, 23, 24, 25, 8, 9, 10],
    "chelonia_mydas":        [],                  # cosmopolita
    "mola_mola":             [],                  # cosmopolita
    "aurelia_aurita":        [],                  # cosmopolita
    "physalia_physalis":     [],                  # cosmopolita
    "rhincodon_typus":       [],                  # cosmopolita
    "tursiops_truncatus":    [],                  # cosmopolita
    "amphiprion_ocellaris":  [84, 85, 87, 88],   # Coral Triangle / Indo-Pacífico
    "pterois_miles":         [84, 85, 87, 88, 22],
    "thalassoma_hardwicke":  [84, 85, 86, 87],
    "chaetodon_lunula":      [84, 85, 86, 87, 88],
    "acanthaster_planci":    [84, 85, 86, 87, 88],
    "tridacna_gigas":        [84, 85, 87],
    "acropora_palmata":      [62, 63, 64],        # Caribe
    "hippocampus_guttulatus":[22, 23, 24, 25, 3, 4],
    "diodon_hystrix":        [62, 63, 64, 84, 85],
    "dermochelys_coriacea":  [],                  # cosmopolita
    "mobula_mobular":        [22, 23, 24, 25, 8, 9],
}

_IUCN_MOCK: dict[str, str | None] = {
    "posidonia_oceanica": "EN",
    "caretta_caretta": "VU",
    "pinna_nobilis": "CR",
    "rhincodon_typus": "EN",
    "epinephelus_marginatus": "VU",
    "hippocampus_guttulatus": "LC",
    "dermochelys_coriacea": "VU",
    "chelonia_mydas": "EN",
    "acropora_palmata": "CR",
    "tridacna_gigas": "VU",
    "mobula_mobular": "EN",
}

_ONLINE_DELAY_S = 0.5  # cortesía hacia las APIs


def _load_taxon_list(taxon_yaml: Path) -> list[dict[str, Any]]:
    with open(taxon_yaml, encoding="utf-8") as f:
        data = yaml.safe_load(f)
    return data["taxa"]


def _fetch_obis_occurrences(scientific_name: str, size: int = 500) -> list[dict]:
    """Descarga ocurrencias de OBIS (solo bajo --online)."""
    import urllib.request
    import urllib.parse

    url = (
        "https://api.obis.org/v3/occurrence"
        f"?scientificname={urllib.parse.quote(scientific_name)}&size={size}"
    )
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read())
    return data.get("results", [])


def _fetch_gbif_occurrences(scientific_name: str, limit: int = 300) -> list[dict]:
    """Descarga ocurrencias de GBIF (solo bajo --online)."""
    import urllib.request
    import urllib.parse

    url = (
        "https://api.gbif.org/v1/occurrence/search"
        f"?scientificName={urllib.parse.quote(scientific_name)}"
        f"&hasCoordinate=true&limit={limit}"
    )
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read())
    return data.get("results", [])


# Columna del shapefile MEOW con el ID canónico 1..232 (NO ECOREGION, que es
# el nombre; NI ECO_CODE, que es el código global de 5 dígitos 20001..25230).
_ECO_ID_COL = "ECO_CODE_X"


def _load_meow(meow_shp: Path):
    """Carga el shapefile MEOW una sola vez. Requiere geopandas."""
    try:
        import geopandas as gpd  # type: ignore[import]
    except ImportError:
        raise ImportError(
            "geopandas y shapely son necesarios para --online. "
            "Instala con: pip install geopandas shapely"
        )
    meow = gpd.read_file(meow_shp)
    if _ECO_ID_COL not in meow.columns:
        raise ValueError(
            f"Columna {_ECO_ID_COL} no encontrada en {meow_shp.name}. "
            f"Columnas: {list(meow.columns)}"
        )
    return meow


def _occurrence_points(occurrences: list[dict]) -> list[tuple[float, float]]:
    """Extrae pares (lon, lat) válidos de las ocurrencias OBIS/GBIF."""
    pts: list[tuple[float, float]] = []
    for occ in occurrences:
        lat = occ.get("decimalLatitude") or occ.get("decimallatitude")
        lon = occ.get("decimalLongitude") or occ.get("decimallongitude")
        if lat is None or lon is None:
            continue
        try:
            pts.append((float(lon), float(lat)))
        except (TypeError, ValueError):
            continue
    return pts


def _occurrences_to_ecoregions(occurrences: list[dict], meow) -> list[int]:
    """
    Cruza ocurrencias (lat/lon) con MEOW mediante spatial-join vectorizado.

    `meow` es el GeoDataFrame ya cargado (ver `_load_meow`). Devuelve la lista
    de ecoregionId únicos (ECO_CODE_X, 1..232) donde caen las ocurrencias.
    """
    import geopandas as gpd  # type: ignore[import]
    from shapely.geometry import Point  # type: ignore[import]

    pts = _occurrence_points(occurrences)
    if not pts:
        return []
    geoms = [Point(lon, lat) for lon, lat in pts]
    occ_gdf = gpd.GeoDataFrame(geometry=geoms, crs=meow.crs)
    joined = gpd.sjoin(occ_gdf, meow[[_ECO_ID_COL, "geometry"]], predicate="within")
    ids = joined[_ECO_ID_COL].dropna().astype(int).unique().tolist()
    return sorted(ids)


def _build_species_entry(
    taxon: dict[str, Any], ecoregions: list[int]
) -> dict[str, Any]:
    key = taxon["species_key"]
    return {
        "speciesKey": key,
        "aphiaId": taxon.get("aphia_id"),
        "scientificName": taxon["scientific_name"],
        "commonNames": taxon.get("common_names", {}),
        "ecoregions": ecoregions,
        "cosmopolitan": bool(taxon.get("cosmopolitan", False)),
        "spritePath": f"sprites/{key}.webp",
        "descriptions": {"en": "", "es": "", "fr": "", "de": "", "it": "", "pt": ""},
        "iucnStatus": _IUCN_MOCK.get(key),
    }


def build_catalog_mock(taxon_list: list[dict], output_path: Path) -> None:
    """Genera el catálogo con ecoregiones mock (sin red)."""
    species_entries = []
    for taxon in taxon_list:
        key = taxon["species_key"]
        ecoregions = _MOCK_ECOREGIONS.get(key, [])
        # Cosmopolitas: lista vacía = exento del filtro (manejado por cosmopolitan=true)
        species_entries.append(_build_species_entry(taxon, ecoregions))

    catalog = {
        "version": 1,
        "sources": ["mock"],
        "license_note": (
            "MEOW: TNC libre uso con atribución (Spalding et al. 2007). "
            "OBIS/GBIF: CC BY 4.0. "
            "AquaMaps: CC BY-NC (verificar antes de uso comercial)."
        ),
        "species": species_entries,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(catalog, f, indent=2, ensure_ascii=False)
    print(f"  Catálogo mock escrito: {output_path} ({len(species_entries)} taxones)")


def _resolve_taxon_ecoregions(taxon: dict, meow) -> list[int]:
    """Ecoregiones MEOW reales para un taxón vía OBIS+GBIF.

    Las especies cosmopolitas se marcan con lista vacía (exentas del filtro).
    Si la red falla o no hay ocurrencias, cae al mock plausible.
    """
    key = taxon["species_key"]
    if bool(taxon.get("cosmopolitan", False)):
        print(f"    INFO: {key} cosmopolita -> sin ecoregiones (exento)")
        return []

    sci = taxon["scientific_name"]
    print(f"  Consultando OBIS+GBIF: {sci}...")
    try:
        obis_occs = _fetch_obis_occurrences(sci)
        time.sleep(_ONLINE_DELAY_S)
        gbif_occs = _fetch_gbif_occurrences(sci)
        time.sleep(_ONLINE_DELAY_S)
    except Exception as exc:
        print(f"    WARN: error de red para {sci}: {exc}. Usando mock.")
        obis_occs, gbif_occs = [], []

    all_occs = obis_occs + gbif_occs
    if all_occs:
        ecoregions = _occurrences_to_ecoregions(all_occs, meow)
        if ecoregions:
            print(f"    OK: {key} -> {len(ecoregions)} ecoregiones ({len(all_occs)} occs)")
            return ecoregions
    print(f"    INFO: sin ocurrencias mapeadas -> usando mock para {key}")
    return _MOCK_ECOREGIONS.get(key, [])


def build_catalog_online(
    taxon_list: list[dict], meow_shp: Path, output_path: Path
) -> None:
    """
    Descarga ocurrencias de OBIS+GBIF y cruza con MEOW shapefile.
    Solo se ejecuta bajo --online.
    """
    meow = _load_meow(meow_shp)
    species_entries = [
        _build_species_entry(taxon, _resolve_taxon_ecoregions(taxon, meow))
        for taxon in taxon_list
    ]

    catalog = {
        "version": 1,
        "sources": ["OBIS", "GBIF", "AquaMaps"],
        "license_note": (
            "MEOW: TNC libre uso con atribución (Spalding et al. 2007). "
            "OBIS/GBIF: CC BY 4.0. "
            "AquaMaps: CC BY-NC (verificar antes de uso comercial)."
        ),
        "species": species_entries,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(catalog, f, indent=2, ensure_ascii=False)
    print(f"  Catálogo online escrito: {output_path} ({len(species_entries)} taxones)")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Genera species_catalog_v1.json con distribución MEOW por taxón."
    )
    parser.add_argument(
        "--taxon-list",
        type=Path,
        default=Path(__file__).parent / "taxon_list.yaml",
        help="YAML de taxones (default: taxon_list.yaml junto a este script).",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("output") / "species_catalog_v1.json",
        help="JSON de salida (default: output/species_catalog_v1.json).",
    )
    parser.add_argument(
        "--meow-shapefile",
        type=Path,
        default=None,
        help=(
            "Path al shapefile MEOW_FINAL.shp. "
            "Descarga: https://www.marineregions.org/sources.php#meow"
        ),
    )
    parser.add_argument(
        "--online",
        action="store_true",
        help="Descargar ocurrencias de OBIS+GBIF (requiere red y --meow-shapefile).",
    )
    parser.add_argument(
        "--mock",
        action="store_true",
        help="Usar ecoregiones plausibles fijas sin red (default si no se pasa --online).",
    )
    args = parser.parse_args()

    taxon_list = _load_taxon_list(args.taxon_list)
    print(f"Taxones cargados: {len(taxon_list)}")

    if args.online and not args.mock:
        if args.meow_shapefile is None:
            parser.error("--online requiere --meow-shapefile.")
        build_catalog_online(taxon_list, args.meow_shapefile, args.output)
    else:
        build_catalog_mock(taxon_list, args.output)


if __name__ == "__main__":
    main()
