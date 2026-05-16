---
license: apache-2.0
base_model: google/gemma-4-E2B-it
library_name: peft
tags:
- vision
- object-detection
- marine-debris
- environmental-ai
- unsloth
- lora
- gemma-4
- peft
- edge-ai
- on-device
- mobile
- litert-lm
datasets:
- cleansea
- ocean_garbage
- neural_ocean
language:
- en
- es
- fr
- de
- it
- pt
pipeline_tag: image-to-text
metrics:
- mAP
model-index:
- name: gemma-4-E2B-it-oceanguard-marine-debris
  results:
  - task:
      type: object-detection
      name: Marine Debris Detection (open-vocabulary, 8-class collapse)
    dataset:
      type: oceanguard-merged-marine-debris
      name: OceanGuard merged hold-out (CleanSea + Ocean_garbage + Neural_Ocean)
    metrics:
    - type: mAP@0.5
      value: 0.3256
    - type: mAP@0.5_delta_vs_base
      value: 0.2189
---

# Gemma 4 E2B — OceanGuard Marine Debris LoRA Adapter

A LoRA fine-tune of [`google/gemma-4-E2B-it`](https://huggingface.co/google/gemma-4-E2B-it)
that adapts the model to open-vocabulary detection of anthropogenic marine debris on
consumer mobile hardware. The adapter is the training artefact behind the
[OceanGuard AI](https://github.com/asferrer/OceanguardAI-App) Android application — a fully
offline marine-debris intelligence toolkit submitted to the **Kaggle Gemma 4 Good
Hackathon** (Global Resilience track, Unsloth $10 K bonus) and built on top of two prior
peer-reviewed publications on underwater debris detection ([Sánchez-Ferrer et al., PRL
2023](https://www.sciencedirect.com/science/article/pii/S0167865522003889?via%3Dihub);
[IbPRIA 2022](https://link.springer.com/chapter/10.1007/978-3-031-04881-4_49)).

> **Headline result.** On the held-out evaluation split (n = 200 stratified samples,
> `real_test_holdout.jsonl`) the LoRA fine-tune reaches **mAP@0.5 = 0.3256**, against
> **0.1067** for the unmodified base model (Δ = **+0.2189**, a **+205 %** relative
> improvement). The winner is `exp12_vision_lora`, which unfreezes the SigLIP2 vision
> encoder via LoRA on top of the language-only adapter. Canonical metrics are tracked in
> `finetune/experiments/results/best.json` and `results.md` of the source repository.

## Files in This Repository

This repository ships the LoRA adapter artefacts. The LiteRT-LM runtime build
(`.litertlm`) is in progress — see §Deployment for the current status:

| Artefact | Purpose | Consumed by |
|---|---|---|
| `adapter_model.safetensors` + `adapter_config.json` | LoRA adapter (≈120 MB, `r=16`, `α=32`, both language + vision towers) on top of `google/gemma-4-E2B-it` | PEFT / Unsloth pipelines, the reproducibility notebook |
| `gemma-4-E2B-it-oceanguard.litertlm` *(pending)* | LiteRT-LM runtime build of the merged base + adapter (≈2.6 GB) | The OceanGuard Android app, via the in-app model downloader |

The adapter is fully reproducible and usable today via PEFT / Unsloth. The
`.litertlm` export depends on Gemma 4 support landing in MediaPipe's
`tasks.python.genai.converter` or in `ai-edge-torch`; until then, the Android
app's FINETUNED selector remains disabled and the app uses the unmodified
base `google/gemma-4-E2B-it` LiteRT-LM build. See §Deployment.

## Model Description

OceanGuard AI runs **Gemma 4 E2B** fully on-device on a Samsung Galaxy S22 Ultra
(Exynos 2200, CPU + Vulkan/Xclipse 920) for open-vocabulary deep analysis of marine debris
over a 50-class taxonomy that collapses down to **11 canonical ecological-impact families**
(`BOTTLE`, `CAN`, `FISHING_NET`, `GLOVE`, `MASK`, `METAL_DEBRIS`, `PLASTIC_DEBRIS`, `TIRE`,
`FABRIC_DEBRIS`, `GLASS_DEBRIS`, `OTHER`).

This adapter aligns Gemma 4 E2B with the exact structured output contract that the Android
application consumes — a JSON array of `{box_2d, label, material}` objects — and reduces the
rate at which detections fall through to the generic `PLASTIC_DEBRIS` catch-all. The same
LoRA also improves material attribution for the downstream ecological-impact lookup that
drives the app's health-score and report-generation pipelines.

The adapter is distributed as a standard PEFT LoRA (≈120 MB safetensors, `r=16`, `α=32`)
attached to a bf16 base model. Compared to a language-only adapter, the published model
additionally adapts the **SigLIP2 vision encoder** via the same low-rank decomposition —
this is what unlocks the dramatic gain on texture-heavy classes (`Fishing_Net`,
`Glove`) reported below.

## Intended Use

**In-scope use cases.**

- Mobile and edge marine-debris detection for citizen science, NGO field operations, and
  coastal monitoring campaigns.
- Research baselines for open-vocabulary detection over specialised environmental
  taxonomies.
- A grounding component for tool-augmented reporting pipelines (see the OceanGuard
  two-phase tool-calling architecture).

**Out-of-scope use cases.**

- Autonomous decision-making (cleanup dispatch, regulatory enforcement, environmental
  fines). This model is a triage and reporting aid; final decisions must remain with a
  qualified human.
- Real-time safety-critical detection (collision avoidance, navigation). End-to-end
  latency on the reference device is on the order of seconds per image.
- General-purpose object detection outside the marine-debris domain — the adapter narrows
  the visual prior towards marine debris and may degrade on unrelated tasks.

## Training Data

The training set is a permissive-license merge of three open marine-debris datasets,
including the **CleanSea** corpus introduced by the model author ([IbPRIA
2022](https://link.springer.com/chapter/10.1007/978-3-031-04881-4_49)), plus a
synthetic-renders set that expands the rare-class footprint:

| Source | License | Annotations | Used in |
|---|---|---|---|
| CleanSea | Research-friendly (per dataset card) | COCO bbox, 8-class | All experiments |
| Ocean_garbage | Research-friendly (per dataset card) | COCO bbox, 8-class | All experiments |
| Neural_Ocean | Research-friendly (per dataset card) | COCO bbox, 8-class | All experiments |
| DenSea synthetic renders | Apache 2.0 | COCO bbox, 8-class | exp01-exp09, exp11, exp12 |

The ablation grid was run over **twelve dataset compositions** that vary the ratio of
synthetic-to-real images and the LoRA target modules. The published adapter corresponds
to experiment `exp12_vision_lora`, which uses the **largest available training mix
(n = 24 446 samples: 10 809 synthetic + 13 637 real)** combined with the
**vision-encoder LoRA** that the language-only experiments leave untouched. The held-out
evaluation set is a separate 200-sample stratified split from `real_test_holdout.jsonl`
never seen during training. The 8-class taxonomy
(`Bottle`, `Can`, `Fishing_Net`, `Glove`, `Mask`, `Metal_Debris`, `Plastic_Debris`, `Tire`)
is extended at inference time to a 50-class fine-grained vocabulary that is mapped back to
the 11 canonical families through a deterministic lookup table; see
`Gemma4VisionDetector.LABEL_TO_TYPE` in the Android source for the full mapping.

**Geographic coverage.** The three source datasets were collected predominantly in
coastal waters around Japan and the wider north-western Pacific. As a result the
training distribution is biased towards the debris taxonomy, water clarity, lighting
and sediment patterns of that region. This is reflected in the §Limitations and §Bias
sections below; users surveying outside Japan-Pacific waters should expect a measurable
domain shift and validate the adapter on a local hold-out before operational use.

Re-distributors of the merged dataset must comply with the original dataset licenses.

## Training Procedure

The published adapter is the winner of a 12-experiment LoRA ablation grid. All runs used
**Unsloth FastVisionModel 2026.5.2** on a single workstation GPU, in bf16.

### Configuration (best: `exp12_vision_lora`)

| Hyper-parameter | Value |
|---|---|
| Base model | `unsloth/gemma-4-E2B-it` |
| LoRA `r` / `α` / dropout | 16 / 32 / 0 |
| Trainable parameters | ≈ 60 M (≈ 1.2 % of the 5.15 B base) |
| Target modules | `q_proj`, `k_proj`, `v_proj`, `o_proj`, `gate_proj`, `up_proj`, `down_proj` |
| Vision LoRA | **true** — same target modules applied to the SigLIP2 vision encoder *and* the language tower |
| Language LoRA | true |
| Optimiser | `adamw_8bit` (Unsloth fused), `lr = 1e-4` cosine schedule, `weight_decay = 0.01`, `warmup_ratio = 0.1` |
| Batch size | 1 per device × 32 gradient accumulation = effective 32 |
| Sequence length / image token budget | 1 024 / 64 |
| Precision | bf16 |
| Planned epochs | 3 (2 292 steps) |
| Effective steps trained | 1 150 (≈ 50 % of plan) |
| Seed | 42 |
| Loss | causal-LM over the `box_2d`+`label`+`material` JSON tokens |

### Why training stopped at step 1 150

The vision-encoder LoRA roughly doubles the trainable parameter count compared to a
language-only adapter, which raises the peak Windows commit charge (RAM + pagefile) for
the training process beyond the 112 GB ceiling configured on the training workstation
(96 GB physical RAM + 16 GB pagefile). After two CUDA / OS-level crashes during the
original 3-epoch run, the experiment was restarted with a multi-resume strategy
(200-step chunks, fresh Python process each chunk to drain the PIL-image cache held by
the eager dataset loader). The first chunk produced **checkpoint-1150** — and an
intermediate evaluation showed that the model had already **overtaken the previous
language-only winner** (`exp10_real_full`, mAP@0.5 = 0.3253). The orchestrator therefore
promoted `checkpoint-1150` to the canonical artefact and stopped further chunks. Full
3-epoch training on this configuration would require a larger pagefile or a lazy-loading
data collator — both noted in the project roadmap.

### Convergence

Loss decreased monotonically from **14.78** at step 10 to **0.4991** at step 1 150;
the last 20 logging windows had a standard deviation of **≈ 0.013** and a minimum loss of
**0.4991** at step 1 150, with no late-stage divergence. The language-only baseline
(`exp11_real_synth_full`, identical data) reached **0.5224** at the same step — the
vision LoRA delivered a sustained ≈ 0.022 absolute (≈ 4 %) loss advantage from step 250
onwards.

### Hardware and wall-clock

- 1× NVIDIA RTX 5090 (32 GiB GDDR7), Windows 11, CUDA 12.8, PyTorch 2.7
- Training wall-clock: **≈ 5 h 30 min** end-to-end (initial run to step 1 097 + recovery
  cycle to step 1 150 + final eval)
- Deployment target: Samsung Galaxy S22 Ultra (Exynos 2200) via
  [LiteRT-LM 0.11.0](https://github.com/google-ai-edge/LiteRT-LM)

### Software

`unsloth` 2026.5.2 · `transformers` 5.5.0 · `peft` · `trl` · `accelerate` · `bitsandbytes` ·
`torch` 2.7.0+cu128.

The complete reproducible pipeline (`prepare_datasets → generate_configs → run_experiments
→ eval_grid → collect_results → auto_promote_best`) is published in the source repository
under `OceanguardAI-App/finetune/experiments/`. The multi-resume orchestrator
(`finetune/multi_resume_exp12.py`) and the WMI-hang workaround
(`finetune/wmi_bypass.py`) used to recover the published checkpoint are also included.

## Evaluation

All runs are evaluated on the **held-out evaluation split** of the merged dataset, with
the same JSON contract and post-processing as the production Android app
(`temperature = 0.0`, `max_new_tokens = 256`). The canonical metrics in
`finetune/experiments/results/best.json` and `results.md` are reproduced verbatim in the
tables below.

### Aggregate

| Metric | Base Gemma 4 E2B | OceanGuard LoRA (`exp12_vision_lora`) |
|---|---|---|
| **mAP@0.5** | **0.1067** | **0.3256** *(+0.2189)* |
| Predictions emitted | 168 | 298 |
| JSON validity | 0.995 | 0.885 |
| Mean inference latency (RTX 5090) | 1.88 s | 7.19 s |

### Per-class mAP@0.5

| Class            | Base  | LoRA (vision_lora) | Δ          |
|------------------|-------|--------------------|------------|
| Fishing Net      | 0.142 | **0.575**          | **+0.433** |
| Glove            | 0.233 | **0.498**          | **+0.265** |
| Plastic Debris   | 0.121 | **0.489**          | **+0.368** |
| Tire             | 0.133 | **0.362**          | **+0.229** |
| Mask             | 0.091 | **0.319**          | **+0.228** |
| Bottle           | 0.132 | **0.272**          | **+0.140** |
| Can              | 0.000 | 0.091              | +0.091     |
| Metal Debris     | 0.000 | 0.000              | 0.000      |

Compared to the language-only adapter (`exp10_real_full`), the vision LoRA delivers
**dramatic gains on texture-rich classes** (`Fishing_Net` 0.448 → 0.575 = +0.127;
`Glove` 0.463 → 0.498 = +0.035), modest gains on `Mask` and `Can`, and small
regressions on `Plastic_Debris`, `Bottle` and `Tire` that the global mAP@0.5 still
overtakes. `Metal_Debris` remains unlearned even with the vision encoder unfrozen,
confirming that the bottleneck for that specific class is data scarcity rather than
visual representation. The full ablation grid (`exp01 … exp12`) and class-wise
comparison against the base model are recorded in
`finetune/experiments/results/results.md` in the source repository.

### Per-source mAP@0.5 (stratified breakdown)

To validate that the gain is not driven by a single source dataset, the same
200-image stratified test split is broken down by the originating dataset.
**The LoRA improves over the base in every source, with the largest relative
uplift on CleanSea — the smallest training subset and the project's own
benchmark from IbPRIA 2022.**

| Source         | n  | Base mAP@0.5 | LoRA mAP@0.5 | Δ absolute | LoRA / Base |
|----------------|---:|---:|---:|---:|---:|
| **GLOBAL**     | 200 | 0.1067 | 0.3236 | +0.2169 | **3.03×** |
| **CleanSea**   |  19 | 0.0152 | 0.0991 | +0.0840 | **6.54×** |
| **Neural_Ocean** | 67 | 0.1001 | 0.3252 | +0.2250 | 3.25× |
| **Ocean_garbage**| 114 | 0.1428 | 0.3532 | +0.2104 | 2.47× |

Notes on this breakdown:
- The values are computed with the same VOC 11-point AP fallback used by
  the project's `eval_grid.manual_map_eval` (matches the global card row
  within ≤ 0.002 rounding versus the pycocotools COCO-standard pass that
  produced `0.3256`).
- **CleanSea (n = 19)** is the smallest subset and therefore the
  6.54× ratio has wider confidence; treat it as a strong directional
  signal rather than a tight point estimate.
- The eval split is **not contaminated**: the 200 ids are a subset of
  `real_test_holdout.jsonl`, none of which appears in any
  `experiments/splits/exp*/train.jsonl`. The companion eval dataset on
  Hugging Face,
  [`asferrer/oceanguard-marine-debris-eval-1000`](https://huggingface.co/datasets/asferrer/oceanguard-marine-debris-eval-1000),
  ships the full annotation set plus `compute_per_source_map.py` for
  independent verification.

## Deployment

The adapter targets **LiteRT-LM 0.11.0** on Exynos 2200. The Android application
is shipped with the **base `google/gemma-4-E2B-it` LiteRT-LM build**; the
FINETUNED variant is gated on the `.litertlm` export listed below.

| Step | Tooling | Status |
|---|---|---|
| Train LoRA on RTX 5090 | Unsloth FastVisionModel | Done |
| Publish adapter on HuggingFace | `huggingface-cli` | Done |
| Use adapter via Transformers / PEFT | `model.load_adapter(...)` | Done — see notebook |
| Merge LoRA into base | `peft_model.merge_and_unload()` | Reproducible (`conversion/merge_and_convert.py`) |
| Export merged model to `.litertlm` | MediaPipe `tasks.python.genai.converter` or `ai-edge-torch` | **Pending** — Gemma 4 (MatMul-Free MLP + interleaved local/global attention) is not yet supported in the public converter as of May 2026. Scripts are in `conversion/` so the export can re-run as soon as upstream lands Gemma 4 support. |
| In-app download to Android | `VlmModelManager` + BASE/FINETUNED selector | Wired (BASE active, FINETUNED gated on `.litertlm` above) |

The Android application exposes a **BASE / FINETUNED variant selector** in the
developer-mode model picker. Selecting FINETUNED is currently disabled until
the `.litertlm` artefact ships in this repository; the BASE selector remains the
default and uses the unmodified `litert-community/gemma-4-E2B-it` runtime
fetched at install time.

Reference benchmarks on the deployment target
(Galaxy S22 Ultra, Exynos 2200, LiteRT-LM 0.11.0, Vulkan via Xclipse 920):

- Decode throughput: 5.1 tok/s
- Time to first token (warm / cold first load): 2.33 s / 17.34 s
- Prefill throughput: 48.7 tok/s
- Single-shot detection, ~100-token JSON output: ≈ 22 s end-to-end

## Limitations

- **Fixed-with-fallback taxonomy.** The fine-tune adheres to an 8-class core extended to
  50 fine-grained classes. Out-of-distribution debris (polar microplastic, deep-sea
  anthropogenic objects) is not represented in the training data and will be mapped to
  the generic catch-all classes.
- **CPU + Vulkan-only inference on the reference device.** The Exynos 2200 GPU pass via
  Vulkan improves correctness but not throughput; on this hardware Gemma 4 E2B is
  reserved for single-shot deep analysis (≈ 20 s end-to-end per image), not for
  continuous live-camera operation.
- **Geographic bias.** The training imagery is predominantly sourced from coastal
  waters around Japan and the broader north-western Pacific. Performance on the
  Mediterranean, tropical reefs, polar seas, the south Atlantic and freshwater debris
  surveys is expected to be lower and is **not yet measured**. Users surveying outside
  Japan-Pacific waters should treat the model as a baseline and validate against a
  local hold-out set before drawing conclusions.
- **Lighting and capture bias.** Synthetic-augmentation passes can inject lighting biases
  that may degrade under poor underwater visibility.
- **`Metal_Debris` remains unlearned.** Despite unfreezing the vision encoder via LoRA,
  `Metal_Debris` per-class mAP@0.5 stays at **0.000**, identical to the language-only
  baseline. This rules out visual representation as the bottleneck and points to either
  label ambiguity in the test split (overlap with `Tire`, `Plastic_Debris`,
  `Other_metal_objects`) or insufficient ground-truth instances. A class-balanced data
  campaign and a per-class label audit are planned.
- **`Can` improves but remains weak.** mAP@0.5 = **0.091**. Operators should expect
  silent misses on this class and gate critical decisions accordingly.
- **JSON validity is 88.5 %, not 100 %.** Roughly 1 in 9 detections in the eval set
  emerge in malformed JSON and are dropped by the parser. The Android app handles
  these gracefully by surfacing an empty detection set to the user rather than
  fabricating boxes. The drop from the language-only baseline (94.5 %) is the cost the
  vision LoRA pays for the higher recall on texture-rich classes — it is the dominant
  remaining failure mode.
- **Trained to step 1 150 of a 2 292-step plan.** The published checkpoint reaches the
  ablation-grid winner mAP but was halted before full 3-epoch convergence (see §Training
  Procedure). Further training on a workstation with a larger pagefile or with a
  lazy-loading collator is expected to refine these numbers modestly, particularly on
  `Plastic_Debris`, `Bottle` and `Tire` which regressed slightly versus the
  language-only winner.
- **Not certified.** This model is not certified for legal, regulatory or environmental
  enforcement reporting. The OceanGuard app produces draft reports that a human reviews
  before any official use.
- **Function-calling head untouched.** The fine-tune is intentionally scoped to the
  detection head and does **not** rewrite tool-calling syntax, to avoid regressing the
  two-phase tool-calling pipeline that the Android app depends on for grounded report
  generation.

## Bias, Risks and Safety

- **Spurious-correlation risk.** Marine-debris classes co-occur with characteristic
  backgrounds (e.g. fishing nets with rocky shorelines). The model may exploit context
  cues that do not generalise across geographies.
- **Sensitive categories.** The `syringe` / `medical waste` class is rare and high-stakes;
  positive detections should trigger human handling protocols rather than autonomous
  action.
- **Citizen-science abuse vectors.** As with any open-vocabulary detector, the model can
  be prompted to label arbitrary objects; downstream applications should constrain
  inputs to the marine-debris domain and filter implausible outputs (the OceanGuard app
  does this via `LABEL_TO_TYPE` and the canonical 11-class projection in
  `EnvironmentalImpact.IMPACT_MAP`).
- **Privacy.** Photos analysed on-device in OceanGuard never leave the phone; this
  adapter itself processes no user data. When used outside the app, downstream operators
  are responsible for compliance with applicable privacy regulation.

## How to Use

### A. Research / training pipeline — PEFT + Unsloth

```python
from unsloth import FastVisionModel

model, tokenizer = FastVisionModel.from_pretrained(
    "unsloth/gemma-4-E2B-it",
    load_in_4bit=True,
)
model.load_adapter(
    "asferrer/gemma-4-E2B-it-oceanguard-marine-debris",
    adapter_name="oceanguard",
)
model.set_adapter("oceanguard")
FastVisionModel.for_inference(model)

# Use the exact DETECTION_PROMPT shipped in the Android app — see
# docs/submission/notebook_finetune.ipynb for the verbatim string and helper.
```

A fully reproducible end-to-end example (image loading, inference, JSON parsing,
visualisation and quantitative evaluation) lives at
[`docs/submission/notebook_finetune.ipynb`](https://github.com/asferrer/OceanguardAI-App/blob/main/docs/submission/notebook_finetune.ipynb)
in the source repository.

### B. On-device deployment — direct `.litertlm` download *(pending upstream)*

Once the `.litertlm` export lands (see §Deployment), it will live at the URL
below and the Android app's FINETUNED selector will become active:

```bash
# Direct download URL (will return 404 until the .litertlm is uploaded)
curl -L -o gemma-4-E2B-it-oceanguard.litertlm \
  "https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris/resolve/main/gemma-4-E2B-it-oceanguard.litertlm"
```

The wiring inside the OceanGuard Android app (`VlmModelManager`, BASE/FINETUNED
selector, resumable download, atomic rename, progress notifications) is already
in place; it falls back gracefully to the BASE variant whenever the FINETUNED
URL is unreachable.

## License and Citation

- **LoRA adapter** — Apache 2.0
- **Base model** — `google/gemma-4-E2B-it`, subject to the Gemma Terms of Use
- **Training datasets** — source-dataset licenses apply (CC-BY or equivalent; see dataset
  cards)

```bibtex
@misc{sanchezferrer2026oceanguard_adapter,
  author       = {S{\'a}nchez-Ferrer, Alejandro},
  title        = {{Gemma 4 E2B OceanGuard Marine Debris LoRA Adapter}},
  year         = {2026},
  howpublished = {HuggingFace Model Hub},
  note         = {LoRA adapter for google/gemma-4-E2B-it. Apache 2.0.},
  url          = {https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris}
}

@misc{sanchezferrer2026oceanguard_app,
  author       = {S{\'a}nchez-Ferrer, Alejandro},
  title        = {{OceanGuard AI: Fully Offline Marine Debris Intelligence with Gemma 4 and Two-Phase Tool Calling}},
  year         = {2026},
  howpublished = {Kaggle Gemma 4 Good Hackathon (Global Resilience track)},
  url          = {https://github.com/asferrer/OceanguardAI-App}
}
```

## Related Publications by the Author

This adapter is the latest iteration of an ongoing line of research on automated
detection and recognition of underwater anthropogenic debris.

### Peer-reviewed papers

1. **Sánchez-Ferrer, A.**, Valero-Mas, J. J., Gallego, A. J., & Calvo-Zaragoza, J. (2023).
   *An experimental study on marine debris location and recognition using object
   detection.*
   **Pattern Recognition Letters**, 168, 154–161.
   [https://doi.org/10.1016/j.patrec.2022.12.019](https://www.sciencedirect.com/science/article/pii/S0167865522003889?via%3Dihub)

2. **Sánchez-Ferrer, A.**, Gallego, A. J., Valero-Mas, J. J., & Calvo-Zaragoza, J. (2022).
   *The CleanSea Set: A Benchmark Corpus for Underwater Debris Detection and
   Recognition.*
   In *Iberian Conference on Pattern Recognition and Image Analysis (IbPRIA 2022)*,
   Lecture Notes in Computer Science, Springer.
   [doi.org/10.1007/978-3-031-04881-4_49](https://link.springer.com/chapter/10.1007/978-3-031-04881-4_49)

### Theses

3. **Sánchez-Ferrer, A.** (2024). *Modelos de difusión aplicados a la detección de
   objetos en el fondo marino.* Master's Thesis, Universidad de Alicante.
   [rua.ua.es/…/88244474](https://rua.ua.es/entities/publication/88244474-6165-4cd9-a4af-68eff29d65c6)

4. **Sánchez-Ferrer, A.** (2021). *Deep Learning aplicado a la detección de residuos en
   el fondo marino.* Bachelor's Thesis, Universidad de Alicante.
   [rua.ua.es/…/92c34588](https://rua.ua.es/entities/publication/92c34588-9842-4ec3-8b15-97a8ce718e04)

### Applied research

5. **Sánchez-Ferrer, A.** (2025). *OceanGuard AI: Mapping and Mitigating Marine
   Pollution.* Kaggle hackathon write-up.
   [Kaggle write-up](https://www.kaggle.com/competitions/google-gemma-3n-hackathon/writeups/oceanguard-ai-mapping-and-mitigating-marine-pollut)

The CleanSea corpus introduced in (2) is one of the three sources used to train this
adapter; the detection methodology developed in (1) defines the marine-debris recognition
task that this fine-tune operationalises on-device with Gemma 4 E2B; (3) and (4) lay the
foundational work on which the present model card builds.

## Acknowledgments

- **Google DeepMind** for releasing Gemma 4 under terms that enable open research and
  on-device deployment.
- **The Unsloth team** for the FastVisionModel implementation that makes a Gemma 4
  vision-language fine-tune tractable on a single workstation GPU.
- **CleanSea, Ocean_garbage and Neural_Ocean** dataset authors for releasing
  marine-debris annotations under permissive terms.
- **Google AI Edge / LiteRT-LM** team for the on-device runtime that hosts the base model
  in the OceanGuard Android app.
- **Pattern Recognition and Artificial Intelligence (PRAI) group, Universidad de
  Alicante** — research group hosting the doctoral programme under which this work is
  carried out, and co-authors of the prior publications cited above.
