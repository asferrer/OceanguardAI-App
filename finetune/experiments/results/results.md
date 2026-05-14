# OceanGuard AI — Grid de 9 Experimentos (Gemma 4 E2B LoRA)

Base mAP@0.5: **0.0924**

Best: **exp10_real_full** mAP@0.5=0.3253  Δ=+0.2329

## Resumen

| key | mAP@0.5 | mAP@0.5:0.95 | JSON-validity | n_preds | latency(s) | status |
|---|---|---|---|---|---|---|
| base | 0.0924 | 0.0000 | 1.000 | 47 | 1.96 | ok |
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
| exp11_real_synth_full | — | — | — | — | — | fail |

## Per-class mAP@0.5

| key | Bottle | Can | Fishing_Net | Glove | Mask | Metal_Debris | Plastic_Debris | Tire |
|---|---|---|---|---|---|---|---|---|
| base | 0.045 | 0.000 | 0.091 | 0.309 | 0.091 | 0.000 | 0.112 | 0.091 |
| exp01_synth100 | 0.020 | 0.000 | 0.156 | 0.327 | 0.091 | 0.000 | 0.393 | 0.061 |
| exp07_real100 | 0.023 | 0.000 | 0.145 | 0.322 | 0.091 | 0.000 | 0.418 | 0.191 |
| exp10_real_full | 0.312 | 0.061 | 0.448 | 0.463 | 0.308 | 0.000 | 0.608 | 0.403 |
