# Kaggle Gemma 4 Good Hackathon -- Writeup

---

## Basic Details

**Title** (max 80 chars):
```
OceanGuard AI: On-Device Marine Debris Detection with Gemma 4 Vision
```

**Subtitle** (max 140 chars):
```
A fully offline Android app that uses Gemma 4 as a visual object detector with bounding boxes -- no cloud, no API keys, 6 languages.
```

**Track**: Global Resilience

---

## Project Description

*(Copy below into the Kaggle writeup editor)*

### The Problem

Every year, over **8 million tons of plastic and debris** enter the world's oceans. Marine pollution kills over 1 million seabirds and 100,000 marine mammals annually. Monitoring this crisis relies on expensive research vessels, satellite imagery with limited resolution, or cloud-dependent apps that fail in remote coastal areas -- exactly where monitoring is needed most.

Conservation teams in the field -- marine biologists, dive cleanup crews, coastal rangers -- need a tool that works **right here, right now**, without waiting for a cell signal or paying for cloud inference.

### The Solution

**OceanGuard AI** is a fully offline Android application that runs multiple AI models **entirely on-device** to detect, classify, map, and report marine debris from photos and live camera feeds.

The app combines:
- **RT-DETRv2** for fast detection (~30ms per frame)
- **Gemma 4 E2B Vision** for deep analysis with precise bounding boxes (~4-10s)
- **Gemma 4 E2B + Qwen 3.5** for generating comprehensive environmental reports

No internet required. No data leaves the device. Works in remote atolls, underwater housings, and research vessels with zero connectivity.

### Key Innovation: Gemma 4 as a Visual Object Detector

Most VLM applications use language models for classification or text descriptions. **OceanGuard AI pushes further** -- Gemma 4 E2B generates precise bounding boxes using its native `box_2d` coordinate system.

The model receives a photo and a detection prompt, then outputs structured JSON:

```json
[
  {"box_2d": [120, 340, 450, 680], "label": "Plastic_Debris"},
  {"box_2d": [50, 100, 200, 300], "label": "Bottle"}
]
```

Coordinates are on a 1000x1000 grid in `[y_min, x_min, y_max, x_max]` format. The app converts these to normalized `[0,1]` bounding boxes and renders them visually -- just like a traditional object detector, but powered by a VLM.

**Why this matters:**
- **No custom training required** -- Gemma 4's zero-shot detection finds debris types it was never specifically trained on
- **Richer context** -- The VLM understands scene semantics (e.g., "fishing net tangled on coral" vs. "fishing net on sand")
- **Complementary pipeline** -- Fast RT-DETRv2 for real-time scanning, Gemma 4 Vision for deep analysis on-demand

### How Gemma 4 Is Used

Gemma 4 E2B powers **two core features**:

**1. Visual Object Detection** (`Gemma4VisionDetector`)
- Receives a photo via `Content.ImageBytes` (JPEG)
- Uses a structured detection prompt requesting JSON with `box_2d` coordinates
- Parses the response, converts coordinates from 1000-grid to normalized [0,1]
- Maps labels to the 8 debris classes (Bottle, Can, Fishing_Net, Glove, Mask, Metal_Debris, Plastic_Debris, Tire)
- Returns `List<DetectionResult>` with the same interface as RT-DETRv2

**2. Environmental Report Generation** (`LiteRTTextEngine`)
- Generates detailed environmental impact reports from detection data
- Produces health scores, risk assessments, material breakdowns, and cleanup recommendations
- Supports streaming token output for real-time progress display
- 1.65x faster decode than llama.cpp on Exynos 2200

Both features run on **LiteRT-LM** with automatic GPU detection (`Backend.GPU()` with CPU fallback).

### Dual Detection Architecture

```
            User selects detection mode in Settings
                          |
          +---------------+---------------+
          |                               |
   [RT-DETRv2 TFLite]         [Gemma 4 E2B Vision]
    ~30ms, 8 classes            ~4-10s, open-vocab
    640x640 fixed               box_2d JSON output
    NNAPI + XNNPACK             LiteRT-LM (CPU/GPU)
          |                               |
          +---------------+---------------+
                          |
                  List<DetectionResult>
                  (same interface)
                          |
          +---------------+---------------+
          |                               |
   [Qwen 3.5 llama.cpp]       [Gemma 4 E2B LiteRT-LM]
    0.8B / 2B / 4B tiers        Report generation
    Vulkan GPU ready             GPU auto-detect
          |                               |
          +---------------+---------------+
                          |
              Environmental Report
              + Health Score + Maps
```

### Complete Feature Set

**Detection & Analysis**
- Dual detection pipeline: RT-DETRv2 (fast, ~30ms) + Gemma 4 Vision (deep, ~4-10s)
- 8 marine debris classes with bounding box visualization
- Live camera detection with real-time annotations
- Batch photo and video processing
- Configurable confidence thresholds

**Reports & Intelligence**
- AI-generated environmental impact reports (Gemma 4 + Qwen 3.5)
- Ecosystem health scoring (0-100 scale)
- Material breakdown analysis (Plastic, Metal, Fabric, Rubber, etc.)
- Risk assessment and cleanup priority recommendations
- PDF/CSV/GeoJSON export in 6 languages

