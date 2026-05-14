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
datasets:
- cleansea
- ocean_garbage
- neural_ocean
language:
- en
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
      value: 0.3253
    - type: mAP@0.5_delta_vs_base
      value: 0.2329
---

# Gemma 4 E2B — OceanGuard Marine Debris LoRA Adapter

A LoRA fine-tune of [`google/gemma-4-E2B-it`](https://huggingface.co/google/gemma-4-E2B-it)
that adapts the model to open-vocabulary detection of anthropogenic marine debris on
consumer mobile hardware. The adapter is the training artefact behind the
[OceanGuard AI](https://github.com/asferrer/OceanguardAI) Android application — a fully
offline marine-debris intelligence toolkit submitted to the **Kaggle Gemma 4 Good
Hackathon** (Global Resilience track, Unsloth $10 K bonus) and built on top of two prior
peer-reviewed publications on underwater debris detection ([Sánchez-Ferrer et al., PRL
2023](https://www.sciencedirect.com/science/article/pii/S0167865522003889?via%3Dihub);
[IbPRIA 2022](https://link.springer.com/chapter/10.1007/978-3-031-04881-4_49)).

> **Headline result.** On a held-out 200-image evaluation set the LoRA fine-tune reaches
> **mAP@0.5 = 0.325**, against **0.092** for the unmodified base model
> (Δ = **+0.233**, a **+252 %** relative improvement) while maintaining
> **94.5 %** JSON-validity on the structured detection output.

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

The adapter is distributed as a standard PEFT LoRA (≈120 MB, `r=16`, `α=32`) attached to a
bf16 base model. End-to-end reproduction fits comfortably on a single consumer GPU (the
ablation grid below was run on an RTX 5090, but the recipe also runs on Kaggle T4 with
4-bit quantisation).

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
2022](https://link.springer.com/chapter/10.1007/978-3-031-04881-4_49)):

| Source | License | Annotations | Used in |
|---|---|---|---|
| CleanSea | Research-friendly (per dataset card) | COCO bbox, 8-class | All experiments |
| Ocean_garbage | Research-friendly (per dataset card) | COCO bbox, 8-class | All experiments |
| Neural_Ocean | Research-friendly (per dataset card) | COCO bbox, 8-class | All experiments |

The ablation grid (see §Evaluation) was run over **eleven dataset compositions** that vary
the ratio of synthetic-to-real images. The published adapter corresponds to experiment
`exp10_real_full`, which uses **13 637 real-image training samples** stratified by class
across the three source datasets. The 8-class taxonomy
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

The published adapter is the winner of an 11-experiment LoRA ablation grid. All runs used
**Unsloth FastVisionModel 2026.5.2** on a single workstation GPU, in bf16.

### Configuration (best: `exp10_real_full`)

| Hyper-parameter | Value |
|---|---|
| Base model | `unsloth/gemma-4-E2B-it` |
| LoRA `r` / `α` / dropout | 16 / 32 / 0 |
| Trainable parameters | 31 039 488 (0.60 % of the 5.15 B base) |
| Target modules | language head linear layers; vision tower frozen |
| Optimiser | `adamw_8bit` (Unsloth fused), `lr = 1e-4` cosine schedule, `weight_decay = 0.01`, `warmup_ratio = 0.1` |
| Batch size | 1 per device × 32 gradient accumulation = effective 32 |
| Sequence length / image token budget | 1 536 / 128 |
| Precision | bf16 |
| Epochs | 3 |
| Steps | 1 281 |
| Seed | 42 |
| Loss | causal-LM over the `box_2d`+`label`+`material` JSON tokens |

### Convergence

Loss decreased monotonically from **15.01** at step 10 to **0.456** at step 1 281; the
last 20 logging windows had a standard deviation of **0.0092** and a minimum loss of
**0.446** at step 1 230, indicating a clean cosine landing with no late-stage divergence.

### Hardware and wall-clock

- 1× NVIDIA RTX 5090 (32 GiB GDDR7), Windows 11, CUDA 12.8, PyTorch 2.7
- Training wall-clock: **6 h 53 min** (24 777 s) for `exp10_real_full`
- Deployment target: Samsung Galaxy S22 Ultra (Exynos 2200) via
  [LiteRT-LM 0.11.0](https://github.com/google-ai-edge/LiteRT-LM)

### Software

`unsloth` 2026.5.2 · `transformers` 5.5.0 · `peft` · `trl` · `accelerate` · `bitsandbytes` ·
`torch` 2.7.0+cu128.

The complete reproducible pipeline (`prepare_datasets → generate_configs → run_experiments
→ eval_grid → collect_results → auto_promote_best`) is published in the source repository
under `OceanguardAI-App/finetune/experiments/`.

## Evaluation

All runs are evaluated on a **200-image held-out test split** of the merged dataset, with
the same JSON contract and post-processing as the production Android app
(`temperature = 0.0`, `max_new_tokens = 256`).

### Aggregate

| Metric | Base Gemma 4 E2B | OceanGuard LoRA (`exp10_real_full`) |
|---|---|---|
| **mAP@0.5** | **0.0924** | **0.3253** *(+0.2329)* |
| Predictions emitted | 47 | 292 |
| JSON validity | 1.000 | 0.945 |
| Mean inference latency (RTX 5090) | 1.96 s | 5.43 s |

### Per-class mAP@0.5

| Class | Base | LoRA | Δ |
|---|---|---|---|
| Plastic Debris | 0.112 | **0.608** | **+0.496** |
| Fishing Net | 0.091 | **0.448** | **+0.357** |
| Tire | 0.091 | **0.403** | **+0.312** |
| Glove | 0.309 | **0.463** | **+0.154** |
| Bottle | 0.045 | **0.312** | **+0.266** |
| Mask | 0.091 | **0.308** | **+0.217** |
| Can | 0.000 | 0.061 | +0.061 |
| Metal Debris | 0.000 | 0.000 | 0.000 |

The fine-tune produces the largest absolute gains on `Plastic_Debris`, `Fishing_Net` and
`Tire` — three of the most ecologically harmful categories in the OceanGuard impact
hierarchy. `Metal_Debris` remains unlearned in this run (99 ground-truth instances in the
training set is the known floor); a class-balanced data campaign is planned. The ablation
grid (`exp01 … exp10`) and class-wise comparison against the base model are recorded in
`finetune/experiments/results/results.md` in the source repository.

## Deployment

The adapter targets **LiteRT-LM 0.11.0** on Exynos 2200 once the upstream
LoRA-to-`.litertlm` conversion path lands.

| Step | Tooling | Status |
|---|---|---|
| Train LoRA on RTX 5090 | Unsloth FastVisionModel | ✅ Done |
| Publish adapter on HuggingFace | `huggingface-cli` | ✅ Done |
| Use adapter via Transformers / PEFT | `model.load_adapter(...)` | ✅ Done — see notebook |
| Merge LoRA into base | `peft_model.merge_and_unload()` | ✅ Documented |
| Export merged model to `.litertlm` | `ai-edge-torch.generative` | ⏳ Blocked on upstream PEFT-export support (in progress, May 2026) |
| Sideload to Android | `adb push` + `VlmModelManager` | ✅ Documented |

Once the conversion path is unblocked the published adapter is the exact artefact that
will ship on-device — no retraining required. Until then, the adapter is consumed through
Unsloth or stock PEFT in the companion notebook
([`docs/submission/notebook_finetune.ipynb`](https://github.com/asferrer/OceanguardAI-App/blob/main/docs/submission/notebook_finetune.ipynb)),
and the shipped APK uses the **base** Gemma 4 E2B for single-shot deep analysis. This
is disclosed honestly in the submission write-up.

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
- **Underrepresented classes.** `Metal_Debris` (99 ground-truth instances) is not learned
  at the current data scale; `Can` improves only marginally. Cross-checks are advised on
  predictions for these classes until a class-balanced campaign is shipped.
- **JSON validity is 94.5 %, not 100 %.** Roughly 1 in 18 detections in the eval set
  emerge in malformed JSON and are dropped by the parser. The Android app handles
  these gracefully by surfacing an empty detection set to the user rather than
  fabricating boxes.
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
  url          = {https://github.com/asferrer/OceanguardAI}
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
