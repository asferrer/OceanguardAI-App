# Upload the 1 000-image evaluation set to Hugging Face

The hackathon writeup reports the LoRA adapter delta on a 1 000-image
held-out test split. To make the numbers reproducible, that split needs
to live somewhere public alongside the adapter.

## TL;DR

```
1. Materialise images + COCO JSON in a local staging folder
2. Push to Hugging Face Hub as a Dataset repo
3. Paste the URL into KAGGLE_SUBMISSION_FORM.md Group B
```

Recommended target: **Hugging Face Dataset**
(`asferrer/oceanguard-marine-debris-eval-1000`).
Why HF: ties the eval set to the adapter on the same platform, supports
the `datasets` Python API for one-line loading, no Kaggle dataset-size
limit gotchas, and licence metadata is first-class.

---

## Where the data lives right now

- **Manifest**: `finetune/experiments/splits/real_test_holdout.jsonl`
  (1 000 rows, tracked in git, **1.7 MB**)
  - keys: `img_id`, `image_path`, `width`, `height`, `gt_dets`, `messages`
  - `image_path` is an **absolute Windows path** into the Synology NAS,
    e.g. `C:\Users\aleja\SynologyDrive\Datasets\Deteccion_Residuos\…\img.jpg`
- **Images**: in the Synology share at
  `C:\Users\aleja\SynologyDrive\Datasets\Deteccion_Residuos`, scattered
  across the three source datasets:
  - `CleanSea/CocoFormatDataset/train_coco/JPEGImages`
  - `Ocean_garbage/{train,valid,test}`
  - `Neural_Ocean/{train,valid,test}`
- **Source-dataset licences** (verify before publishing):
  - CleanSea — CC-BY-4.0 (Sánchez-Ferrer et al., IbPRIA 2022)
  - Ocean_garbage — Roboflow Universe (check licence on the page)
  - Neural_Ocean — Roboflow Universe (check licence on the page)
  - If any one is non-redistributable, **filter it out before upload**
    (see step 3 below).

## Step 1 — Stage the eval set locally

```bash
# Run from the repo root
mkdir -p /tmp/oceanguard-eval-staging/images
```

Then write a tiny one-shot copy script. Save as
`finetune/experiments/stage_eval_for_hf.py` (you can delete it after):

```python
"""Stage the 1000-img holdout into a flat folder + a relative-path COCO JSON."""
import json
import shutil
from pathlib import Path

SRC_JSONL = Path("finetune/experiments/splits/real_test_holdout.jsonl")
STAGING   = Path("/tmp/oceanguard-eval-staging")
IMG_DIR   = STAGING / "images"
IMG_DIR.mkdir(parents=True, exist_ok=True)

# Optional: which source-dataset prefixes are publishable
ALLOWED = ("CleanSea", "Neural_Ocean", "Ocean_garbage")   # trim if needed

rows_out = []
missing  = 0
skipped  = 0
for line in SRC_JSONL.open(encoding="utf-8"):
    row = json.loads(line)
    src = Path(row["image_path"])
    # licence filter
    if not any(token in src.parts for token in ALLOWED):
        skipped += 1
        continue
    if not src.exists():
        missing += 1
        continue
    flat_name = f"{row['img_id']:06d}_{src.name}"
    shutil.copy2(src, IMG_DIR / flat_name)
    rows_out.append({
        "img_id":     row["img_id"],
        "file_name":  f"images/{flat_name}",
        "width":      row["width"],
        "height":     row["height"],
        "gt_dets":    row["gt_dets"],
    })

(STAGING / "metadata.jsonl").write_text(
    "\n".join(json.dumps(r, ensure_ascii=False) for r in rows_out) + "\n",
    encoding="utf-8",
)
print(f"Staged {len(rows_out)} rows · missing {missing} · skipped {skipped}")
print(f"Output: {STAGING}")
```

Run it:

```bash
python finetune/experiments/stage_eval_for_hf.py
```

Expected output: `Staged 1000 rows · missing 0 · skipped 0` (if any
file is missing, fix the Synology mount first; if a source dataset has
to be excluded, edit `ALLOWED`).

## Step 2 — Write the dataset card

Drop a `README.md` into `/tmp/oceanguard-eval-staging/` with the HF
metadata block:

