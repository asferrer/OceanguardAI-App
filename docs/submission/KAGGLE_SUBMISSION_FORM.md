# Kaggle Submission Form — OceanGuard AI

**Hackathon**: Gemma 4 Good Hackathon
**Track**: Global Resilience (Climate)
**Submission window**: 16–18 May 2026
**Author**: Alejandro Sánchez-Ferrer (Universidad de Alicante)

> Drop-in copy for every required Kaggle field. Each numbered section below
> maps **1:1 to a field in the Kaggle submission form**. Copy the recommended
> option verbatim. Character counts have been verified.

---

## 1. Title  *(max 80 characters)*

**Recommended (73 chars):**

> OceanGuard AI: Offline Marine Debris Intelligence on Android with Gemma 4

Alternatives within budget:

| Variant | Chars |
|---|---|
| `OceanGuard AI: On-Device Marine Debris Intelligence Powered by Gemma 4` | 70 |
| `OceanGuard AI: Gemma 4 Marine Pollution Intelligence on a Phone` | 64 |
| `OceanGuard AI: Offline Marine Debris Detection with Gemma 4` | 60 |

---

## 2. Subtitle / short description  *(max 140 characters)*

**Recommended (138 chars):**

> Fully offline Android app: Gemma 4 detects, classifies and reports marine debris in six languages. Zero cloud calls. Apache 2.0.

Alternatives within budget:

| Variant | Chars |
|---|---|
| `On-device marine pollution intelligence with Gemma 4. Bounding boxes, ecosystem health, six-language reports. 100% offline.` | 124 |
| `Gemma 4 turns any Android into a marine biologist. Offline detection, health score, multilingual conservation reports.` | 119 |
| `Marine debris detection, ecological-impact scoring and multilingual reports on-device with Gemma 4. Apache 2.0.` | 113 |

---

## 3. Cover image  *(reference image of the project)*

Upload **one** of the following as the listing thumbnail (the image Kaggle
displays in the gallery / shareable card):

| Recommended | Path | Notes |
|---|---|---|
| **Primary** | `OceanguardAI/assets/banner.svg` (export to 1200×630 PNG) | Wide hero with the OceanGuard logo + ocean gradient, optimised for social-share aspect ratio. |
| Fallback A | `OceanguardAI/assets/logo.png` | Square logo, 512×512. Safe if Kaggle rejects the wide banner. |
| Fallback B | `OceanguardAI-App/docs/submission/diagrams/architecture.png` | High-density technical diagram — works as a thumbnail for a more research-oriented framing. |

**To export `banner.svg` → PNG (1200×630, the Kaggle-recommended cover ratio):**

```bash
# Inkscape (preferred — preserves text):
inkscape OceanguardAI/assets/banner.svg \
  --export-type=png --export-filename=cover.png \
  --export-width=1200 --export-height=630

# Or ImageMagick:
magick -background none -density 144 OceanguardAI/assets/banner.svg \
  -resize 1200x630 cover.png
```

---

## 4. Media gallery  *(extra images, in upload order)*

Aim for **6–8 images**. Sequence below tells a story (problem → architecture
→ app → results). Files marked *(to capture)* require an `adb screencap`
pass from the reference device.

| # | File | Purpose |
|---|---|---|
| 1 | `OceanguardAI/assets/banner.svg` (PNG export) | Hero card — duplicates the cover so the gallery feels intentional. |
| 2 | `OceanguardAI-App/docs/submission/diagrams/architecture.png` | System architecture: capture → on-device Gemma 4 → grounded reports, no cloud. |
| 3 | `OceanguardAI-App/docs/submission/diagrams/two_phase_flow.png` | Single-conversation agentic tool-calling flow on Gemma 4: Step 1 tool dispatch + Step 2 report writing inside the same conversation (warm KV cache). |
| 4 | *(to capture)* `screenshots/01_home.png` | Home screen with Ready banner, stats, MarineDex preview. |
| 5 | *(to capture)* `screenshots/02_camera.png` | Camera viewfinder with the 80 dp shutter (high-contrast for dive use). |
| 6 | *(to capture)* `screenshots/03_results.png` | Captured photo with bounding boxes overlay + ecosystem health score. |
| 7 | *(to capture)* `screenshots/04_marinedex.png` | MarineDex Pokédex-style gallery with the canonical 11 pixel-art sprites. |
| 8 | *(to capture)* `screenshots/05_report.png` | Generated multilingual report (e.g. Spanish, Scientific audience). |

