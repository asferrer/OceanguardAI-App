# M8 — Evaluación & calidad de retrieval (BioDex RAG visual)

Milestone M8 del track BioDex (`feature/biodex-species-rag`). Documenta la
evaluación de calidad del retrieval visual + la ablación del pre-filtro
biogeográfico, los umbrales de confianza calibrables, y el plan de medición
de latencia on-device.

## Escala 203 especies (expansión Mediterránea, 2026-05-27)

Tras ampliar a **203 especies** (27 semilla + 176 mediterráneas), índice 1218
prototipos sobre 5788 imágenes, split 70/30 (4054 train / 1734 held-out):

| Modo | Top-1 | Top-5 | Cand. | FDR |
|------|-------|-------|-------|-----|
| Coseno puro (sin geo) | **65.2 %** | **82.9 %** | 203 | 0 % |
| + soft geo-prior (coords reales en 1569/1734) | 57.8 % | 72.0 % | 181.2 | 1.9 % |
| + hard-filter STRICT | 57.9 % | 72.8 % | 163.4 | 2.9 % |

**Hallazgo: a esta escala el geo-prior NO ayuda (resta ~7 pts top-1).** Causas:
(1) catálogo **homogéneo mediterráneo** → casi todo "en-región", el filtro recorta
poco (−11%/−20% candidatos) y no desambigua confusores intra-región (eso es del
encoder); (2) distribuciones **OBIS/GBIF incompletas** → el soft-prior penaliza a
la especie verdadera cuando su rango registrado no incluye la ecoregión exacta de
la observación (de ahí FDR 2-3%). **Implicaciones:** el pre-filtro biogeográfico
aporta con catálogos **geográficamente diversos** (excluir otros océanos), no con
uno region-homogéneo; a esta escala la palanca de top-1 es el **encoder**
(fine-tune marino / BioCLIP). **TODO calibración:** rellenar distribuciones con
AquaMaps (rangos modelados) + prior más suave/condicionado a confianza, o usar
solo hard-filter a realm sin penalización fina. Nota: el modo "OFF" del script
aplica el soft-prior en `_search` si la query trae coords; el baseline sin-geo
puro es la fila de coseno puro (held-out sin coords).

## Fine-tune marino del encoder + cuantización int8 (2026-05-27)

### Deliverable 1 — Fine-tune del visual tower (`train_encoder.py`)

**Enfoque.** Partial-unfreeze de los últimos 4 bloques del transformer +
`ln_post` + `proj` del visual tower OpenCLIP ViT-B/32 (28.75M de 87.85M params
entrenables), objetivo **Supervised Contrastive (SupCon)** sobre embeddings
L2-norm con sampler P-K (P especies × K imágenes/batch → positivos garantizados).
Se eligió partial-unfreeze sobre LoRA porque el attention de open_clip usa
`nn.MultiheadAttention` con `in_proj_weight` empaquetado que PEFT no inyecta
limpiamente; partial-unfreeze mantiene la arquitectura intacta y el ONNX se
exporta sin merge ni cirugía de tensores (mismo contrato que el zero-shot).

**Datos.** SOLO `images_train` (4161 imgs, mismo split determinista seed 42 que
`make_holdout_split.py`). El held-out de 1734 queries NUNCA se toca en training;
se usa solo en `eval_retrieval.py`. Validación interna: split 90/10 estratificado
por especie dentro de train (≠ held-out).

**Hiperparámetros (full run):** `--unfreeze-blocks 4 --epochs 12 --batch-p 16
--batch-k 4 --lr 1e-5 --weight-decay 1e-4 --temp 0.07 --early-stop-patience 3`,
AdamW, grad-clip 1.0, early-stop por val-top1 interno.

**Comando full run:**
```bash
cd finetune/species
python train_encoder.py --images-dir images_train \
    --out-ckpt output/clip_vitb32_ft.pt --export-onnx output/clip_vitb32_ft.onnx \
    --unfreeze-blocks 4 --epochs 12 --batch-p 16 --batch-k 4 \
    --lr 1e-5 --temp 0.07 --early-stop-patience 3
# Rebuild del índice con el encoder afinado + re-eval:
python build_reference_bank.py --images-dir images_train --output-dir output_ft \
    --k 6 --embedder openclip --load-ckpt output/clip_vitb32_ft.pt
python eval_retrieval.py --index output_ft/species_index_v1.bin \
    --catalog output/species_catalog_v1.json --raster output/meow_raster_v1.bin \
    --hierarchy output/ecoregion_hierarchy_v1.json --held-out held_out.jsonl \
    --embedder openclip --load-ckpt output/clip_vitb32_ft.pt
```

**Retrieval held-out (1734 queries, modo OFF = coseno puro), fine-tuned vs baseline:**

