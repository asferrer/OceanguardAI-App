<h1 align="center">OceanGuard AI &mdash; Android Application</h1>

<p align="center">
  <strong>On-device marine debris intelligence powered by Gemma 4.</strong><br/>
  <sub>100% offline &middot; agentic tool-calling reports &middot; 6 languages &middot; Apache 2.0</sub>
</p>

<p align="center">
  <a href="https://github.com/asferrer/OceanguardAI/releases/latest">
    <img src="https://img.shields.io/github/v/release/asferrer/OceanguardAI?style=for-the-badge&color=00d4ff&label=Latest%20APK" alt="Latest APK"/>
  </a>
  &nbsp;
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 8.0+"/>
  &nbsp;
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue?style=for-the-badge" alt="Apache 2.0"/>
  &nbsp;
  <img src="https://img.shields.io/badge/Powered%20by-Gemma%204-00d4ff?style=for-the-badge" alt="Powered by Gemma 4"/>
</p>

<p align="center">
  <a href="https://github.com/asferrer/OceanguardAI/releases/latest/download/OceanGuard-AI-latest.apk"><strong>Download APK</strong></a>
  &nbsp;&middot;&nbsp;
  <a href="https://asferrer.github.io/OceanguardAI"><strong>Landing page</strong></a>
  &nbsp;&middot;&nbsp;
  <a href="https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris"><strong>LoRA adapter</strong></a>
  &nbsp;&middot;&nbsp;
  <a href="docs/submission/WRITEUP_FINAL.md"><strong>Writeup</strong></a>
</p>

---

## About

OceanGuard AI is a fully offline Android application that detects and reports marine debris using **Gemma 4 E2B** running entirely on the device via the LiteRT-LM runtime. No internet connection is required after the initial model download, no images leave the phone, and every number in every generated report is sourced from a typed Kotlin function &mdash; never invented by the model.