**Gamification -- MarineDex**
- 11 collectible marine debris species (like a field guide)
- 25 achievement badges across 6 categories
- Personal conservation impact tracker
- Streak tracking and milestone celebrations

**Mapping & Spatial Analysis**
- MapLibre + OpenFreeMap (no API key, fully offline-capable)
- GPS-tagged detection sessions plotted on interactive maps
- Zone aggregation for area-level pollution hotspot identification
- Date-based filtering and spatial data export

**Accessibility & Inclusivity**
- 6 languages: English, Spanish, French, German, Italian, Portuguese
- Works on mid-range devices (4GB RAM, Android 8.0+)
- GPU auto-detection with CPU fallback (no manual configuration)
- All models downloadable in-app from HuggingFace (Apache 2.0, no auth)
- Touch targets >= 56dp (glove-friendly for dive operations)
- 100% offline -- no internet, no API keys, no cloud costs

**Data Contribution**
- Optional anonymous contribution of detection data to marine research
- COCO annotation format for interoperability with ML research pipelines
- WiFi-only upload constraint for data-conscious users

### Technical Execution

| Component | Technology | Details |
|-----------|-----------|---------|
| Language | Kotlin 2.3.20 | Single-activity, Jetpack Compose |
| UI | Compose BOM 2026.03.00 | Material3, animations, dark mode |
| Camera | CameraX 1.6.0 | CameraPipe backend |
| Detection | TFLite 2.17.0 | RT-DETRv2 FP16, NNAPI+XNNPACK |
| VLM Detection | LiteRT-LM 0.10.0 | Gemma 4 E2B, GPU auto-detect |
| Text Gen | llama.cpp (JNI) | Qwen 3.5, flash attention, Vulkan ready |
| Database | Room 2.8.4 | Offline persistence, type converters |
| Maps | MapLibre 0.12.1 | OpenFreeMap, no API key |
| Location | Play Services + Photon | EXIF GPS fallback chain |

### Performance (Samsung Galaxy S22 Ultra -- Exynos 2200)

| Metric | RT-DETRv2 | Gemma 4 Vision |
|--------|-----------|----------------|
| Inference latency | ~30ms (NNAPI) | ~4-10s (LiteRT-LM) |
| Model size on disk | 83 MB (bundled) | 2.6 GB (downloaded) |
| RAM usage | ~200 MB | ~3 GB |
| Bounding boxes | 150 queries, NMS | box_2d JSON, zero-shot |
| Detection classes | 8 (trained on COCO subset) | 8 (zero-shot prompt) |

### Impact

**Who benefits:**
- **Marine biologists** -- Automated detection replaces manual visual surveys
- **Dive cleanup crews** -- Real-time identification during underwater operations
- **Coastal rangers** -- Patrol monitoring without cloud infrastructure
- **Citizen scientists** -- Low-barrier contribution to marine conservation data
- **Researchers** -- COCO-format exports feed directly into ML training pipelines

**Why it matters:**
- **No cost barrier** -- Free app, free models (Apache 2.0), no cloud fees
- **No connectivity barrier** -- Works in remote locations without internet
- **No language barrier** -- 6 languages covering ~2 billion native speakers
- **No expertise barrier** -- Gamification makes marine monitoring accessible to anyone with a phone

### Research Context

OceanGuard AI is part of a doctoral research project on applying on-device AI to marine conservation. The training dataset combines CleanSea, Ocean_garbage, and Neural_Ocean datasets in COCO format, with custom augmentation for underwater conditions.

### Open Source

The entire project is open source under **Apache License 2.0**:
- App source code, ML inference pipeline, and model conversion scripts
- All models (RT-DETRv2, Qwen 3.5, Gemma 4 E2B) are Apache 2.0 licensed
- No proprietary dependencies -- fully reproducible

---

## Project Links

```
GitHub Repository: https://github.com/AlejandroSanchezFerrer/OceanguardAI-App
Training Repository: https://github.com/AlejandroSanchezFerrer/OceanguardAI
```

---

## Suggested Media Gallery

1. App home screen showing model status and quick actions
2. Photo detection with RT-DETRv2 bounding boxes
3. Photo detection with Gemma 4 Vision bounding boxes (same image, comparing both)
4. Live camera detection in action
5. AI-generated environmental report
6. MarineDex collection screen
7. Interactive map with detection pins
8. Settings screen showing detector mode selector
9. Short demo video (~2-3 min) on YouTube showing the full flow

---

## Cover Image Concept (560x280)

Suggested composition:
- Left side: underwater photo with marine debris (bottle, net)
- Center: phone mockup showing OceanGuard AI with detection boxes
- Right side: Gemma 4 logo + "Powered by Gemma 4 -- Google AI Edge"
- Bottom strip: "100% Offline | 8 Debris Classes | 6 Languages | Open Source"
- Color palette: deep ocean blue (#0A1628) to teal (#14B8A6) gradient
