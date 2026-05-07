# Kaggle Submission Form - OceanGuard AI

**Hackathon**: Gemma 4 Good Hackathon
**Track**: Global Resilience (Climate)
**Submission window**: 16-18 May 2026
**Author**: Alejandro Sanchez Ferrer

> Drop-in copy for every required Kaggle field. Each section is self-contained; copy the recommended option and paste verbatim. [VERIFY: ...] markers flag items that require human confirmation before submission.

---

## Section 1 - Title (max 100 chars)

**Recommended (86 chars):**

> OceanGuard AI: Fully Offline Marine Debris Intelligence on Android, Powered by Gemma 4

**Alternative A (66 chars):**

> OceanGuard AI: Offline Gemma 4 Marine Pollution Reports on a Phone

**Alternative B (77 chars):**

> OceanGuard AI: On-Device Gemma 4 Tool Calling for Marine Conservation Reports

---

## Section 2 - Short description / tagline (max 250 chars)

**Recommended (234 chars):**

> First fully-offline marine debris intelligence toolkit for consumer Android. RT-DETRv2 detection plus Gemma 4 E2B native tool calling produce hallucination-free, multilingual scientific reports in six languages, with zero cloud calls.

**Alternative A (222 chars):**

> Offline Android app that turns any phone into a marine biologist. Hybrid RT-DETRv2 plus Gemma 4 pipeline with two-phase native tool calling delivers grounded six-language conservation reports without a single network call.

**Alternative B (242 chars):**

> OceanGuard AI runs marine debris detection and Gemma 4 grounded reporting entirely on a Samsung Galaxy S22 Ultra. Two-phase native tool calling eliminates fabricated statistics; six languages of PDF and Markdown export; Apache 2.0 end-to-end.

---

## Section 3 - Long description (200-300 words)

**Word count: 287**

Marine plastic pollution is a planetary-scale resilience crisis, and the field workers closest to it (coastal NGO patrols, dive cleanup crews, government rangers) operate exactly where cloud AI fails: low connectivity, high data cost, and zero tolerance for fabricated statistics in policy reports.

OceanGuard AI is a single-Activity Jetpack Compose Android application that runs the full marine debris intelligence pipeline on the device, with no network call after initial model download. The user captures or imports a photo and receives bounding-box detections with confidence, a 0-100 ecosystem health score, GPS-tagged geospatial aggregation, and executive-grade Markdown or PDF reports tuned to three audiences (Scientific, NGO Manager, Citizen) in six languages (English, Spanish, French, German, Italian, Portuguese).

The system uses a hybrid pipeline. RT-DETRv2 (TensorFlow Lite, 8-class custom detector trained on CleanSea, Ocean_garbage, and Neural_Ocean) provides low-latency bounding boxes. Gemma 4 E2B (LiteRT-LM 0.10.0) extends the open-vocabulary surface to a 50-class taxonomy mapped onto 11 ecological-impact families, and generates the multilingual reports.

The technical centerpiece is a two-phase native tool calling architecture built on the Gemma 4 native tool API. PHASE 1 forces the model to dispatch a required-tool set against a typed Kotlin interface backed by Room v10. PHASE 2 starts a fresh conversation with a pre-rendered Markdown data bundle as the only context, so the writer never sees draft contamination. A section-aware repair pass reconciles residual label drift. Every percentage, degradation time, and risk score traces back to deterministic Kotlin computations, not to model token sampling.

Reproducibility is end-to-end Apache 2.0: signed APK on GitHub Releases, Kaggle-runnable notebook, and tagged source builds via GitHub Actions. The reference device is a Samsung Galaxy S22 Ultra (Exynos 2200, CPU-only XNNPACK).

---

## Section 4 - Track justification (Global Resilience, ~150 words)

**Word count: 156**

Marine plastic pollution is a Global Resilience problem on three axes. First, climate and biodiversity: an estimated eight million tonnes of plastic enter the ocean annually (UNEP 2021), implicated in entanglement and ingestion mortality across more than 900 species and in degraded fishery, tourism, and coastal-protection ecosystem services valued above thirteen billion USD per year. Second, geographic equity: a small set of rivers in Asia and Africa is responsible for a disproportionate share of riverine plastic emissions, yet the coastal communities adjacent to those outflows are the same communities with the worst mobile-broadband coverage and the lowest tolerance for cloud-API costs in foreign currency. Third, decision-quality: ministries and NGOs cannot defensibly act on AI-generated environmental statistics that hallucinate. OceanGuard AI directly attacks all three axes by running offline on a consumer phone, with grounded numbers traceable to deterministic database tools, and shipping in six languages.

---

## Section 5 - Links checklist

