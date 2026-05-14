# Smoke Inference — Best LoRA Adapter (Gemma 4 E2B)

Adapter: `C:\Users\aleja\Desktop\Doctorado\OceanguardAI-App\finetune\outputs\grid_best\adapter`

Muestras: 5

JSON-validity (n=5): **80%**

> WARNING: JSON-validity por debajo del 95%. Revisar parser y prompt.

## Sample 0 — img_id=1644  latency=15.40s json_ok=False

**Ground truth**

```json
[{"box_2d":[10,132,375,293],"label":"plastic_debris","material":"Plastic"},{"box_2d":[221,291,627,430],"label":"plastic_debris","material":"Plastic"},{"box_2d":[5,334,161,430],"label":"plastic_debris","material":"Plastic"},{"box_2d":[2,442,55,483],"label":"plastic_debris","material":"Plastic"},{"box_2d":[120,445,401,500],"label":"plastic_debris","material":"Plastic"},{"box_2d":[264,522,498,579],"label":"plastic_debris","material":"Plastic"},{"box_2d":[401,599,702,683],"label":"plastic_debris","material":"Plastic"},{"box_2d":[5,534,281,724],"label":"plastic_debris","material":"Plastic"},{"box_2d":[2,702,603,856],"label":"plastic_debris","material":"Plastic"}]
```

**Prediction**

```
```json
[
  {"box_2d": [0, 135, 365, 292], "label": "plastic_debris", "material": "Plastic"},
  {"box_2d": [0, 535, 235, 705], "label": "plastic_debris", "material": "Plastic"},
  {"box_2d": [0, 712, 588, 855], "label": "plastic_debris", "material": "Plastic"},
  {"box_2d": [235, 295, 605, 425], "label": "plastic_debris", "material": "Plastic"},
  {"box_2d": [285, 525, 465, 585], "label": "plastic_debris", "material": "Plastic"}
]
```
```

## Sample 1 — img_id=32  latency=3.27s json_ok=True

**Ground truth**

```json
[{"box_2d":[315,297,350,349],"label":"plastic_debris","material":"Plastic"},{"box_2d":[383,244,409,275],"label":"plastic_debris","material":"Plastic"},{"box_2d":[291,300,316,323],"label":"plastic_debris","material":"Plastic"}]
```

**Prediction**

```
[
  {"box_2d": [412, 524, 534, 574], "label": "plastic_bottle", "material": "Plastic"}
]
```

## Sample 2 — img_id=5280  latency=3.35s json_ok=True

**Ground truth**

```json
[{"box_2d":[510,300,752,885],"label":"plastic_debris","material":"Plastic"}]
```

**Prediction**

```
[
  {"box_2d": [511, 305, 749, 887], "label": "plastic_debris", "material": "Plastic"}
]
```

## Sample 3 — img_id=15351  latency=6.13s json_ok=True

**Ground truth**

```json
[{"box_2d":[7,36,507,697],"label":"glove","material":"Latex"},{"box_2d":[0,700,567,1000],"label":"face_mask","material":"Fabric"},{"box_2d":[635,279,998,550],"label":"glove","material":"Latex"},{"box_2d":[570,17,957,298],"label":"face_mask","material":"Fabric"},{"box_2d":[572,774,998,1000],"label":"face_mask","material":"Fabric"}]
```

**Prediction**

```
[
  {"box_2d": [565, 75, 835, 278], "label": "plastic_debris", "material": "Plastic"},
  {"box_2d": [650, 315, 998, 505], "label": "glove", "material": "Latex"}
]
```

## Sample 4 — img_id=76  latency=12.18s json_ok=True

**Ground truth**

```json
[{"box_2d":[614,96,719,168],"label":"plastic_debris","material":"Plastic"}]
```

**Prediction**

```
[
  {"box_2d": [638, 362, 772, 452], "label": "plastic_bottle", "material": "Plastic"},
  {"box_2d": [634, 524, 724, 574], "label": "metal_can", "material": "Metal"},
  {"box_2d": [669, 542, 792, 602], "label": "metal_can", "material": "Metal"},
  {"box_2d": [782, 0, 1000, 500], "label": "fishing_net", "material": "Plastic"}
]
```

