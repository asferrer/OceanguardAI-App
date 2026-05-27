# M8 — Evaluación & calidad de retrieval (BioDex RAG visual)

Milestone M8 del track BioDex (`feature/biodex-species-rag`). Documenta la
evaluación de calidad del retrieval visual + la ablación del pre-filtro
biogeográfico, los umbrales de confianza calibrables, y el plan de medición
de latencia on-device.

## ✅ Resultados REALES (OpenCLIP ViT-B/32, 2026-05-27)

Eval con el **encoder OpenCLIP real exportado a ONNX** + banco de **547 imágenes
reales** (iNaturalist + GBIF, CC-BY/CC0) descargadas con
`download_reference_images.py`. Split held-out 70/30 determinista
(`make_holdout_split.py`): **383 train / 164 held-out**, índice k=6 prototipos.

| Modo | Top-1 | Top-5 | Cand. | FDR | N |
|------|-------|-------|-------|-----|---|
| OFF / BALANCED / STRICT | **84.1 %** | **98.2 %** | 27.0 | 0.0 % | 164 |

- **Supera el gate Fase 0 del plan** (objetivo top-1 ≥ 65 %, top-5 ≥ 85 %) →
  **GO** para el RAG visual, con OpenCLIP **zero-shot** (sin fine-tuning).
- Los 3 modos coinciden porque el held-out **no lleva lat/lon** (el scraper aún
  no captura coords) → `region=None` → todos caen a OFF. La ablación geográfica
  real (BALANCED/STRICT distintos) requiere coords en el held-out + ráster MEOW
  real (ver TODO). La mecánica del filtro ya está validada en mock (abajo).

Reproducir:
```bash
cd finetune/species
python download_reference_images.py --out images --per-species 30
python make_holdout_split.py --images images --train-out images_train \
    --holdout-jsonl held_out.jsonl --frac 0.3
python build_reference_bank.py --images-dir images_train --output-dir output_eval \
    --k 6 --embedder openclip
python eval_retrieval.py --index output_eval/species_index_v1.bin \
    --catalog output/species_catalog_v1.json --raster output/meow_raster_v1.bin \
    --hierarchy output/ecoregion_hierarchy_v1.json --held-out held_out.jsonl \
    --embedder openclip
```

---

> **Validación mock (mecánica del pre-filtro geográfico).** La sección de abajo
> corre en **modo mock** (`FakeEmbedder` hash). Sus números de accuracy NO son
> representativos (los reales son los de arriba); sirve para validar **el pipeline
> y la mecánica del pre-filtro geográfico end-to-end**.