| Encoder | Top-1 | Top-5 |
|---------|-------|-------|
| Zero-shot OpenCLIP ViT-B/32 (baseline) | 65.2 % | 82.9 % |
| **Fine-tuned (SupCon, 4 bloques)** | **70.1 %** | **88.2 %** |
| Δ | **+4.9 pts** | **+5.3 pts** |

El fine-tune **supera el baseline** en el held-out de 1734 queries (imágenes que el
encoder NUNCA vio: split determinista seed 42, `images_train` ⊥ held-out). Señal
interna coherente: val 90/10 top-1 0.484 (pre) → **0.586** (época 2, pico). La
curva de val peakea en la época 2 y decae después (época 3 = 0.564), por eso el
early-stop selecciona el ckpt de la época 2. Pasada efectiva: ~2-4 épocas reales
(cada época ~3 min en RTX 5090; el cuello de botella es el re-embedding del train
en cada eval interno, no el forward/backward). El ckpt se guarda en cada mejora
(`save-on-best`), así que el mejor sobrevive a interrupciones.

**Honestidad sobre la escala del run.** Esta es una pasada **reducida pero real**
(early-stop en época 2). El comando full run (12 épocas, early-stop patience 3)
está arriba; a esta escala de datos (4161 imgs, ~20/especie) el riesgo es
overfit más que underfit, y la val interna ya decae tras la época 2 → más épocas
con estos hiperparámetros no ayudarían sin más datos o augmentación más fuerte.
Para exprimir más: subir augmentación, bajar `lr`, o añadir un término ArcFace.

### Deliverable 2 — Cuantización int8 dinámica (`quantize_int8.py`)

Cuantización dinámica de pesos a int8 vía `onnxruntime.quantization.quantize_dynamic`
(`QuantType.QInt8`, `per_channel=True`). Comando:
```bash
python quantize_int8.py --in output/clip_vitb32.onnx \
    --out output/clip_vitb32_int8.onnx --images-dir images_train --n-images 64
```

| Métrica | Resultado |
|---------|-----------|
| Tamaño | 352 MB fp32 → **89.4 MB int8** (25 %, ~4×) |
| Carga con `ORT_ENABLE_ALL` (= ORT Mobile) | **OK** (fp16 fallaba aquí) |
| Coseno int8 vs fp32 (64 imgs reales) | media **0.981**, min **0.956**, p05 0.963 |
| Latencia CPU batch=1 | 28.8 ms |

**Lectura honesta.** A diferencia de fp16, el int8 **sí carga** bajo las
optimizaciones por defecto de ORT (la fusión `SimplifiedLayerNormFusion` no se
dispara porque el quant dinámico usa `MatMulInteger`/`DynamicQuantizeLinear` en
vez de Cast alrededor de LayerNorm). PERO el coseno cae por debajo del gate de
0.99 (min 0.956): un ViT de embeddings es sensible al ruido de cuantización de
pesos. **Recomendación: desplegar fp32** mientras no se valide que esa caída de
coseno no degrada el top-1/top-5 del retrieval en el held-out real (medición
pendiente). El script y el artefacto quedan listos por si el parent decide medir
ese impacto o aceptar el trade-off tamaño/precisión.

## BioCLIP base encoder (2026-05-28) — domain-specific wins

