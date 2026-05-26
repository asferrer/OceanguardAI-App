# BioDex — Pipeline offline de identificación de especies marinas

Pipeline Python para construir los assets on-device del sistema de RAG visual
de BioDex (OceanguardAI). Mantiene el mismo estilo que `finetune/experiments/`.

## Flujo end-to-end

```
taxon_list.yaml
      │
      ├─► build_reference_bank.py ──── imágenes de referencia/especie
      │         └─► species_index_v1.bin   (índice vectorial + metadatos embebidos)
      │
      ├─► build_distribution_filter.py ─ OBIS / GBIF / mock
      │         └─► species_catalog_v1.json (distribución MEOW + metadatos)
      │
      ├─► build_meow_raster.py ──────── shapefile MEOW / mock
      │         └─► meow_raster_v1.bin      (grid 720×1440, lookup O(1))
      │         └─► ecoregion_hierarchy_v1.json (array plano realm/province/eco)
      │
      └─► eval_retrieval.py ─────────── held-out con lat/lon
                └─► tabla ablación OFF / BALANCED / STRICT
```

## Ejecución rápida (modo mock, sin datos reales)

```bash
cd finetune/species

# 1. Índice vectorial sintético (autocontenido, sin index_meta.json separado)
python build_reference_bank.py --mock --output-dir output

# 2. Catálogo de distribución MEOW (sin red)
python build_distribution_filter.py --mock --output output/species_catalog_v1.json

# 3. Ráster MEOW sintético
python build_meow_raster.py --mock --output-dir output

# 4. Evaluación ablación completa sobre datos sintéticos
python eval_retrieval.py --mock --output-dir output
```

## Ejecución con datos reales

```bash
# 1. Descargar shapefile MEOW desde:
#    https://www.marineregions.org/sources.php#meow
#    → MEOW_FINAL.shp (ZIP ~6 MB, TNC/libre uso con atribución)

# 2. Obtener imágenes de referencia por especie:
#    - FathomNet: https://fathomnet.org (CC-BY, marino/submarino)
#    - iNaturalist research-grade: https://www.gbif.org/dataset/50c9509d-22c7-4a22-a47d-8c48425ef4a7
#    Estructura esperada: images/{species_key}/{img.jpg} o images/{species_key}/{view_tag}/{img.jpg}

# 3. Construir artefactos reales
python build_reference_bank.py \
    --images-dir /path/to/reference_images \
    --output-dir output \
    --k 6 \
    --embedder openclip   # requiere: pip install open_clip_torch torch Pillow

python build_distribution_filter.py \
    --taxon-list taxon_list.yaml \
    --meow-shapefile /path/to/MEOW_FINAL.shp \
    --online \
    --output output/species_catalog_v1.json

python build_meow_raster.py \
    --shapefile /path/to/MEOW_FINAL.shp \
    --output-dir output

# 4. Evaluar con imágenes held-out (--meta ya NO existe; el índice es autocontenido)
# Formato held_out.jsonl: {"image_path":"/abs/path.jpg","species_key":"amphiprion_ocellaris","lat":9.5,"lon":119.3}
python eval_retrieval.py \
    --index output/species_index_v1.bin \
    --catalog output/species_catalog_v1.json \
    --raster output/meow_raster_v1.bin \
    --hierarchy output/ecoregion_hierarchy_v1.json \
    --held-out /path/to/held_out.jsonl \
    --embedder openclip
```

---

## Formatos binarios — Contrato con Android

### `species_index_v1.bin`

Fichero autocontenido (header + cuerpo + trailer JSONL embebido).
Lector Kotlin: `SpeciesReferenceIndex.kt`.

```
Sección   Offset        Size          Type      Campo
Header    0             4             uint32    magic = 0x53504558  ('SPEX')
Header    4             4             uint32    version = 1
Header    8             4             uint32    n  (número de prototipos)
Header    12            4             uint32    dim = 512
Header    16            16            bytes     reserved (ceros)
Cuerpo    32            n * 512 * 2   float16   vectores L2-norm, row-major, little-endian
Trailer   32+n*512*2    variable      UTF-8     n líneas JSON separadas por '\n'
```

Trailer — línea i (0-based), misma posición que fila i del cuerpo:
```json
{"speciesKey":"amphiprion_ocellaris","scientificName":"Amphiprion ocellaris","viewTag":"lateral","nRefs":42}
```

Tamaño total: `32 + n*512*2 + len(trailer_utf8_bytes)`