> **Estado de los datos.** Esta evaluación corre en **modo mock** (datos 100 %
> sintéticos, `FakeEmbedder` basado en hash). Sirve para validar **el pipeline
> y la mecánica del pre-filtro geográfico end-to-end** sin assets reales. Los
> números de *accuracy* (top-1/top-5) **no son representativos**: dependen del
> encoder OpenCLIP exportado a ONNX y del banco de referencia real (ambos
> bloqueados por el fix de Windows Defender + curación de imágenes, ver
> [TODO swap a assets reales](#todo-swap-a-assets-reales)).

## Reproducir

```bash
cd finetune/species
python eval_retrieval.py --mock
```

Determinista (semillas fijas en `build_mock`, `FakeEmbedder` y el k-means
numpy). No requiere `torch`, `open_clip` ni imágenes — solo `numpy`.

## Ablación del pre-filtro biogeográfico (mock)

5 especies en el índice mock (`amphiprion_ocellaris`, `octopus_vulgaris`,
`caretta_caretta`, `aurelia_aurita`, `rhincodon_typus`), k=4 prototipos/especie,
30 queries (6/especie). Cada query recibe una lat/lon **en una ecorregión donde
la especie está documentada** (in-region); 1 de cada 6 va **sin GPS**
(`region=None` ≡ ruta OFF) para ejercer la degradación elegante.

| Modo      | Top-1 | Top-5  | Cand. medios | FDR  | N  |
|-----------|-------|--------|--------------|------|----|
| OFF       | 40.0% | 100.0% | 27.0         | 0.0% | 30 |
| BALANCED  | 40.0% | 100.0% | 17.8         | 0.0% | 30 |
| STRICT    | 40.0% | 100.0% | 17.8         | 0.0% | 30 |

`Cand.` = nº medio de candidatos tras el filtro · `FDR` = False Discard Rate
(especie verdadera excluida por geografía).

### Lectura de resultados

1. **FDR = 0 % en BALANCED (propiedad de seguridad del plan §2b).** El
   pre-filtro **nunca descarta una especie que vive en la región consultada**.
   Esta es la garantía clave del refinamiento geográfico: acota sin perder la
   verdad. Lo verifica directamente este mock (todas las queries son in-region o
   de especies cosmopolitas/sin-datos, exentas del filtro).
2. **Reducción del espacio de búsqueda: 27 → 17.8 candidatos (−34 %).** Sobre
   embeddings semánticos reales esto (a) **sube top-1** al eliminar confusores
   geográficamente imposibles y (b) **acelera** la búsqueda brute-force. El
   recall top-5 se mantiene al 100 % → la reducción no sacrifica cobertura.
3. **STRICT == BALANCED aquí** porque las distribuciones sintéticas no contienen
   confusores *in-realm / out-of-province*. Sobre datos reales, STRICT
   (hard-filter a provincia) intercambia mayor precisión por algo de FDR; ese
   trade-off solo emerge con held-out real.
4. **Top-1 = 40 % es artefacto de `FakeEmbedder`** (hash, no semántico), no una
   métrica real. El objetivo del plan sobre datos reales es **top-1 ≥ 65 %** y
   **top-5 ≥ 85 %** en el núcleo curado.

### Observaciones fuera de rango (out-of-range)

El mock usa solo queries in-region para aislar la propiedad FDR=0. En
producción, un avistamiento **fuera del rango conocido** (animal fotografiado
donde no está documentado) sí queda fuera de los candidatos a nivel realm; el
sistema **no lo pierde**: la relajación (realm → global) lo recupera y lo marca
`outOfRange=true`, convirtiéndolo en señal científica (posible extensión de
distribución) en vez de ocultarlo. Esto se valida con el held-out real, no con
el mock.

## Umbrales de confianza (τ / δ)

Fuente de verdad on-device: `SpeciesConfidence.kt`. Calibración Platt
`P(correcto | score) = σ(A·score + B)` sobre el coseno crudo.

| Parámetro      | Valor   | Sobre            | Efecto                                  |
|----------------|---------|------------------|-----------------------------------------|
| Platt `A`      | 10.0    | —                | pendiente sigmoide (**placeholder**)    |
| Platt `B`      | −3.5    | —                | sesgo sigmoide (**placeholder**)        |
| `τ_alta`       | 0.60    | confianza calib. | ≥ τ_alta **y** margen ≥ δ → `RAG_CORE`  |
| `τ_baja`       | 0.30    | confianza calib. | ≥ τ_baja → `RAG_TENTATIVE`              |
| `δ` (margen)   | 0.05    | coseno crudo     | margen rank1−rank2 mínimo para `CORE`   |
| `< τ_baja`     | —       | confianza calib. | desconocido → fallback VLM open-vocab   |

**Correspondencia eval ↔ app.** El eval (`eval_retrieval.py`) razona en
**coseno crudo** (`TAU_HIGH=0.35`, `TAU_LOW=0.20`); la app aplica los umbrales
sobre la **probabilidad calibrada** post-Platt. Con `A=10, B=−3.5`:

- coseno 0.40 → σ(0.5) ≈ **0.62** ≥ 0.60 → `RAG_CORE`
- coseno 0.35 → σ(0.0) = **0.50** → `RAG_TENTATIVE`
- coseno 0.20 → σ(−1.5) ≈ **0.18** < 0.30 → desconocido

Ambos lados son **placeholders coherentes**. Calibración fina pendiente de un
held-out real (Platt fit + barrido de τ/δ por curva ROC del gate de
desconocido), ver TODO.

## Latencia on-device (S22 Ultra · Exynos 2200)

| Etapa                         | Coste              | Estado                          |
|-------------------------------|--------------------|---------------------------------|
| Encoder ONNX (ViT-B/32, NNAPI)| ~10–30 ms / crop   | **estimado** (plan §1) — TODO medir |
| Match brute-force coseno      | < 1 ms             | acotado analíticamente          |
| Geo-resolución (ráster O(1))  | despreciable       | lookup directo + anillo si tierra |

- **Match:** producto punto de un vector 512-dim contra ≤ 1 600 prototipos
  (núcleo completo) = ~0.8 M MAC → sub-milisegundo en CPU; el pre-filtro lo
  reduce aún más (−34 % candidatos en mock).
- **Encoder:** la latencia real **está bloqueada** hasta exportar el encoder
  OpenCLIP a ONNX y desplegarlo en device (Defender bloquea el import de
  `torch`/`sklearn` que requiere la exportación; ver M0). Metodología de medida:
  patrón `LiteRTBenchmark.kt` / `LlamaCppBenchmark.kt` (p50/p95 sobre N crops).
- El camino demo (`SampleQueryEmbedder`) **no** mide coste de encoder real
  (genera embeddings deterministas, sin inferencia) → no representativo.

## TODO: swap a assets reales

Lo siguiente desbloquea métricas reales (gated por el fix de Defender +
curación de imágenes de referencia):

- [ ] **M0:** exportar OpenCLIP ViT-B/32 → ONNX (`embedder.py --export-onnx`)
      tras excluir el env de miniconda en Windows Defender.
- [ ] Construir banco de referencia real desde FathomNet/iNat
      (`build_reference_bank.py --images <dir>`).
- [ ] Rasterizar MEOW real (`build_meow_raster.py` con shapefile TNC) +
      distribución por especie vía OBIS/GBIF (`build_distribution_filter.py`).
- [ ] Held-out estratificado (~30–50 img/especie núcleo + set cola larga) con
      **lat/lon real por imagen** → `eval_retrieval.py --index … --held-out …`.
- [ ] Calibrar Platt + barrer τ/δ sobre el held-out (curva ROC del gate de
      desconocido, ECE/reliability) y actualizar `SpeciesConfidence.kt`.
- [ ] Medir p50/p95 encoder+match en S22 con el ONNX real.

## Nota de implementación

El k-means de los prototipos se migró de `sklearn.cluster.KMeans` a un k-means
**en numpy puro** (k-means++ + Lloyd, determinista por semilla) en
`build_reference_bank.py::_kmeans_numpy`. Motivo: el import de `scikit-learn`
(arrastra `scipy` + DLLs compilados) **se cuelga bajo Windows Defender**, lo que
bloqueaba `eval_retrieval.py --mock`. Para el `k` pequeño de los prototipos
(k≈4–8) el resultado es equivalente, sin dependencia pesada en el camino caliente.