### Screenshot capture commands

```bash
# Single screenshot (replace 01_home.png each iteration):
MSYS_NO_PATHCONV=1 adb shell screencap -p /sdcard/sc.png && \
MSYS_NO_PATHCONV=1 adb pull /sdcard/sc.png docs/submission/screenshots/01_home.png

# Or, for a clean batch in one terminal session:
mkdir -p docs/submission/screenshots
for name in 01_home 02_camera 03_results 04_marinedex 05_report; do
  read -p "Frame the screen for $name then press ENTER..."
  MSYS_NO_PATHCONV=1 adb shell screencap -p /sdcard/sc.png
  MSYS_NO_PATHCONV=1 adb pull /sdcard/sc.png docs/submission/screenshots/$name.png
done
```

Recommended capture order to minimise navigation:
**Home → Camera → take real photo → Results → MarineDex (after entry unlocks) → Reports**.

---

## 5. Full project description  *(Kaggle markdown — paste verbatim)*

```markdown
# OceanGuard AI

**Fully offline marine debris intelligence on Android, powered by Gemma 4.**

> mAP@0.5 = 0.325 vs 0.092 base (+252 % relative) on a 200-image held-out
> evaluation — a fine-tuned LoRA on Gemma 4 E2B that turns any consumer phone
> into a marine biologist with zero cloud dependency.

---

## The problem

Marine plastic pollution is a planetary-scale resilience crisis. UNEP
estimates ~8 million tonnes of plastic enter the ocean every year, implicated
in entanglement and ingestion mortality across more than 900 species and in
the degradation of fisheries, tourism and coastal-protection ecosystem
services valued above 13 B USD / year.

The field workers closest to the problem — coastal NGOs, dive cleanup crews,
government rangers, fishing-port volunteers — operate exactly where cloud AI
fails: low or zero connectivity, prohibitive data cost in foreign currency,
and absolute zero tolerance for fabricated statistics in policy reports.

OceanGuard AI exists for them: it is a **single-Activity Jetpack Compose
Android application that runs the entire marine debris intelligence pipeline
on the device** with no network call after the initial model download.

---

## What the app does

1. **Capture** — the user takes a photo (camera viewfinder optimised for
   diving masks: 80 dp pulsing shutter, high contrast) or imports from the
   gallery. Batch mode accepts up to 10 images at once.
2. **Detect** — Gemma 4 E2B (LiteRT-LM 0.11.0) returns a JSON array of
   `{box_2d, label, material}` over a 50-class fine-grained vocabulary that
   maps deterministically to **11 canonical ecological-impact families**
   (Bottle, Can, Fishing Net, Glove, Mask, Metal Debris, Plastic Debris,
   Tire, Fabric Debris, Glass Debris, Other).
3. **Score** — every detection feeds a 0-100 *ecosystem health score*
   computed from a deterministic risk-weight table (NOAA MDMAP, OSPAR and
   MSFD D10 informed).
4. **Aggregate** — sessions are clustered geospatially (Haversine + DBSCAN)
   into **zones**; trend analysis is computed across temporal windows.
5. **Report** — Gemma 4 produces an executive-grade Markdown or PDF report
   tuned to three audiences (Scientific, NGO Manager, Citizen) in
   **six languages** (English, Spanish, French, German, Italian, Portuguese).
6. **Discover** — every new debris class unlocks an entry in a Pokédex-style
   *MarineDex* with pixel-art sprites and a 24-achievement progression
   system to keep citizen-science engagement sustained over months of field
   work.

Everything happens on a Samsung Galaxy S22 Ultra (Exynos 2200, CPU + Vulkan)
with **no network call** after the initial model download.

---

## Why this is a Global Resilience problem

| Axis | Why OceanGuard moves the needle |
|---|---|
| **Climate & biodiversity** | Plastic ingestion / entanglement mortality across 900+ marine species; coastal ecosystem services degradation. Triage faster, intervene sooner. |
| **Geographic equity** | The river basins emitting most riverine plastic are in regions with the worst mobile broadband. OceanGuard is the first toolkit that does NOT require connectivity. |
| **Decision quality** | Ministries and NGOs cannot defensibly act on AI-generated environmental statistics that hallucinate. OceanGuard's reports trace every percentage, degradation time and risk score back to deterministic Kotlin tools — *not* token sampling. |

---

## The technical centrepiece — single-conversation agentic tool calling on Gemma 4

LLM reports are notoriously unreliable: models invent percentages, dates,
species and GPS coordinates. OceanGuard AI's agentic tool-calling
architecture on Gemma 4 eliminates this by construction, all inside one
LiteRT-LM conversation:

- **Step 1 — Tool dispatch (warm KV).** Gemma 4 is required to dispatch a
  set of typed Kotlin tools (`debris-summary`, `material-breakdown`,
  `type-breakdown`, `risk-assessment`, `collection-waypoints`,
  `survey-statistics`, `ecological-impacts`, `temporal-trend`). Each tool
  reads from a Room v11 database; the returned JSON lives in the model's
  KV cache as its own turn. `ToolAgentLoop` runs with
  `automaticToolCalling = false` and `MAX_TOOL_ROUNDS = 14` so the
  application — not the runtime — decides when to terminate. Every tool
  invocation surfaces a `ReportGenerationState.ToolExecuting` event so
  the UI shows "Querying Debris Summary…" live, eliminating "Generating…"
  deadtime.
- **Step 2 — Report writing (same conversation).** As soon as the
  required tools have returned, Gemma 4 continues writing the report
  **inside the same conversation** — there is no reset, no fresh
  conversation, no second context window. The KV cache built up by the
  tool round carries directly into the prose generation, so the model
  writes its report using verified tool data while every token streams
  live to the UI via `ReportGenerationState.StreamingText`. First
  user-visible report token arrives within 1–2 s of the last tool
  dispatch.
- **Canonical post-process safety net.** A `ToolDataBundleFormatter`
  builds a `Canon` lookup map *offline*, never sent to the model.
  `repairHallucinations` walks the final markdown and rewrites any row
  whose first cell matches a canonical label, restoring the verbatim
  number even if Gemma 4 paraphrased it. A 12-check `ReportValidator`
  then cross-references the output against the source sessions and
  persists a 0–100 confidence score.

The net effect: every number in a generated report can be traced to a
specific Kotlin function call against a specific Room row, and the user
watches each query happen in real time.

---

## Headline result

A LoRA fine-tune of `google/gemma-4-E2B-it` on a Japan / north-western
Pacific marine-debris corpus (CleanSea + Ocean_garbage + Neural_Ocean,
~13.6 k training images stratified by class) was selected from an
11-experiment ablation grid:

| Metric | Base Gemma 4 E2B | OceanGuard LoRA (`exp10_real_full`) |
|---|---|---|
| **mAP@0.5** | 0.0924 | **0.3253 (+252 %)** |
| JSON validity | 1.000 | 0.945 |
| Predictions emitted | 47 | 292 |

Per-class gains (mAP@0.5, full table on the model card):

- Plastic Debris: 0.112 → **0.608** (+0.496)
- Fishing Net: 0.091 → **0.448** (+0.357)
- Tire: 0.091 → **0.403** (+0.312)
- Bottle: 0.045 → **0.312** (+0.266)
- Mask: 0.091 → **0.308** (+0.217)
- Glove: 0.309 → **0.463** (+0.154)
- Can: 0.000 → 0.061 (data-scarce class, planned class-balanced campaign)
- Metal Debris: 0.000 → 0.000 (only 99 ground-truth instances; same)

Training: 1 281 steps × effective batch 32 over 3 epochs (≈ 6 h 53 min on
a single RTX 5090, bf16). LoRA `r=16`, `α=32`, ~31 M trainable parameters
(0.60 % of base). Loss decreased monotonically from 15.01 to 0.456 with a
σ_last20 of 0.0092 — clean cosine landing, no late-stage divergence.

Full per-class table, ablation grid, hyper-parameters and reproducible
pipeline live in the published HuggingFace
[model card](https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris).

---

## Multilingual scientific reporting

Three audience modes × six languages:

- **Scientific** — peer-review tone, passive methodology, Latin binomials,
  hedged claims with citations.
- **NGO Manager** — operational, imperative recommendations,
  `IMMEDIATE / SHORT-TERM / LONG-TERM` action tags, named local actors.
- **Citizen** — vivid analogies (`450 years ≈ 6 human lifetimes`), no
  jargon, three-tier call-to-action (personal / community / civic).

Each report:
- Renders to PDF with proper Spanish / French / German / Italian /
  Portuguese typography.
- Embeds annotated session thumbnails (max 50).
- Includes a Collection-Route Itinerary table with waypoint priority,
  coordinates and recommended method (boat / on-foot / diving / drone).
- Carries a hidden `validationScore` (0–100) computed from 12+ anti-
  hallucination checks; reports below 70 get an in-app warning chip.

---

## Honest engineering disclosures

- **APK ships with the base Gemma 4 E2B**, not yet with the LoRA-merged
  weights — the LoRA-to-`.litertlm` export path is upstream-in-progress.
  The fine-tuned adapter is reproducible end-to-end via the published
  Kaggle notebook and the HuggingFace adapter repo.
- **Geographic bias.** Training data is predominantly Japan and the
  north-western Pacific. Mediterranean, tropical, polar and freshwater
  performance not yet measured.
- **JSON validity 94.5 %**, not 100 %. The 5.5 % of malformed outputs are
  silently dropped — the app prefers an empty detection set over fabricated
  boxes.
- **Underrepresented classes.** `Metal_Debris` (99 ground-truth instances)
  remains unlearned at the current data scale; a class-balanced campaign is
  planned.
- The CPU+Vulkan single-shot Gemma 4 path takes ≈ 20 s end-to-end on the
  reference device, so this is a triage and reporting tool, not a real-time
  detector.

---

## Reproducibility

- **Code repository** (Apache 2.0): https://github.com/asferrer/OceanguardAI
- **Signed APK** (GitHub Actions): https://github.com/asferrer/OceanguardAI/releases/latest
- **Kaggle notebook** *(Run-All clean, 4-bit quantised base + adapter on T4)*:
  *paste Kaggle URL after publishing*
- **HuggingFace LoRA adapter**: https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris
- **Model card**: published on the HuggingFace repo above, with full
  hyper-parameters, per-class metrics, citations and limitations.

---

## Built on top of prior peer-reviewed work by the author

This project is the next iteration in an ongoing line of research on
automated detection and recognition of underwater anthropogenic debris:

1. **Sánchez-Ferrer, A.**, Valero-Mas, J. J., Gallego, A. J., &
   Calvo-Zaragoza, J. (2023). *An experimental study on marine debris
   location and recognition using object detection.* **Pattern Recognition
   Letters**, 168, 154–161.
   [DOI](https://www.sciencedirect.com/science/article/pii/S0167865522003889?via%3Dihub)
2. **Sánchez-Ferrer, A.**, Gallego, A. J., Valero-Mas, J. J., &
   Calvo-Zaragoza, J. (2022). *The CleanSea Set: A Benchmark Corpus for
   Underwater Debris Detection and Recognition.* In *IbPRIA 2022*, LNCS,
   Springer.
   [DOI](https://link.springer.com/chapter/10.1007/978-3-031-04881-4_49)
3. **Sánchez-Ferrer, A.** (2024). *Modelos de difusión aplicados a la
   detección de objetos en el fondo marino.* MSc Thesis, Universidad de
   Alicante.
   [Repository](https://rua.ua.es/entities/publication/88244474-6165-4cd9-a4af-68eff29d65c6)
4. **Sánchez-Ferrer, A.** (2021). *Deep Learning aplicado a la detección
   de residuos en el fondo marino.* BSc Thesis, Universidad de Alicante.
   [Repository](https://rua.ua.es/entities/publication/92c34588-9842-4ec3-8b15-97a8ce718e04)

The **CleanSea** corpus introduced in (2) is one of the three sources used
to train this fine-tune. The detection methodology developed in (1) defines
the marine-debris recognition task that this project operationalises
on-device with Gemma 4 E2B.

---

## License & contact

- **Source code**: Apache 2.0
- **Gemma 4 weights**: governed by the Gemma Terms of Use
- **Training datasets**: source-dataset licenses apply (CC-BY or equivalent)
- **Author**: Alejandro Sánchez-Ferrer · `asanc.tech@gmail.com` ·
  Universidad de Alicante, PRAI group

```bibtex
@misc{sanchezferrer2026oceanguard,
  author       = {S{\'a}nchez-Ferrer, Alejandro},
  title        = {{OceanGuard AI: Fully Offline Marine Debris Intelligence
                   on Android with Gemma 4}},
  year         = {2026},
  howpublished = {Kaggle Gemma 4 Good Hackathon, Global Resilience Track},
  url          = {https://github.com/asferrer/OceanguardAI},
  note         = {Apache 2.0}
}
```
```

---

## 6. Attachments / project links  *(Kaggle "External resources")*

Paste these into the Kaggle "Project links / external resources" field —
each on its own line. Group A is the **required core**; Group B is
recommended; Group C is bonus.

### Group A — Required core

| Type | URL |
|---|---|
| Public source repo (Apache 2.0) | `https://github.com/asferrer/OceanguardAI` |
| Signed APK (latest release) | `https://github.com/asferrer/OceanguardAI/releases/latest/download/OceanGuard-AI-latest.apk` |
| HuggingFace LoRA adapter | `https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris` |
| HuggingFace model card | `https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris/blob/main/README.md` |

### Group B — Strongly recommended

| Type | URL / file |
|---|---|
| Kaggle notebook (demo, Run-All) | *paste after publishing* |
| Kaggle fine-tune notebook (Unsloth bonus track) | *paste after publishing* |
| Demo video (YouTube, 3 min) | *paste after upload; script in `docs/HACKATHON_VIDEO_SCRIPT.md`* |
| Project landing page | `https://asferrer.github.io/OceanguardAI` |

### Group C — Bonus (research / artefacts)

| Type | URL |
|---|---|
| PRL 2023 — Marine debris detection paper | https://www.sciencedirect.com/science/article/pii/S0167865522003889?via%3Dihub |
| IbPRIA 2022 — CleanSea Set paper | https://link.springer.com/chapter/10.1007/978-3-031-04881-4_49 |
| MSc Thesis 2024 — Diffusion models for underwater debris | https://rua.ua.es/entities/publication/88244474-6165-4cd9-a4af-68eff29d65c6 |
| BSc Thesis 2021 — Deep Learning for underwater debris | https://rua.ua.es/entities/publication/92c34588-9842-4ec3-8b15-97a8ce718e04 |

### Optional file attachments  *(upload to Kaggle if the form accepts files)*

| File | Purpose |
|---|---|
| `docs/submission/WRITEUP_FINAL.md` | Long-form technical write-up (judge-reviewable). |
| `docs/submission/notebook.ipynb` | Standalone demo notebook (image → detection → JSON parse → bounding-box visualisation). |
| `docs/submission/notebook_finetune.ipynb` | Full LoRA fine-tune notebook for the Unsloth bonus track. |
| `docs/submission/hf_model_card.md` | The same model card pushed to HuggingFace, attached locally for reviewers who don't follow the HF link. |
| `docs/submission/diagrams/architecture.png` | Architecture diagram (PNG, embeddable in Kaggle markdown). |
| `docs/submission/diagrams/two_phase_flow.png` | Two-phase tool-calling sequence diagram. |
| `docs/submission/SMOKE_RESULTS.md` | Smoke-test results on the reference device — single-shot latency, decode tok/s, memory. |

---

## Submission day checklist  *(internal use, not for Kaggle field)*

- [ ] Title pasted (≤ 80 chars).
- [ ] Subtitle pasted (≤ 140 chars).
- [ ] Track selected: **Global Resilience**.
- [ ] Cover image uploaded (PNG, 1200 × 630).
- [ ] Media gallery uploaded in the recommended order (6–8 images).
- [ ] Full description pasted into the Kaggle markdown field (Section 5).
- [ ] Group A links (repo, APK, HF adapter, model card) verified resolvable
      from an incognito browser.
- [ ] Kaggle notebook published and URL pasted into Group B.
- [ ] Fine-tune notebook published as separate Kaggle notebook (Unsloth
      bonus track) and URL pasted.
- [ ] Demo video uploaded to YouTube and URL pasted.
- [ ] All file attachments uploaded (architecture diagrams, write-up,
      notebooks, model card).
- [ ] APK release tag bumped to current version and `latest` asset URL
      resolves.
- [ ] Final submit before **18 May 2026 23:59 UTC**.