```markdown
---
license: cc-by-4.0
task_categories:
  - object-detection
language:
  - en
tags:
  - marine-debris
  - underwater
  - environmental
  - gemma
size_categories:
  - n<10K
---

# OceanGuard AI — Marine Debris Eval Set (1 000 images)

The held-out evaluation split used to report the LoRA adapter delta
in [OceanGuard AI](https://github.com/asferrer/OceanguardAI-App), the
Kaggle Gemma 4 Good Hackathon submission (Global Resilience track).

## Contents

- `images/` — 1 000 underwater / coastal JPEGs.
- `metadata.jsonl` — one row per image:
  - `img_id` (int)
  - `file_name` (str, relative to repo root)
  - `width`, `height` (int)
  - `gt_dets` — list of `{label, box_2d}` where
    `box_2d = [y_min, x_min, y_max, x_max]` on a 1000×1000 grid.

## Classes (8)

| ID | Name | Material |
|----|------|----------|
| 0  | plastic_bottle | Plastic |
| 1  | metal_can      | Metal   |
| 2  | fishing_net    | Plastic |
| 3  | glove          | Latex   |
| 4  | face_mask      | Fabric  |
| 5  | metal_debris   | Metal   |
| 6  | plastic_debris | Plastic |
| 7  | tire           | Rubber  |

## Provenance

Sourced from CleanSea (CC-BY-4.0), Ocean_garbage and Neural_Ocean
(Roboflow Universe). Stratified random hold-out from the combined corpus
used to train `asferrer/gemma-4-E2B-it-oceanguard-marine-debris`.

## Headline metrics (LoRA adapter vs base Gemma 4 E2B)

| Metric | Base | OceanGuard LoRA |
|--------|------|-----------------|
| mAP@0.5 | 0.1067 | **0.3256 (+205 %)** |
| JSON validity | 1.000 | 0.945 |

## Citation

```bibtex
@misc{sanchezferrer2026oceanguardeval,
  author       = {S{\'a}nchez-Ferrer, Alejandro},
  title        = {{OceanGuard AI — Marine Debris Eval Set (1 000 images)}},
  year         = {2026},
  howpublished = {Hugging Face Dataset},
  url          = {https://huggingface.co/datasets/asferrer/oceanguard-marine-debris-eval-1000}
}
```
```

## Step 3 — Push to Hugging Face

You need an HF token with **Write** scope. If you do not have one yet:
<https://huggingface.co/settings/tokens> → "New token" → role = Write.

```bash
pip install --upgrade huggingface_hub
huggingface-cli login                  # paste your token

# Create the empty dataset repo (first time only)
huggingface-cli repo create \
  oceanguard-marine-debris-eval-1000 \
  --type dataset \
  --organization asferrer

# Upload the staged folder (one big atomic commit)
huggingface-cli upload \
  asferrer/oceanguard-marine-debris-eval-1000 \
  /tmp/oceanguard-eval-staging \
  --repo-type dataset \
  --commit-message "[ADD] Initial eval set v1.0 (1000 imgs + metadata)"
```

This pushes ~200 MB. Allow ~5–15 minutes depending on connection.
Verify at: <https://huggingface.co/datasets/asferrer/oceanguard-marine-debris-eval-1000>

## Step 4 — Wire the URL into the submission

Update `docs/submission/KAGGLE_SUBMISSION_FORM.md` Group B / external
resources with:

```
Hugging Face Dataset (eval set) | https://huggingface.co/datasets/asferrer/oceanguard-marine-debris-eval-1000
```

And the same URL into `docs/submission/hf_model_card.md` if you want
the model card to point to its eval companion.

## Step 5 — One-line load for reviewers

A judge / researcher can pull the eval set in one line:

```python
from datasets import load_dataset
ds = load_dataset("asferrer/oceanguard-marine-debris-eval-1000")
print(ds["train"][0])   # {'img_id': ..., 'file_name': ..., 'gt_dets': [...]}
```

Done.

---

## Alternative: Kaggle Dataset (only if HF fails)

```bash
pip install --upgrade kaggle
# put kaggle.json at ~/.kaggle/kaggle.json (or %USERPROFILE%\.kaggle\)

cd /tmp/oceanguard-eval-staging
kaggle datasets init -p .
# edit the generated dataset-metadata.json:
#   "id": "asferrer/oceanguard-marine-debris-eval-1000"
#   "title": "OceanGuard AI — Marine Debris Eval Set (1000 images)"
#   "licenses": [{"name": "CC-BY-4.0"}]
kaggle datasets create -p . -r zip
```

Public URL will be
`https://www.kaggle.com/datasets/asferrer/oceanguard-marine-debris-eval-1000`.
