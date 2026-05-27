"""
build_meow_raster.py — Genera los assets on-device de ecoregiones MEOW.

DESCRIPCIÓN
-----------
Crea dos artefactos para el `MarineRegionResolver` de Android:

  meow_raster_v1.bin          — Grid global de ecoregiones (ver FORMATO abajo)
  ecoregion_hierarchy_v1.json — Lista de ecoregiones con jerarquía realm/province

FUENTE DE DATOS
---------------
Shapefile MEOW (Marine Ecoregions of the World):
  Spalding et al. (2007). "Marine Ecoregions of the World: A Bioregionalization
  of Coastal and Shelf Areas." BioScience 57(7):573-583.
  Descarga: https://www.marineregions.org/sources.php#meow
    → "Marine Ecoregions of the World (MEOW)" → MEOW_FINAL.shp (ZIP ~6 MB)
  Licencia: The Nature Conservancy (TNC), libre uso con atribución.

FORMATO BINARIO meow_raster_v1.bin
------------------------------------
  Little-endian. Lector Kotlin: `ByteBuffer.order(ByteOrder.LITTLE_ENDIAN)`.
  Resolución fija: 0.25 grados (implícita, no almacenada en el header).

  Sección  Offset  Size       Type    Campo
  Header   0       4          uint32  magic = 0x4D455257  ('MERW')
  Header   4       4          uint32  version = 1
  Header   8       4          uint32  nLat = 720   (filas: norte +90 → sur -90)
  Header   12      4          uint32  nLon = 1440  (columnas: oeste -180 → este +180)
  Header   16      16         bytes   reserved (ceros)
  Grid     32      nLat*nLon*2  int16   ecoregionId, little-endian, row-major

  Tamaño total: 32 + 720*1440*2 = 2,073,632 bytes

  Celda (row, col) para lat/lon dado (resolución = 0.25°):
    row = floor((90.0 - lat) / 0.25)        coerced a [0, 719]
    col = floor((lon + 180.0) / 0.25)       coerced a [0, 1439]
    byte_offset = 32 + (row * 1440 + col) * 2

  Valores int16:
     0   = tierra / agua dulce (no marino)
    -1   = océano abierto sin ecoregión MEOW asignada
    1–232 = ecoregionId MEOW

  Si la celda es 0 (tierra costera), buscar en anillo de radio creciente
  hasta encontrar celda con valor != 0.

FORMATO ecoregion_hierarchy_v1.json
-------------------------------------
  Array JSON de objetos, uno por ecoregión documentada:
  [
    {
      "ecoregionId": 22,
      "provinceId":  4,
      "realmId":     2,
      "ecoregionName": "Adriatic Sea",
      "provinceName":  "Mediterranean Sea",
      "realmName":     "Temperate Northern Atlantic"
    },
    ...
  ]

  IDs de realms MEOW (12):
    1=Arctic, 2=Temperate Northern Atlantic, 3=Tropical Atlantic,
    4=Temperate South America, 5=Temperate Southern Africa,
    6=Tropical Indo-Pacific, 7=Temperate Australasia,
    8=Southern Ocean, 9=Temperate Northern Pacific,
    10=Eastern Indo-Pacific, 11=Central Indo-Pacific,
    12=Western Indo-Pacific
  (Verificar IDs contra el shapefile MEOW real.)

USO
---
  # Modo mock (sin shapefile, valida formato y tamaño exacto):
  python build_meow_raster.py --mock --output-dir ./output

  # Con shapefile real:
  python build_meow_raster.py \\
      --shapefile /path/to/MEOW_FINAL.shp \\
      --output-dir ./output
"""

from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path

import numpy as np

MAGIC_RASTER = 0x4D455257  # 'MERW'
VERSION = 1
CELL_SIZE = 0.25           # grados, implícito (no en el header)
N_LAT = int(180.0 / CELL_SIZE)   # 720
N_LON = int(360.0 / CELL_SIZE)   # 1440
HEADER_SIZE = 32           # bytes

# ---------------------------------------------------------------------------
# Jerarquía MEOW condensada (subset representativo para mock y referencia)
# Para jerarquía completa (232 ecoregiones): leer del shapefile MEOW.
# Fuente: Spalding et al. 2007, Supplementary Material.
# ---------------------------------------------------------------------------

