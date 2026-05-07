# OceanGuard AI - Reproducible Evaluation Set

A small, externally-collected evaluation set used to report the detector
metrics in the Kaggle Gemma 4 Good Hackathon submission writeup. The goal is
**reproducibility**, not large-scale benchmarking: numbers reported in the
writeup must be re-derivable by running `scripts/evaluate_detector.py` on
this directory.

## Structure

```
eval_set/
├── README.md                      # this file
├── annotations/
│   ├── _template.json             # empty COCO JSON template (8 classes)
│   └── eval_set_v1.json           # ground truth (filled by annotator)
├── images/
│   └── *.jpg                      # captured images (50-100, NOT committed)
└── scripts/
    ├── requirements.txt
    ├── validate_coco.py           # check JSON structure + image presence
    ├── evaluate_detector.py       # run RT-DETRv2 ONNX, compute COCO mAP
    └── generate_stats.py          # per-class counts, image dims histogram
```

Images are **excluded from git** (see `.gitignore` patterns: `*.jpg`,
`*.jpeg`, `*.png` under `eval_set/images/`). They live as a release asset
or in a Synology share. The COCO JSON ground truth is committed.

## Classes (must match training schema)

The detector produces 8 classes in this exact order — `category_id` must
match the index. Do not rename, do not reorder.

| ID | Name | Examples |
|----|------|----------|
| 0  | Bottle | PET, glass, plastic bottles |
| 1  | Can | Aluminium drink cans |
| 2  | Fishing_Net | Drift nets, ghost nets, line tangles |
| 3  | Glove | Latex / nitrile / fishing gloves |
| 4  | Mask | Surgical / FFP2 disposable masks |
| 5  | Metal_Debris | Rusty cans, metal fragments, scrap |
| 6  | Plastic_Debris | Generic plastic fragments, bags, wrappers |
| 7  | Tire | Vehicle / motorcycle tires |

## Capture protocol — Alicante coast, 11-12 May 2026

**Target:** 50-100 images, geographically diverse, lighting diverse, including
hard cases (partial occlusion, water reflection, sand on debris, distance
variation). Aim for **6-12 images per class** so that per-class AP is
statistically meaningful.

### Recommended zones (in order of accessibility)

| Zone | Lat / Lng (approx) | Why |
|------|-------------------:|-----|
| Cabo de las Huertas (north tip) | 38.354, -0.418 | Rocky coves, accumulation pockets |
| Playa del Postiguet | 38.345, -0.476 | Urban beach, mixed debris |
| Playa de la Albufereta | 38.357, -0.434 | Mid-town, post-storm accumulation |
| Cala Cantalar | 38.359, -0.408 | Small natural cove, less swept |
| Playa de San Juan | 38.391, -0.418 | Open beach, larger items |

### Per-shot checklist

- [ ] Camera height: ~1.2-1.5 m (handheld), pointed slightly down (15-30°).
- [ ] One primary debris item centered, at least one secondary item visible
      when realistic.
- [ ] Avoid duplicates: do NOT shoot the same item from 5 angles. One angle
      per item; move to the next item.
- [ ] Varying distance: ~30 % close-up (item fills 30-50 % of frame), ~50 %
      medium (item is 5-15 % of frame), ~20 % wide (multiple items).
- [ ] EXIF GPS: turn ON in camera settings before shooting.
- [ ] Filename: `OG-EVAL-{NN}-{class_short}-{HHMMSS}.jpg`
      (e.g. `OG-EVAL-12-bottle-141520.jpg`).

### What to AVOID

- Posed / staged debris arranged by the photographer (defeats the purpose).
- Branded close-ups that identify a specific manufacturer (privacy + legal).
- People's faces or license plates in frame (blur or recompose).
- HDR / portrait mode / heavy in-camera processing — use auto / standard.

### Two-day plan

| Day  | Block | Locations | Target |
|------|-------|-----------|--------|
| 11 May AM (08:00-10:30) | Golden hour | Cabo Huertas, Cala Cantalar | 25-30 imgs, hard light cases |
| 11 May PM (17:00-19:00) | Evening light | Postiguet, Albufereta | 20-25 imgs, urban-debris mix |
| 12 May AM (rain backup) | Indoor / vehicle shots if storm | n/a | studio shots of recovered debris |
| 12 May PM | Open beach | San Juan | 15-25 imgs to round out classes |

## Annotation pipeline

1. Pull images into `eval_set/images/`.
2. Open them in **Label Studio** (recommended) or **CVAT**. A starting
   project template is in `scripts/labelstudio_template.json` — TODO once
   first annotation pass is done.
3. Draw tight axis-aligned boxes around each piece of debris. **Bounding
   boxes only — no segmentation masks** (RF-DETR-Seg migration is a
   separate post-submission workstream).
4. Export as **COCO JSON** to `annotations/eval_set_v1.json`.
5. Run `python scripts/validate_coco.py annotations/eval_set_v1.json` —
   must report 0 errors before a metric is published.

## Reproducing the writeup metrics

```bash
cd docs/submission/eval_set
python -m venv .venv && source .venv/bin/activate
pip install -r scripts/requirements.txt

python scripts/validate_coco.py annotations/eval_set_v1.json
python scripts/generate_stats.py annotations/eval_set_v1.json
python scripts/evaluate_detector.py \
    --annotations annotations/eval_set_v1.json \
    --images images/ \
    --model ../../../android/app/src/main/assets/models/rtdetrv2_detector.tflite \
    --threshold 0.30 \
    --iou 0.50
```

The detector script prints **mAP@0.5**, **mAP@0.5:0.95**, and per-class AP
in a single table that maps directly into the writeup. It accepts the
TFLite (FP16 or INT8) model used by the Android app, so the numbers are
the *same numbers* the phone produces — modulo platform-specific
quantisation deltas which the script also reports.

## License

CC-BY-4.0 for the captured images and annotations. Apache 2.0 for the
scripts. Anyone can re-use the eval set for marine debris detection
research provided they cite the OceanGuard AI submission.