This repository contains the **Android source code**, the **LoRA fine-tuning pipeline**, the **model-conversion utilities** and the **hackathon submission notebooks**. The companion repository [`asferrer/OceanguardAI`](https://github.com/asferrer/OceanguardAI) hosts the project landing page and the signed-APK releases.

Submitted to the **Kaggle Gemma 4 Good Hackathon &mdash; Global Resilience Track** and the **Unsloth fine-tuning bonus track**.

## Why Gemma 4

A single open-weights model drives both perception and reporting on the phone:

- **Open-vocabulary detection.** Gemma 4 Vision emits `box_2d` bounding boxes with free-text labels and material guesses on a 1000&times;1000 normalised grid, expanding the deterministic 8-class debris taxonomy into 50 fine-grained sub-types mapped onto 11 ecological-impact families.
- **Grounded report generation.** Gemma 4 Text uses native function calling to query Kotlin `@Tool` methods (debris counts, percentages, GPS waypoints, ecological impacts, temporal trends) and writes scientific-grade reports in six languages and three audience voices, with zero fabricated statistics.
- **One on-device weight.** A single shared engine instance powers both modes &mdash; no cloud round-trip, no per-image cost, no telemetry.

## Single-Conversation Agentic Tool Calling

Open VLMs on a phone routinely hallucinate the very statistics environmental policy depends on &mdash; degradation times, percentage breakdowns, risk scores, GPS waypoints. A government cannot defensibly act on a fabricated `23 %`. OceanGuard AI flips the contract:

```
USER &rarr; "Generate report"
  &darr;
[ One Gemma 4 Conversation &middot; KV cache persists across every turn ]
  &darr;
Step 1 &middot; Tool dispatch (warm KV)
  &middot; ToolAgentLoop &middot; MAX_TOOL_ROUNDS = 14 &middot; automaticToolCalling = false
  &middot; 8 typed Kotlin @Tool methods: debris-summary, material-breakdown,
    type-breakdown, risk-assessment, collection-waypoints,
    survey-statistics, ecological-impacts, temporal-trend
  &middot; onToolCallStarted &rarr; UI banner "Querying &lt;tool&gt;&hellip;"
  &darr; (same conversation, no reset)
Step 2 &middot; Report writing (warm KV)
  &middot; Tool responses already in cache &mdash; tokens stream live to the UI
  &darr;
Post-process &middot; repairHallucinations(canon) &mdash; rewrites paraphrased rows
  &darr;
ReportValidator &middot; 12+ checks &rarr; validationScore 0&ndash;100
  &darr;
Grounded Report &middot; Markdown &middot; PDF &middot; CSV &middot; GeoJSON
```

Every percentage is computed once in Kotlin, every risk score is read from a Kotlin map, and the model has no opportunity to mis-tokenise a number it never authored.

## Features

| Detection | Agentic Reporting |
|---|---|
| Single-shot deep analysis (full Gemma 4 Vision pass) | Native function calling on Gemma 4 |
| Image, gallery, and batch input | Live token stream with per-tool banners |
| 50 fine-grained debris sub-types &rarr; 11 canonical families | Scientific / NGO Manager / Citizen voices |
| Corner-bracket bounding boxes with glow + pulse | 6 languages (EN, ES, FR, DE, IT, PT) |

| Mapping & Tracking | Privacy & Gamification |
|---|---|
| GPS geotagging (EXIF &rarr; device GPS) | 100% offline, no telemetry, no accounts |
| Offline MapLibre + OpenFreeMap, no API key | All data on-device, deletable from settings |
| Automatic hotspot clustering, ecosystem health | Pixel-art MarineDex with achievements |

## On-Device Stack

| Component | Technology |
|---|---|
| **Vision &amp; report model** | **Gemma 4 E2B** via LiteRT-LM 0.11 &mdash; single shared engine, ~2.6&nbsp;GB on disk |
| **Tool runtime** | Native function calling on Gemma 4 &middot; 8 typed Kotlin `@Tool` methods &middot; `automaticToolCalling = false` agent loop |
| **UI** | Jetpack Compose + Material 3 &middot; Kotlin 2.2 &middot; min SDK 26 (Android 8.0) |
| **Camera** | CameraX 1.5 &mdash; hardware-rotation-aware capture |
| **Database** | Room 2.7 + DataStore preferences |
| **Maps** | MapLibre Compose + OpenFreeMap &mdash; no API key, offline tiles |

Optional alternative engines are kept as legacy fall-backs and selectable from **Settings &rarr; VLM provider**: an RT-DETRv2 TFLite path for fast (~30&nbsp;ms) lightweight detection on devices where Gemma 4 Vision is too heavy, and a llama.cpp + Qwen 3.5 path for text generation when LiteRT-LM is not available.

## Domain-Adapted LoRA Adapter

A Stage-1 / Stage-2 LoRA fine-tune of `google/gemma-4-E2B-it` on a marine-debris corpus (CleanSea + Ocean_garbage + Neural_Ocean, ~13.6&nbsp;k images stratified by class) is published as a reproducible artefact:

- **Adapter:** [`asferrer/gemma-4-E2B-it-oceanguard-marine-debris`](https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris) on Hugging Face
- **Notebook:** [`docs/submission/notebook_finetune.ipynb`](docs/submission/notebook_finetune.ipynb) &mdash; runs end-to-end on Kaggle (T4 x2), Unsloth FastVisionModel stack
- **Reported delta:** mAP@0.5 = 0.3256 (vs 0.1067 base &mdash; **+205&nbsp;% relative**, &Delta;&nbsp;+0.2189) on a 200-image stratified held-out test split. Winning experiment: `exp12_vision_lora` (LoRA on both the language tower and the SigLIP2 vision encoder).

The APK currently ships with the base Gemma 4 E2B weights; LoRA-merged `.litertlm` export is upstream-in-progress.

## Installation (end users)

> **Requirements:** Android 8.0+, ~200&nbsp;MB storage for the APK and ~2.6&nbsp;GB additional for the Gemma 4 model (downloaded in-app on first deep analysis).

1. [Download the latest signed APK](https://github.com/asferrer/OceanguardAI/releases/latest/download/OceanGuard-AI-latest.apk).
2. On your Android device: **Settings &rarr; Apps &rarr; &hellip; &rarr; Special access &rarr; Install unknown apps** &rarr; enable for your browser.
3. Open the downloaded APK and tap **Install**.

Google Play Protect may show a warning &mdash; this is normal for apps distributed outside the Play Store. Tap **"Install anyway"** to proceed.

## Build from source

```bash
git clone https://github.com/asferrer/OceanguardAI-App.git
cd OceanguardAI-App/android
./gradlew installDebug
```

**Requirements:** JDK 17 (Temurin or Liberica), Android SDK 34+, ~6&nbsp;GB free RAM during the build.

The Gemma 4 E2B `.litertlm` (~2.6&nbsp;GB) is **downloaded in-app** by `VlmModelManager` from `huggingface.co/litert-community/gemma-4-E2B-it-litert-lm` the first time the user runs single-shot deep analysis or pulls the in-app benchmark &mdash; no `adb push` step is required.

### Release builds

Releases are cut by pushing a `v*` tag; the workflow `.github/workflows/release.yml` builds a signed APK in GitHub Actions and publishes it to `asferrer/OceanguardAI/releases/latest`. The signing keystore lives in a GitHub Secret; never commit `*.jks` or `keystore.properties`.

## Repository Layout

```
OceanguardAI-App/
+-- android/                              # Android project root
|   +-- app/
|   |   +-- src/main/
|   |   |   +-- java/com/oceanguard/
|   |   |   |   +-- OceanGuardApp.kt          # App lifecycle + model management
|   |   |   |   +-- data/                     # Room entities, DAOs, repositories
|   |   |   |   +-- inference/                # Detection + report engines + ToolAgentLoop
|   |   |   |   +-- service/                  # Foreground services
|   |   |   |   +-- ui/                       # Compose screens & components
|   |   |   |   +-- utils/                    # Image, geocoding, export
|   |   |   +-- assets/models/                # Bundled TFLite (RT-DETRv2 fallback)
|   |   |   +-- res/values[-xx]/              # i18n (en, es, fr, de, it, pt)
|   |   +-- build.gradle.kts
|   +-- gradle.properties
|   +-- settings.gradle.kts
+-- conversion/                           # Python model-conversion utilities
+-- finetune/                             # LoRA fine-tuning pipeline (Unsloth)
+-- docs/                                 # Architecture, Gemma 4 detection, hackathon
|   +-- submission/                       # Kaggle writeup, notebooks, model card
+-- scripts/                              # setup-models.sh, setup-models.ps1, test_notebooks.ps1
+-- OceanguardAI/                         # Submodule -> landing page repo
+-- requirements-mobile.txt
+-- LICENSE                               # Apache 2.0
```

## Documentation

| Doc | Purpose |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | System architecture &mdash; inference pipelines, model lifecycle, data flow |
| [`docs/GEMMA4_DETECTION.md`](docs/GEMMA4_DETECTION.md) | `box_2d` prompt engineering, JSON parsing, coordinate conversion |
| [`docs/submission/WRITEUP_FINAL.md`](docs/submission/WRITEUP_FINAL.md) | Long-form hackathon writeup |
| [`docs/submission/KAGGLE_SUBMISSION_FORM.md`](docs/submission/KAGGLE_SUBMISSION_FORM.md) | Drop-in copy for every Kaggle form field |
| [`docs/submission/hf_model_card.md`](docs/submission/hf_model_card.md) | Hugging Face model card for the LoRA adapter |
| [`docs/submission/UPLOAD_EVAL_SET.md`](docs/submission/UPLOAD_EVAL_SET.md) | Procedure for staging and publishing the 1000-image eval hold-out on Hugging Face |
| [`docs/submission/SMOKE_RESULTS.md`](docs/submission/SMOKE_RESULTS.md) | On-device latency &amp; memory smoke tests |

## Privacy

All processing happens **entirely on your device**. No data is collected, transmitted, or shared with any server.

| Data | Storage | Shared? |
|---|---|---|
| Photos &amp; videos | Device only | Never |
| GPS coordinates | Device only | Never |
| Detection &amp; report results | Device only | Never |
| Gemma 4 model weights | Device only | Downloaded once from Hugging Face, then never re-fetched |

## Research

OceanGuard AI is built on peer-reviewed research and ongoing doctoral work at the **Universidad de Alicante**.

- **Sánchez-Ferrer, A.**, Valero-Mas, J. J., Gallego, A. J., &amp; Calvo-Zaragoza, J. (2023). *An experimental study on marine debris location and recognition using object detection.* **Pattern Recognition Letters**. [DOI](https://www.sciencedirect.com/science/article/pii/S0167865522003889)
- **Sánchez-Ferrer, A.**, Gallego, A. J., Valero-Mas, J. J., &amp; Calvo-Zaragoza, J. (2022). *The CleanSea Set: A Benchmark Corpus for Underwater Debris Detection and Recognition.* **IbPRIA 2022**, LNCS Springer. [DOI](https://link.springer.com/chapter/10.1007/978-3-031-04881-4_49)
- **Sánchez-Ferrer, A.** (2024). *Modelos de difusión aplicados a la detección de objetos en el fondo marino.* MSc Thesis, Universidad de Alicante. [RUA](https://rua.ua.es/entities/publication/88244474-6165-4cd9-a4af-68eff29d65c6)
- **Sánchez-Ferrer, A.** (2021). *Deep Learning aplicado a la detección de residuos en el fondo marino.* BSc Thesis, Universidad de Alicante. [RUA](https://rua.ua.es/entities/publication/92c34588-9842-4ec3-8b15-97a8ce718e04)

## Related Repositories

| Repo | Purpose |
|---|---|
| [`asferrer/OceanguardAI`](https://github.com/asferrer/OceanguardAI) | Landing page + signed-APK GitHub Releases |
| [`asferrer/gemma-4-E2B-it-oceanguard-marine-debris`](https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris) | LoRA adapter on Hugging Face |

## License

The application source code is released under the **Apache License 2.0** &mdash; see [LICENSE](LICENSE). The Gemma 4 weights are governed by the [Gemma Terms of Use](https://ai.google.dev/gemma/terms). The LoRA adapter training code is Apache 2.0; the merged weights are governed by the same Gemma Terms.

## Contact

Alejandro Sánchez-Ferrer &middot; `asanc.tech@gmail.com` &middot; Universidad de Alicante, PRAI group

```bibtex
@misc{sanchezferrer2026oceanguard,
  author       = {S{\'a}nchez-Ferrer, Alejandro},
  title        = {{OceanGuard AI: Fully Offline Marine Debris Intelligence
                   on Android with Gemma 4}},
  year         = {2026},
  howpublished = {Kaggle Gemma 4 Good Hackathon, Global Resilience Track},
  url          = {https://github.com/asferrer/OceanguardAI-App},
  note         = {Apache 2.0}
}
```
