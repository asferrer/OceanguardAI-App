# OceanGuard AI — Grid de 9 Experimentos (Gemma 4 E2B LoRA)

Base mAP@0.5: **0.0924**

Best: **exp01_synth100** mAP@0.5=0.1310  Δ=+0.0385

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
| exp07_real100 | — | — | — | — | — | fail |
| exp08_real_synth_minor | — | — | — | — | — | fail |
| exp09_real_synth_prop | — | — | — | — | — | fail |

## Per-class mAP@0.5

| key | Bottle | Can | Fishing_Net | Glove | Mask | Metal_Debris | Plastic_Debris | Tire |
|---|---|---|---|---|---|---|---|---|
| base | 0.045 | 0.000 | 0.091 | 0.309 | 0.091 | 0.000 | 0.112 | 0.091 |
| exp01_synth100 | 0.020 | 0.000 | 0.156 | 0.327 | 0.091 | 0.000 | 0.393 | 0.061 |