Lectura Kotlin (ByteBuffer little-endian):
```kotlin
val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
val magic   = buf.int           // 0x53504558
val version = buf.int           // 1
val n       = buf.int           // número de prototipos
val dim     = buf.int           // 512
buf.position(32)                // saltar reserved
val bodySize = n * dim * 2
val bodyBytes = ByteArray(bodySize).also { buf.get(it) }
// Leer trailer como UTF-8 desde offset 32 + bodySize
val trailerBytes = bytes.copyOfRange(32 + bodySize, bytes.size)
val lines = trailerBytes.toString(Charsets.UTF_8).trim().split('\n')
```

Búsqueda coseno (vectores L2-norm → producto punto):
```kotlin
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float =
    a.zip(b).sumOf { (x, y) -> (x * y).toDouble() }.toFloat()
```

### `meow_raster_v1.bin`

Grid global de ecoregiones para `MarineRegionResolver.kt`.
Resolución: **0.25° fija, implícita** (no almacenada en el header).

```
Sección  Offset  Size           Type    Campo
Header   0       4              uint32  magic = 0x4D455257  ('MERW')
Header   4       4              uint32  version = 1
Header   8       4              uint32  nLat = 720   (filas: norte +90 → sur -90)
Header   12      4              uint32  nLon = 1440  (columnas: oeste -180 → este +180)
Header   16      16             bytes   reserved (ceros)
Grid     32      720*1440*2     int16   ecoregionId, little-endian, row-major
```

Tamaño total: **2,073,632 bytes** (32 + 720×1440×2).

Valores int16:
- `0`    = tierra / agua dulce (no marino)
- `1–232` = ecoregionId MEOW
- `-1`   = océano abierto sin ecoregión MEOW asignada

Lookup Kotlin (resolución implícita 0.25°):
```kotlin
val CELL = 0.25f
val row = ((90f - lat) / CELL).toInt().coerceIn(0, nLat - 1)
val col = ((lon + 180f) / CELL).toInt().coerceIn(0, nLon - 1)
val ecoId = buf.getShort(32 + (row * nLon + col) * 2).toInt()
```

Si `ecoId == 0` (tierra costera), buscar en anillo de celdas vecinas hasta `ecoId != 0`.

### `ecoregion_hierarchy_v1.json`

Array JSON plano de objetos, uno por ecoregión documentada:
```json
[
  {
    "ecoregionId": 22,
    "provinceId":  4,
    "realmId":     2,
    "ecoregionName": "Adriatic Sea",
    "provinceName":  "Mediterranean Sea",
    "realmName":     "Temperate Northern Atlantic"
  }
]
```

---

## Fuentes de datos y licencias

| Fuente | Tipo | Licencia | URL |
|--------|------|----------|-----|
| MEOW (Spalding et al. 2007) | Shapefile ecoregiones | TNC, libre con atribución | https://www.marineregions.org/sources.php#meow |
| OBIS | Ocurrencias marinas | CC BY 4.0 | https://obis.org |
| GBIF | Ocurrencias biodiversidad | CC BY 4.0 | https://gbif.org |
| AquaMaps | Rangos modelados | CC BY-NC | https://aquamaps.org |
| FathomNet | Imágenes submarinas | CC-BY (por imagen) | https://fathomnet.org |
| OpenCLIP ViT-B/32 | Encoder visual | MIT | https://github.com/mlfoundations/open_clip |
| iNaturalist (research-grade) | Imágenes referencia | CC BY / CC BY-NC | https://www.inaturalist.org |

**Nota AquaMaps**: licencia CC BY-NC prohíbe uso comercial. Verificar antes de
incluir rangos AquaMaps en una app con monetización.

---

## TODOs pendientes (datos reales)

- [ ] Descargar shapefile MEOW y ejecutar `build_meow_raster.py` sin `--mock`
- [ ] Recopilar imágenes de referencia por especie (FathomNet + iNaturalist)
- [ ] Verificar y rellenar `aphia_id: null` en `taxon_list.yaml` (ver TODO inline)
- [ ] Ejecutar `build_distribution_filter.py --online` con shapefile real
- [ ] Obtener held-out FathomNet+iNat con lat/lon para `eval_retrieval.py` real
- [ ] Exportar encoder a ONNX: `python embedder.py --export-onnx output/clip_vitb32.onnx`
- [ ] Calibrar umbrales `TAU_HIGH/TAU_LOW` en `eval_retrieval.py` sobre datos reales
- [ ] Ampliar `taxon_list.yaml` a ~50-200 taxones (fase 3 del plan)
- [ ] Completar `ecoregion_hierarchy_v1.json` con las 232 ecoregiones MEOW completas