# (ecoregionId, provinceId, realmId, ecoregionName, provinceName, realmName)
_ECO_ROWS: list[tuple[int, int, int, str, str, str]] = [
    (3,  3,  2, "Southern North Sea",      "North Sea",           "Temperate Northern Atlantic"),
    (4,  3,  2, "Northern North Sea",      "North Sea",           "Temperate Northern Atlantic"),
    (5,  5,  2, "Saharan Upwelling",       "Lusitanian",          "Temperate Northern Atlantic"),
    (22, 4,  2, "Adriatic Sea",            "Mediterranean Sea",   "Temperate Northern Atlantic"),
    (23, 4,  2, "Aegean Sea",              "Mediterranean Sea",   "Temperate Northern Atlantic"),
    (24, 4,  2, "Levantine Sea",           "Mediterranean Sea",   "Temperate Northern Atlantic"),
    (25, 4,  2, "Western Mediterranean",   "Mediterranean Sea",   "Temperate Northern Atlantic"),
    (62, 26, 3, "Greater Antilles",        "Caribbean",           "Tropical Atlantic"),
    (63, 26, 3, "Lesser Antilles",         "Caribbean",           "Tropical Atlantic"),
    (64, 26, 3, "Southern Caribbean",      "Caribbean",           "Tropical Atlantic"),
    (84, 19, 6, "Sulawesi Sea",            "Coral Triangle",      "Tropical Indo-Pacific"),
    (85, 19, 6, "Banda Sea",               "Coral Triangle",      "Tropical Indo-Pacific"),
    (86, 19, 6, "Timor Sea",               "Coral Triangle",      "Tropical Indo-Pacific"),
    (87, 19, 6, "Sulu Sea",                "Coral Triangle",      "Tropical Indo-Pacific"),
    (88, 19, 6, "Bismarck Sea",            "Coral Triangle",      "Tropical Indo-Pacific"),
]


def _build_hierarchy_array() -> list[dict]:
    return [
        {
            "ecoregionId":  eco_id,
            "provinceId":   prov_id,
            "realmId":      realm_id,
            "ecoregionName": eco_name,
            "provinceName":  prov_name,
            "realmName":     realm_name,
        }
        for eco_id, prov_id, realm_id, eco_name, prov_name, realm_name in _ECO_ROWS
    ]


def _write_header(f, n_lat: int, n_lon: int) -> None:
    """Escribe el header de 32 bytes: 4×uint32 + 16 bytes reserved."""
    header = struct.pack("<IIII", MAGIC_RASTER, VERSION, n_lat, n_lon) + b"\x00" * 16
    assert len(header) == HEADER_SIZE
    f.write(header)


def _write_hierarchy(output_dir: Path, hier: list[dict] | None = None) -> Path:
    """Escribe ecoregion_hierarchy_v1.json como array plano de objetos.

    Si `hier` es None usa el subset mock (`_build_hierarchy_array`); en modo
    shapefile se pasa el array completo de 232 ecoregiones MEOW.
    """
    if hier is None:
        hier = _build_hierarchy_array()
    path = output_dir / "ecoregion_hierarchy_v1.json"
    with open(path, "w", encoding="utf-8") as f:
        json.dump(hier, f, indent=2, ensure_ascii=False)
    return path


