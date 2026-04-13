# OceanGuard AI

**On-device marine debris detection powered by Gemma 4 Vision -- 100% offline, zero cloud dependency.**

> Kaggle Gemma 4 Good Hackathon -- Global Resilience Track

---

## The Problem

Every year, **8 million+ tons of plastic and debris** enter the world's oceans, devastating marine ecosystems, killing wildlife, and contaminating food chains. Current monitoring methods rely on expensive research vessels, satellite imagery with limited resolution, or cloud-dependent apps that fail in remote coastal areas where connectivity is scarce or nonexistent.

Conservation teams in the field -- marine biologists, dive cleanup crews, coastal rangers -- need a tool that works **right now, right here**, without waiting for a cell signal.

## The Solution

OceanGuard AI is a fully offline Android application that runs **two AI models entirely on-device** to detect, classify, and report marine debris from photos and live camera feeds:

1. **RT-DETRv2** -- A real-time transformer-based object detector (~30ms per frame) for fast scanning
2. **Gemma 4 E2B Vision** -- Google's multimodal VLM used as a **visual object detector** with native `box_2d` bounding box output, providing deep analysis with spatial localization

No internet required. No data leaves the device. Works in remote atolls, underwater housings, and research vessels with zero connectivity.

## Key Innovation: Gemma 4 as a Visual Object Detector

Most VLM applications use large language models for classification or description. OceanGuard AI pushes further -- **Gemma 4 E2B generates precise bounding boxes** using its native `box_2d` coordinate system.

The model outputs structured JSON with `[y_min, x_min, y_max, x_max]` coordinates on a 1000x1000 grid, which are converted to normalized `[0,1]` coordinates and rendered as visual bounding boxes on the image -- just like a traditional object detector, but powered by a VLM.

This means:
- **No custom training required** -- Gemma 4's zero-shot detection finds debris types it was never specifically trained on
- **Richer context** -- The VLM understands scene semantics (e.g., "fishing net tangled on coral" vs "fishing net on sand")
- **Complementary to RT-DETRv2** -- Fast detector for scanning, VLM for deep verification and edge cases

## Features

### Dual Detection Pipeline
- **Fast mode (RT-DETRv2)**: TFLite model, ~30ms inference, NNAPI+XNNPACK delegates, 8 debris classes
- **Deep mode (Gemma 4 Vision)**: LiteRT-LM engine, ~4-10s inference, structured JSON with bounding boxes
- **Orchestrated pipeline**: Fast detection first with immediate UI, optional deep VLM verification

### VLM-Powered Reports
- **Qwen 3.5 family** (0.8B / 2B / 4B): Three quality tiers via llama.cpp with Vulkan GPU acceleration
- **Gemma 4 E2B**: LiteRT-LM runtime, 1.65x faster decode than llama.cpp on Exynos 2200
- Comprehensive environmental impact reports with health scores, risk assessments, and cleanup recommendations
- Streaming token output with real-time progress

### Live Camera Detection
- CameraX integration with real-time frame analysis
- Bounding box overlay with corner brackets, glow effects, and pulse animations
- Works with rear and front cameras

### Gamification -- MarineDex
- Collect discovered debris types like a field guide
- Unlock achievements for conservation milestones
- Track personal cleanup impact over time

### Interactive Maps
- MapLibre + OpenFreeMap (no API key, no cloud dependency)
- GPS-tagged detection sessions plotted on the map
- Zone aggregation for area-level pollution analysis

### Accessibility
- 6 languages: English, Spanish, French, German, Italian, Portuguese
- GPU auto-detection with CPU fallback
- Models downloadable in-app from HuggingFace (Apache 2.0, no auth)
- Works on mid-range devices (4GB RAM minimum)

## Architecture

```
                          +---------------------------+
                          |     OceanGuard AI App     |
                          |    (Jetpack Compose UI)   |
                          +-------------|-------------+
                                        |
                          +-------------|-------------+
                          | DetectionOrchestrator     |
                          |  (parallel pipeline)      |
                          +------|--------------|-----+
                                 |              |
                    +------------|--+    +------|------------+
                    | ObjectDetector |    | VlmVisionEngine  |
                    | (interface)    |    | (interface)       |
                    +-------|-------+    +------|------------+
                            |                   |
               +------------|----------+   +----|----+
               |            |          |   | Llama   |
        +------+--+  +------+---+ +---+--------+  |Vision |
        |PicoDet-S|  |RT-DETRv2  | |Gemma4Vision|  |Engine |
        |(NCNN)   |  |(TFLite)   | |(LiteRT-LM) |  +-------+
        +---------+  +----------+  +------------+
                                        |
                               +--------|--------+
                               |LiteRTTextEngine |
                               | (Engine API)    |
                               | GPU auto-detect |
                               +-----------------+

        Text/Report Generation:
        +-------------------+     +-------------------+
        | LlamaTextEngine   |     | LiteRTTextEngine  |
        | Qwen 3.5 (GGUF)  |     | Gemma 4 E2B       |
        | llama.cpp+Vulkan  |     | LiteRT-LM runtime |
        | 3 tiers:          |     | ~2.6 GB .litertlm |
        |  0.8B / 2B / 4B  |     | GPU auto-detect   |
        +-------------------+     +-------------------+
```

## Tech Stack