Tras observar que el fine-tune del encoder es la palanca real a esta escala (la
sección anterior cierra con "+4.9 pts top-1" y la nota *"la palanca de top-1 es
el encoder (fine-tune marino / BioCLIP)"*), se cambia el encoder base de
**OpenCLIP ViT-B/32 LAION-2B** (genérico, web-scraped) a **BioCLIP v1 ViT-B/16
TreeOfLife-10M** (Stevens et al. 2024). BioCLIP está pre-entrenado contrastivamente
sobre 10 M de imágenes de organismos con taxonomía estructurada (iNat + EOL +
BIOSCAN), exactamente el dominio del catálogo BioDex.

Mismo contrato I/O que el OpenCLIP actual — pixel_values [b,3,224,224] →
image_features [b,512] L2-norm — por lo que es **drop-in** en
`OnnxSpeciesEmbedder.kt`. Implementado parametrizando `embedder.ENCODERS` (registro
con `{model, pretrained}` por encoder) + flag `--encoder` propagado a
`train_encoder.py`, `build_reference_bank.py` y `eval_retrieval.py`. El ckpt
guarda `encoder_name` y `_load_finetuned` aborta en mismatch (e.g. mezclar pesos
B/16 sobre arquitectura B/32) para evitar errores opacos.

**Retrieval held-out (1734 queries, modo OFF = coseno puro), 203 especies, mismo
banco k=6 prototipos. Comparativa contra los dos puntos previos:**

| Encoder | Top-1 | Top-5 |
|---------|-------|-------|
| OpenCLIP ViT-B/32 zero-shot (baseline original) | 65.2 % | 82.9 % |
| OpenCLIP ViT-B/32 fine-tuned (SupCon, 4 bloques) — deploy hoy | 70.1 % | 88.2 % |
| **BioCLIP ViT-B/16 zero-shot — sin entrenar nada** | **80.9 %** | **92.7 %** |
| Δ vs OpenCLIP zero-shot | **+15.7 pts** | **+9.8 pts** |
| Δ vs OpenCLIP fine-tuned | **+10.8 pts** | **+4.5 pts** |

BioCLIP zero-shot **supera al fine-tune actual por +10.8 pts top-1 sin entrenar
nada**, confirmando la hipótesis: a esta escala (200 sp / ~20 img/sp) el techo
del encoder genérico está pegado al suelo del encoder domain-specific. El
fine-tune sobre OpenCLIP movía 4.9 pts hacia ese techo; cambiar el encoder
salta 15.7 pts de golpe.

**Fine-tune sobre BioCLIP — abortado por TDR en RTX 5090.** El intento de aplicar
la misma receta SupCon a BioCLIP (`unfreeze 4, epochs 12, P=16 K=4, lr 1e-5,
temp 0.07`) muere en el primer step de train+backward — Windows TDR (Timeout
Detection and Recovery) resetea el driver de display: combo bleeding-edge **RTX
5090 (Blackwell) + driver 596.36 + CUDA 13.2 + PyTorch 2.7+cu128** + kernel CUDA
del primer backward por encima del `TdrDelay` por defecto (2s). El zero-shot
funciona porque solo hay forward + `no_grad`.

**Decisión:** desplegar BioCLIP zero-shot (80.9/92.7) y dejar el fine-tune para
una sesión posterior (subir `TdrDelay` o reducir P×K + autocast fp16). Ganancia
marginal esperada del FT (+2-4 pts dado que ya estamos cerca del techo) no
justifica relanzar con riesgo de display crash hoy.

**Comando reproducible (zero-shot, sin entrenamiento, ~3 min en RTX 5090, banco;
~5 min eval; export ONNX en CPU para evitar TDR):**
```bash
cd finetune/species
# Banco con BioCLIP zero-shot
python build_reference_bank.py --images-dir images_train \
    --output-dir output_bioclip_zs --k 6 --encoder bioclip-b16-tol10m
# Eval held-out 1734
python eval_retrieval.py --index output_bioclip_zs/species_index_v1.bin \
    --catalog output/species_catalog_v1.json --raster output/meow_raster_v1.bin \
    --hierarchy output/ecoregion_hierarchy_v1.json --held-out held_out.jsonl \
    --embedder openclip --encoder bioclip-b16-tol10m
# Export ONNX para Android (CPU)
CUDA_VISIBLE_DEVICES="" python embedder.py \
    --encoder bioclip-b16-tol10m --export-onnx output/clip_bioclip_b16.onnx
```

**Artefactos generados:**

| Fichero | Tamaño | Contrato |
|---------|--------|----------|
| `output/clip_bioclip_b16.onnx` | 345 MB fp32 | `pixel_values [b,3,224,224]` → `image_features [b,512]` L2-norm, opset 14 |
| `output_bioclip_zs/species_index_v1.bin` | 1.37 MB | 1218 vectores (203 sp × k=6), header SPEX + float16 + trailer JSONL — formato idéntico al actual |

Paridad torch ↔ ONNX verificada por `_bioclip_onnx_sanity.py`: coseno = 1.0000
sobre 3 imágenes reales (mismo preprocess open_clip).

**Implicación de latencia en device.** BioCLIP es ViT-B/16 vs OpenCLIP ViT-B/32
→ 4× patches → ~4× FLOPs en attention. Latencia esperada en Exynos 2200 (S22)
NNAPI EP: **encode ~80-130 ms/crop** (vs ~30-50 ms B/32). Sigue siendo
single-shot aceptable; cold start sin prewarm puede subir a ~1.5-2 s. El prewarm
ya implementado en `OnnxSpeciesEmbedder.prewarm()` lo absorbe.

**Próximos pasos pendientes** (no hechos en esta sesión):
1. Publicar `clip_bioclip_b16.onnx` + `species_index_v1.bin` (BioCLIP) a HF
   (`asferrer/oceanguard-biodex`) como versión `v2` o renombrando los actuales.
2. Actualizar el manifest descargable de la app para que apunte a los nuevos
   artefactos.
3. Verificar en S22: el `OnnxSpeciesEmbedder.kt` no necesita cambios (contrato
   I/O idéntico); solo cambia el binario descargado.
4. Re-medir latencia real en device tras prewarm.

## ✅ Resultados REALES (OpenCLIP ViT-B/32, 2026-05-27) — set semilla 27 especies

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