```
- Code repository (public, Apache 2.0):
  https://github.com/asferrer/OceanguardAI

- Kaggle notebook (Run All reproduces PHASE-1/PHASE-2 split on synthetic survey):
  <PLACEHOLDER - submit notebook URL after publishing to Kaggle>

- Demo video (3-minute pitch, YouTube unlisted or public):
  <PLACEHOLDER - upload after final edit; script at docs/HACKATHON_VIDEO_SCRIPT.md>

- APK release (signed, GitHub Actions reproducible build):
  https://github.com/asferrer/OceanguardAI/releases/latest
  Direct: https://github.com/asferrer/OceanguardAI/releases/latest/download/OceanGuard-AI-latest.apk

- Technical writeup (M6 deliverable, in-repo):
  docs/submission/WRITEUP_FINAL.md

- Architecture diagrams (PNG, embeddable in Kaggle markdown):
  docs/submission/diagrams/architecture.png
  docs/submission/diagrams/two_phase_flow.png
  (SVG sources also present in same dir)

- Reproducibility notebook (in-repo):
  docs/submission/notebook.ipynb

- License: Apache 2.0 (code) - Gemma 4 weights under Gemma Terms (compatible)
```

---

## Section 6 - Team / acknowledgments

- **Author**: Alejandro Sanchez Ferrer
- **Affiliation**: Universidad de Alicante - Doctoral Research Program
- **Contact**: asanc.tech@gmail.com
- **License**: Apache 2.0 (source code and notebook). Model weights distributed under their respective upstream licenses (Gemma 4 under Gemma Terms; RT-DETRv2 fine-tune under Apache 2.0).
- **Acknowledgments**: The training dataset combines the public CleanSea, Ocean_garbage, and Neural_Ocean corpora; we thank their authors. The RT-DETRv2 reference architecture (Lv et al., 2024) and the Google AI Edge LiteRT-LM team are gratefully acknowledged.

**BibTeX citation:**

```bibtex
@misc{sanchezferrer2026oceanguard,
  author       = {Sanchez Ferrer, Alejandro},
  title        = {OceanGuard AI: Fully Offline Marine Debris Intelligence on Android with Gemma 4 Two-Phase Tool Calling},
  year         = {2026},
  institution  = {Universidad de Alicante},
  howpublished = {Kaggle Gemma 4 Good Hackathon, Global Resilience Track},
  url          = {https://github.com/asferrer/OceanguardAI},
  note         = {Apache 2.0}
}
```

---

## Section 7 - Differentiators (5 bullets, fast-skim)

- First fully-offline marine debris intelligence toolkit on consumer Android: zero cloud calls after model download, deployable on coastlines with no broadband.
- Two-phase native tool calling with Gemma 4: a pre-rendered data bundle isolates the writer from PHASE-1 cache contamination and grounds every cited number in a deterministic Kotlin tool, not in token sampling.
- Multilingual scientific reporting in six languages (EN, ES, FR, DE, IT, PT) with three audience voices (Scientific, NGO Manager, Citizen) and PDF, Markdown, and COCO JSON export.
- Hybrid pipeline: RT-DETRv2 TFLite for fast 8-class detection plus Gemma 4 E2B LiteRT-LM 0.10.0 open-vocabulary expansion to a 50-class taxonomy across 11 ecological-impact families.
- Apache 2.0 end-to-end and reproducible: public repo, Kaggle notebook, signed APK on GitHub Releases via tagged GitHub Actions workflow.

---

## Section 8 - Reproducibility statement (~80 words)

**Word count: 86**

To reproduce: clone https://github.com/asferrer/OceanguardAI, run the model download helper (bash scripts/setup-models.sh), then cd android && ./gradlew installDebug against an Android device with API 34+. The Kaggle notebook at docs/submission/notebook.ipynb is Run All-clean: it reproduces the RT-DETRv2 evaluation, the Gemma 4 box_2d parsing, and the PHASE-1 / PHASE-2 tool-calling split on a synthetic survey. The signed reference APK is built deterministically by .github/workflows/release.yml from a tagged commit.

---

## Section 9 - Risk and honest disclosure (~60 words)

**Word count: 70**

The RT-DETRv2 detector is trained on an 8-class superset (Bottle, Can, Fishing_Net, Glove, Mask, Metal_Debris, Plastic_Debris, Tire) merged from CleanSea, Ocean_garbage, and Neural_Ocean; the 50-class taxonomy used by Gemma 4 is an open-vocabulary prompt expansion mapped onto 11 ecological-impact families, not a trained classifier. RT-DETRv2 runs CPU-only on the Exynos 2200 reference device under XNNPACK with 8 threads; LiteRT-LM 0.10.0 hosts Gemma 4 E2B. The APK is GitHub-signed, not Play Store published.

---

## Submission checklist (internal use)

- [ ] Long description copied into Kaggle Description field (Section 3)
- [ ] Tagline copied into short-description / subtitle field (Section 2 recommended)
- [ ] Track selected: Global Resilience
- [ ] Repository link verified resolvable from incognito browser
- [ ] Latest beta APK release published and `latest` asset URL resolves (current: v0.0.7-beta)
- [ ] Kaggle notebook published and URL pasted into Section 5
- [ ] Demo video uploaded to YouTube and URL pasted into Section 5
- [ ] All [VERIFY: ...] markers resolved or stripped before final submit
- [ ] Final submit before 18 May 2026 23:59 UTC
