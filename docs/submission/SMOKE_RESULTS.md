# Smoke Inference — Best LoRA Adapter (Gemma 4 E2B)

Adapter: `C:\Users\aleja\Desktop\Doctorado\OceanguardAI-App\finetune\outputs\grid_best\adapter`

Muestras: 5

JSON-validity (n=5): **100%**

## Sample 0 — img_id=1644  latency=12.08s json_ok=True

**Ground truth**

```json
[{"box_2d":[10,132,375,293],"label":"plastic_debris","material":"Plastic"},{"box_2d":[221,291,627,430],"label":"plastic_debris","material":"Plastic"},{"box_2d":[5,334,161,430],"label":"plastic_debris","material":"Plastic"},{"box_2d":[2,442,55,483],"label":"plastic_debris","material":"Plastic"},{"box_2d":[120,445,401,500],"label":"plastic_debris","material":"Plastic"},{"box_2d":[264,522,498,579],"label":"plastic_debris","material":"Plastic"},{"box_2d":[401,599,702,683],"label":"plastic_debris","material":"Plastic"},{"box_2d":[5,534,281,724],"label":"plastic_debris","material":"Plastic"},{"box_2d":[2,702,603,856],"label":"plastic_debris","material":"Plastic"}]
```

**Prediction**

```
[
  {"box_2d": [0, 135, 363, 292], "label": "plastic_debris", "material": "Plastic"},
  {"box_2d": [0, 535, 235, 695], "label": "plastic_debris", "material": "Plastic"},
  {"box_2d": [0, 707, 588, 855], "label": "plastic_debris", "material": "Plastic"},
  {"box_2d": [235, 295, 600, 425], "label": "plastic_debris", "material": "Plastic"}
]
```

## Sample 1 — img_id=32  latency=5.28s json_ok=True

**Ground truth**

```json
[{"box_2d":[315,297,350,349],"label":"plastic_debris","material":"Plastic"},{"box_2d":[383,244,409,275],"label":"plastic_debris","material":"Plastic"},{"box_2d":[291,300,316,323],"label":"plastic_debris","material":"Plastic"}]
```

**Prediction**

```
[
  {"box_2d": [413, 483, 523, 536], "label": "plastic_bottle"},
  {"box_2d": [633, 0, 999, 503], "label": "plastic_debris"}
]
```

## Sample 2 — img_id=5280  latency=2.87s json_ok=True

**Ground truth**

```json
[{"box_2d":[510,300,752,885],"label":"plastic_debris","material":"Plastic"}]
```

**Prediction**

```
[
  {"box_2d": [513, 305, 749, 886], "label": "plastic_debris"}
]
```

## Sample 3 — img_id=15351  latency=5.22s json_ok=True

**Ground truth**

```json
[{"box_2d":[7,36,507,697],"label":"glove","material":"Latex"},{"box_2d":[0,700,567,1000],"label":"face_mask","material":"Fabric"},{"box_2d":[635,279,998,550],"label":"glove","material":"Latex"},{"box_2d":[570,17,957,298],"label":"face_mask","material":"Fabric"},{"box_2d":[572,774,998,1000],"label":"face_mask","material":"Fabric"}]
```

**Prediction**

```
[
  {"box_2d": [565, 75, 865, 278], "label": "plastic_debris"},
  {"box_2d": [645, 315, 998, 505], "label": "glove"}
]
```

## Sample 4 — img_id=76  latency=10.46s json_ok=True

**Ground truth**

```json
[{"box_2d":[614,96,719,168],"label":"plastic_debris","material":"Plastic"}]
```

**Prediction**

```
[
  {"box_2d": [691, 0, 1000, 475], "label": "fishing_net"},
  {"box_2d": [628, 358, 765, 452], "label": "plastic_bottle"},
  {"box_2d": [638, 533, 814, 611], "label": "plastic_bottle"},
  {"box_2d": [628, 528, 765, 602], "label": "plastic_bottle"}
]
```