| Component | Technology | Details |
|-----------|-----------|---------|
| Language | Kotlin 2.3.20 | Single-activity architecture |
| UI | Jetpack Compose | Material3, BOM 2026.03.00 |
| Camera | CameraX 1.6.0 | CameraPipe backend |
| Database | Room 2.8.4 | KSP2, type converters, migrations |
| Maps | MapLibre 0.12.1 | OpenFreeMap tiles (no API key) |
| Fast Detection | TensorFlow Lite 2.17.0 | RT-DETRv2, NNAPI+XNNPACK |
| VLM Detection | LiteRT-LM | Gemma 4 E2B, GPU auto-detect |
| Text Generation | llama.cpp (JNI) | Qwen 3.5, Vulkan GPU |
| Images | Coil 3.4.0 | Async image loading |
| Location | Play Services 21.3.0 | EXIF GPS fallback chain |
| Geocoding | Photon | Reverse geocoding (offline-capable) |

## Detection Classes

| Class | Material | Examples |
|-------|----------|---------|
| Bottle | Plastic | PET bottles, water bottles |
| Can | Metal | Aluminum cans, tin cans |
| Fishing_Net | Nylon/Rope | Ghost nets, trawl fragments |
| Glove | Fabric/Rubber | Latex gloves, work gloves |
| Mask | Fabric | Surgical masks, cloth masks |
| Metal_Debris | Metal | Scrap metal, wire, pipes |
| Plastic_Debris | Plastic | Bags, wrappers, fragments |
| Tire | Rubber | Vehicle tires, tire fragments |

## Screenshots

> Screenshots will be added before submission.

| Detection | Report | MarineDex | Map |
|-----------|--------|-----------|-----|
| ![Detection](media/screenshots/detection.png) | ![Report](media/screenshots/report.png) | ![MarineDex](media/screenshots/marinedex.png) | ![Map](media/screenshots/map.png) |

## Build and Run

### Prerequisites
- Android Studio (latest stable)
- JDK 17
- Android device or emulator (API 26+, 4GB+ RAM)

### Quick Start

```bash
# Clone the repository
git clone https://github.com/AlejandroSanchezFerrer/OceanguardAI-App.git
cd OceanguardAI-App/android

# Build and install (debug)
./gradlew installDebug
```

### Model Setup

The RT-DETRv2 model is bundled in the APK (~83 MB). VLM models are downloaded on-demand from within the app:

| Model | Size | Runtime | Purpose |
|-------|------|---------|---------|
| RT-DETRv2 FP16 | 83 MB | TFLite | Fast detection (bundled) |
| Qwen 3.5 0.8B | 533 MB | llama.cpp | Fast reports |
| Qwen 3.5 2B | 1.1 GB | llama.cpp | Balanced reports |
| Qwen 3.5 4B | 2.7 GB | llama.cpp | Quality reports |
| Gemma 4 E2B | 2.6 GB | LiteRT-LM | Detection + reports |

All VLM models are Apache 2.0 licensed and downloaded from HuggingFace without authentication.

## Performance (Samsung Galaxy S22 Ultra -- Exynos 2200)

| Metric | RT-DETRv2 | Gemma 4 Vision |
|--------|-----------|----------------|
| Inference latency | ~30ms (NNAPI) | ~4-10s (CPU/GPU) |
| Model size | 83 MB | 2.6 GB |
| RAM usage | ~200 MB | ~3 GB |
| Bounding boxes | Yes (150 queries) | Yes (box_2d JSON) |
| Classes | 8 (trained) | 8 (zero-shot) |

## Project Structure

```
OceanguardAI-App/
  android/
    app/src/main/
      java/com/oceanguard/
        OceanGuardApp.kt              -- Application lifecycle, model management
        inference/
          DetectorInterface.kt        -- ObjectDetector + DetectionResult contracts
          RTDETRInference.kt          -- TFLite RT-DETRv2 detector
          Gemma4VisionDetector.kt     -- Gemma 4 box_2d visual detector
          LiteRTTextEngine.kt         -- LiteRT-LM engine (Gemma 4)
          LlamaTextEngine.kt          -- llama.cpp engine (Qwen 3.5)
          DetectionOrchestrator.kt    -- Dual pipeline coordinator
          ReportGenerator.kt          -- VLM report generation
          VlmModelManager.kt          -- Model download and lifecycle
        data/                         -- Room entities, DAOs, repositories
        ui/                           -- Compose screens and components
        service/                      -- Foreground services
        utils/                        -- Image processing, geocoding, export
      assets/models/                  -- Bundled TFLite models
      res/values[-xx]/                -- i18n strings (6 languages)
  conversion/                         -- Model conversion scripts (Python)
  docs/                               -- Technical documentation
```

## Related Repositories

| Repository | Purpose |
|------------|---------|
| [OceanguardAI](https://github.com/AlejandroSanchezFerrer/OceanguardAI) | Training, evaluation, datasets (PyTorch, COCO) |
| [gemma3n](https://github.com/AlejandroSanchezFerrer/gemma3n) | Streamlit web app, CLI, notebook |

## Research Context

OceanGuard AI is part of a doctoral research project on applying on-device AI to marine conservation challenges. The goal is to democratize marine debris monitoring by putting powerful detection tools directly in the hands of conservation teams, citizen scientists, and coastal communities worldwide -- without requiring expensive equipment or cloud infrastructure.

## License

Apache License 2.0 -- see [LICENSE](LICENSE) for details.

All models used (RT-DETRv2, Qwen 3.5, Gemma 4) are Apache 2.0 licensed.

---

**Powered by Gemma 4 -- Google AI Edge**

Built for the Kaggle Gemma 4 Good Hackathon (Global Resilience Track, 2026).