def build_mock_raster(output_dir: Path) -> tuple[Path, Path]:
    """
    Crea un raster sintetico 720x1440 con ecoregiones plausibles:
      - Mediterraneo (lat 30-46, lon -5 a 37):    ecoregionId 25
      - Adriatico/Egeo (lat 32-46, lon 13 a 37):  ecoregionId 22
      - Coral Triangle (lat -10 a 10, lon 115-135): ecoregionId 84
      - Caribe (lat 10-25, lon -85 a -60):         ecoregionId 62
      - Resto marino: -1 (oceano abierto sin MEOW)
      - Tierra: 0 (aproximado, sin shapefile real)

    Tamaño garantizado: 32 + 720*1440*2 = 2,073,632 bytes.
    """
    grid = np.full((N_LAT, N_LON), -1, dtype=np.int16)

    def _fill(lat_lo: float, lat_hi: float, lon_lo: float, lon_hi: float, eco: int) -> None:
        r_lo = int((90.0 - lat_hi) / CELL_SIZE)
        r_hi = int((90.0 - lat_lo) / CELL_SIZE)
        c_lo = int((lon_lo + 180.0) / CELL_SIZE)
        c_hi = int((lon_hi + 180.0) / CELL_SIZE)
        r_lo, r_hi = max(0, r_lo), min(N_LAT, r_hi)
        c_lo, c_hi = max(0, c_lo), min(N_LON, c_hi)
        grid[r_lo:r_hi, c_lo:c_hi] = eco

    _fill(30, 46, -5, 37, 25)      # Mediterraneo Occidental
    _fill(32, 46, 13, 37, 22)      # Adriatico/Egeo
    _fill(-10, 10, 115, 135, 84)   # Coral Triangle
    _fill(10, 25, -85, -60, 62)    # Gran Caribe

    output_dir.mkdir(parents=True, exist_ok=True)
    bin_path = output_dir / "meow_raster_v1.bin"
    with open(bin_path, "wb") as f:
        _write_header(f, N_LAT, N_LON)
        f.write(grid.astype("<i2").tobytes())

    hier_path = _write_hierarchy(output_dir)
    return bin_path, hier_path


# Columna del shapefile MEOW con el ID canónico 1..232 (cabe en int16).
# OJO: ECO_CODE es el código global de 5 dígitos (20001..25230) y NO cabe en
# int16; ECO_CODE_X es el ID compacto 1..232 que exige el contrato binario.
_ECO_ID_COL = "ECO_CODE_X"
_PROV_ID_COL = "PROV_CODE"
_REALM_ID_COL = "RLM_CODE"


def _meow_hierarchy_from_gdf(meow) -> list[dict]:
    """Construye el array plano de las 232 ecoregiones MEOW desde el GeoDataFrame.

    ecoregionId = ECO_CODE_X (1..232), provinceId = PROV_CODE, realmId = RLM_CODE.
    """
    cols = [
        _ECO_ID_COL, "ECOREGION", _PROV_ID_COL, "PROVINCE", _REALM_ID_COL, "REALM"
    ]
    rows = (
        meow[cols]
        .dropna(subset=[_ECO_ID_COL])
        .drop_duplicates(subset=[_ECO_ID_COL])
        .sort_values(_ECO_ID_COL)
    )
    return [
        {
            "ecoregionId": int(r[_ECO_ID_COL]),
            "provinceId": int(r[_PROV_ID_COL]),
            "realmId": int(r[_REALM_ID_COL]),
            "ecoregionName": str(r["ECOREGION"]),
            "provinceName": str(r["PROVINCE"]),
            "realmName": str(r["REALM"]),
        }
        for _, r in rows.iterrows()
    ]


def _rasterize_meow(meow, land_shp: Path | None):
    """Rasteriza MEOW a grid 0.25° int16: 0=tierra, -1=océano abierto, 1..232=eco.

    La tierra se determina con un shapefile de costas (Natural Earth). El orden
    es: ecoregión primero, luego la tierra la sobrescribe a 0 (las celdas de
    plataforma marina perdidas por la malla gruesa las recupera el ring-search
    del resolver Kotlin). Sin máscara de tierra, las celdas no-ecoregión = -1.
    """
    import numpy as np
    import geopandas as gpd                       # type: ignore[import]
    from rasterio.transform import from_bounds    # type: ignore[import]
    from rasterio.features import rasterize       # type: ignore[import]

    transform = from_bounds(-180.0, -90.0, 180.0, 90.0, N_LON, N_LAT)
    eco_shapes = [
        (geom, int(code))
        for geom, code in zip(meow.geometry, meow[_ECO_ID_COL])
        if geom is not None and code == code  # descarta NaN
    ]
    eco = rasterize(
        eco_shapes, out_shape=(N_LAT, N_LON), transform=transform,
        fill=0, dtype="int32", all_touched=True,
    )
    grid = np.full((N_LAT, N_LON), -1, dtype=np.int16)
    grid[eco > 0] = eco[eco > 0].astype(np.int16)

    if land_shp is not None and land_shp.exists():
        land = gpd.read_file(land_shp)
        land_mask = rasterize(
            [(g, 1) for g in land.geometry if g is not None],
            out_shape=(N_LAT, N_LON), transform=transform, fill=0, dtype="uint8",
        )
        grid[land_mask == 1] = 0
    return grid


