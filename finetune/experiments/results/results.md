# OceanGuard AI — Grid de 9 Experimentos (Gemma 4 E2B LoRA)

Base mAP@0.5: **0.1067**

Best: **exp12_vision_lora** mAP@0.5=0.3256  Δ=+0.2189

## Resumen

| key | mAP@0.5 | mAP@0.5:0.95 | JSON-validity | n_preds | latency(s) | status |
|---|---|---|---|---|---|---|
| base | 0.1067 | 0.0000 | 0.995 | 168 | 1.88 | ok |
| exp01_synth100 | 0.1310 | 0.0000 | 1.000 | 80 | 4.68 | ok |
| exp02_synth100_real10 | — | — | — | — | — | fail |
| exp03_synth100_real25 | — | — | — | — | — | fail |
| exp04_synth100_real50 | — | — | — | — | — | fail |
| exp05_synth100_real75 | — | — | — | — | — | fail |
| exp06_synth100_real100 | — | — | — | — | — | fail |
| exp07_real100 | 0.1488 | 0.0000 | 1.000 | 90 | 5.49 | ok |
| exp08_real_synth_minor | — | — | — | — | — | fail |
| exp09_real_synth_prop | — | — | — | — | — | fail |
| exp10_real_full | 0.3253 | 0.0000 | 0.945 | 292 | 5.43 | ok |
| exp11_real_synth_full | 0.2997 | 0.0000 | 0.870 | 274 | 5.21 | ok |
| exp12_vision_lora | 0.3256 | 0.0000 | 0.885 | 298 | 7.19 | ok |

## Per-class mAP@0.5

| key | Bottle | Can | Fishing_Net | Glove | Mask | Metal_Debris | Plastic_Debris | Tire |
|---|---|---|---|---|---|---|---|---|
| base | 0.132 | 0.000 | 0.142 | 0.233 | 0.091 | 0.000 | 0.121 | 0.133 |
| exp01_synth100 | 0.020 | 0.000 | 0.156 | 0.327 | 0.091 | 0.000 | 0.393 | 0.061 |
| exp07_real100 | 0.023 | 0.000 | 0.145 | 0.322 | 0.091 | 0.000 | 0.418 | 0.191 |
| exp10_real_full | 0.312 | 0.061 | 0.448 | 0.463 | 0.308 | 0.000 | 0.608 | 0.403 |
| exp11_real_synth_full | 0.233 | 0.273 | 0.513 | 0.376 | 0.278 | 0.000 | 0.499 | 0.225 |
| exp12_vision_lora | 0.272 | 0.091 | 0.575 | 0.498 | 0.319 | 0.000 | 0.489 | 0.362 |