def build_from_shapefile(
    shp_path: Path, output_dir: Path, land_shp: Path | None = None
) -> tuple[Path, Path]:
    """
    Rasteriza el shapefile MEOW a grid 0.25 grados.
    Requiere: geopandas, rasterio, shapely.
    """
    try:
        import geopandas as gpd                       # type: ignore[import]
    except ImportError:
        raise ImportError("Instala: pip install geopandas rasterio shapely")

    meow = gpd.read_file(shp_path)
    if _ECO_ID_COL not in meow.columns:
        raise ValueError(
            f"Columna {_ECO_ID_COL} no encontrada en {shp_path.name}. "
            f"Columnas: {list(meow.columns)}"
        )
    grid = _rasterize_meow(meow, land_shp)

    output_dir.mkdir(parents=True, exist_ok=True)
    bin_path = output_dir / "meow_raster_v1.bin"
    with open(bin_path, "wb") as f:
        _write_header(f, N_LAT, N_LON)
        f.write(grid.astype("<i2").tobytes())

    hier_path = _write_hierarchy(output_dir, _meow_hierarchy_from_gdf(meow))
    return bin_path, hier_path


def verify_raster(bin_path: Path) -> None:
    """Valida header y tamaño exacto del raster."""
    with open(bin_path, "rb") as f:
        raw = f.read(HEADER_SIZE)

    magic, ver, n_lat, n_lon = struct.unpack_from("<IIII", raw, 0)
    assert magic == MAGIC_RASTER, f"Magic incorrecto: 0x{magic:08X}"
    assert ver == VERSION,        f"Version incorrecta: {ver}"
    assert raw[16:] == b"\x00" * 16, "Bytes reserved no son cero"

    expected = HEADER_SIZE + n_lat * n_lon * 2
    actual = bin_path.stat().st_size
    assert actual == expected, f"Tamano: esperado {expected}, real {actual}"
    print(
        f"  Verificado: {bin_path.name} — "
        f"{n_lat}x{n_lon} celdas, "
        f"header={HEADER_SIZE}B grid={n_lat*n_lon*2}B total={actual}B"
    )


def _dispatch_build(args) -> tuple[Path, Path]:
    """Elige modo mock vs shapefile real según los argumentos CLI."""
    if args.mock or args.shapefile is None:
        print("Modo MOCK: generando raster sintetico 720x1440...")
        return build_mock_raster(args.output_dir)
    print(f"Rasterizando shapefile: {args.shapefile}...")
    return build_from_shapefile(
        args.shapefile, args.output_dir, args.land_shapefile
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Genera meow_raster_v1.bin + ecoregion_hierarchy_v1.json."
    )
    parser.add_argument(
        "--shapefile",
        type=Path,
        default=None,
        help=(
            "Path al shapefile MEOW_FINAL.shp. "
            "Descarga: https://www.marineregions.org/sources.php#meow"
        ),
    )
    parser.add_argument(
        "--land-shapefile",
        type=Path,
        default=None,
        help=(
            "Path a shapefile de costas (Natural Earth ne_*m_land.shp) para "
            "marcar tierra=0. Sin él, las celdas no-ecoregión quedan a -1. "
            "Descarga: https://www.naturalearthdata.com (Physical → Land)."
        ),
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("output"),
        help="Directorio de salida (default: ./output).",
    )
    parser.add_argument(
        "--mock",
        action="store_true",
        help="Crear raster sintetico sin shapefile (para validar formato).",
    )
    args = parser.parse_args()

    bin_p, hier_p = _dispatch_build(args)
    verify_raster(bin_p)
    print(f"  Jerarquia: {hier_p.name}  ({hier_p.stat().st_size} bytes)")
    print(f"\nArtefactos escritos en {args.output_dir.resolve()}:")
    print(f"  {bin_p.name}  ({bin_p.stat().st_size:,} bytes)")
    print(f"  {hier_p.name}")


if __name__ == "__main__":
    main()
