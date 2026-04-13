# OceanGuard AI -- Technical Architecture

This document describes the system architecture of OceanGuard AI, focusing on the inference pipelines, model management, GPU strategy, and data flow.

---

## System Overview

OceanGuard AI runs two independent inference subsystems on-device:

1. **Detection subsystem** -- identifies and localizes marine debris in images
2. **Text generation subsystem** -- produces environmental impact reports from detection data

Both subsystems share a common interface-based design that allows swapping implementations without changing the UI or orchestration layer.

```
+-----------------------------------------------------------------------+
|                        Android Application                            |
|                                                                       |
|  +------------------+   +--------------------+   +-----------------+  |
|  | Compose UI       |   | Foreground         |   | Room Database   |  |
|  | (screens,        |<->| Services           |<->| (sessions,      |  |
|  |  navigation,     |   | (inference,        |   |  reports,       |  |
|  |  state flows)    |   |  report gen,       |   |  MarineDex,     |  |
|  |                  |   |  VLM download)      |   |  achievements)  |  |
|  +------------------+   +--------------------+   +-----------------+  |
|           |                       |                                    |
|  +--------|--------------------------------------------+              |
|  |        v        DetectionOrchestrator               |              |
|  |  +-----------+     +-----------+     +------------+ |              |
|  |  | RT-DETRv2 |     | Gemma 4   |     | VLM Vision | |              |
|  |  | (TFLite)  |     | Vision    |     | (llama.cpp)| |              |
|  |  +-----------+     | (LiteRT)  |     +------------+ |              |
|  |                    +-----------+                    |              |
|  +-----------------------------------------------------+              |
|                                                                       |
|  +-----------------------------------------------------+              |
|  |          Text/Report Generation                     |              |
|  |  +-----------+            +-----------+             |              |
|  |  | Qwen 3.5  |            | Gemma 4   |             |              |
|  |  | (llama.cpp|            | E2B       |             |              |
|  |  |  + Vulkan)|            | (LiteRT)  |             |              |
|  |  +-----------+            +-----------+             |              |
|  +-----------------------------------------------------+              |
+-----------------------------------------------------------------------+
```

---

## Detection Pipeline

### Interface: `ObjectDetector`

All detectors implement a common interface (`DetectorInterface.kt`):

```kotlin
interface ObjectDetector {
    val displayName: String
    val inputSize: Int
    fun isReady(): Boolean
    suspend fun initialize()
    suspend fun warmUp()
    suspend fun detect(bitmap: Bitmap, confidenceThreshold: Float): List<DetectionResult>
    fun release()
}
```

Output is always a list of `DetectionResult` with **normalized [0,1] bounding boxes** in `[x1, y1, x2, y2]` format, regardless of the backend.

### Implementation 1: RT-DETRv2 (Fast Detection)

| Property | Value |
|----------|-------|
| File | `RTDETRInference.kt` |
| Model format | TFLite (FP16: 83 MB, INT8: 43.5 MB) |
| Input | 640x640 RGB, fixed size |
| Output | `pred_boxes[1,150,4]` (cxcywh) + `pred_logits[1,150,8]` |
| Postprocessing | Sigmoid on logits, threshold filter, cxcywh-to-xyxy, per-class NMS |
| Delegates | NNAPI preferred (Exynos big.LITTLE scheduling, +28%), XNNPACK fallback |
| Latency | ~30ms per frame (NNAPI on Exynos 2200) |

**Delegate strategy** is configurable via `DelegateStrategy` enum:
- `NNAPI_PREFERRED` -- default, best on Exynos 2200
- `XNNPACK_ONLY` -- pure CPU, for benchmarking

**Erf-free model**: The TFLite model uses a custom ONNX surgery that replaces the `Erf` operator (unsupported by some delegates) with a `tanh` approximation, ensuring NNAPI compatibility.

### Implementation 2: Gemma 4 Vision (Deep Detection)

| Property | Value |
|----------|-------|
| File | `Gemma4VisionDetector.kt` |
| Model format | `.litertlm` (~2.6 GB, LiteRT-LM container) |
| Input | Any size (LiteRT-LM handles resizing internally) |
| Output | JSON array with `box_2d` coordinates + class labels |
| Backend | `LiteRTTextEngine` -- GPU auto-detect, CPU fallback |
| Latency | ~4-10s per image (CPU), potentially faster on GPU |

**How it works**: Sends image + structured prompt to the VLM. Gemma 4 outputs `box_2d` coordinates on a 1000x1000 grid in `[y_min, x_min, y_max, x_max]` order. These are parsed from JSON and converted to normalized `[0,1]` `[x1, y1, x2, y2]` coordinates.

Since VLMs do not output confidence scores, a fixed confidence of `0.75` is assigned to all detections.

See [GEMMA4_DETECTION.md](GEMMA4_DETECTION.md) for detailed prompt engineering documentation.

### DetectionOrchestrator

The orchestrator (`DetectionOrchestrator.kt`) runs the dual pipeline:

1. Load and preprocess image
2. Launch RT-DETRv2 detection (async)
3. Launch VLM deep analysis in parallel (if enabled and model loaded)
4. Emit RT-DETRv2 results immediately via `StateFlow` for instant UI feedback
5. Await VLM results only if debris was found (or RT-DETRv2 is unavailable)
6. Merge results into `AnalysisResult` with health score

State progression exposed to the UI:

```
Idle -> LoadingImage -> Detecting -> DetectionsReady -> AnalyzingDeep -> Complete
                                                                     -> Error
```

### Detector Selection

Users choose their detector in Settings. The `OceanGuardApp` class maintains the active detector and the orchestrator references it:

```
DetectorType.RT_DETR_V2  -> RTDETRInference (TFLite)
DetectorType.GEMMA4_VISION -> Gemma4VisionDetector (LiteRT-LM)
DetectorType.PICODET_S   -> (planned, NCNN)
```

---

## Text/Report Generation Pipeline

### Interface: `VlmTextEngine`

```kotlin
interface VlmTextEngine {
    val displayName: String
    fun isReady(): Boolean
    suspend fun initialize(modelPath: String)
    suspend fun warmUp()
    fun release()
    suspend fun generateText(
        prompt: String,
        maxTokens: Int,
        systemMessage: String?,
        assistantPrefill: String?,
        thinkingEnabled: Boolean,
        onPartialResult: (String) -> Unit,
    ): String
}
```

Extended by `VlmVisionEngine` for multimodal (image + text) generation.

### Implementation 1: Qwen 3.5 via llama.cpp

| Property | Value |
|----------|-------|
| File | `LlamaTextEngine.kt` |
| Models | Qwen 3.5 0.8B / 2B / 4B (Q4_K_M GGUF) |
| Runtime | llama.cpp via JNI (`LlamaCppBridge`) |
| GPU | Vulkan (auto-detect, CPU fallback) |
| Thinking | Enabled for 2B+ tiers (temperature 1.0) |
| Context | 12288 tokens (prompt ~3500 + output 6144 + margin) |

Three quality tiers:

| Tier | Model | Size | Speed | Context |
|------|-------|------|-------|---------|
| Fast | Qwen 3.5 0.8B | 533 MB | ~15-20 tok/s | 12288 |
| Balanced | Qwen 3.5 2B | 1.1 GB | ~8-12 tok/s | 12288 |
| Quality | Qwen 3.5 4B | 2.74 GB | ~4-8 tok/s | 12288 |

### Implementation 2: Gemma 4 E2B via LiteRT-LM

| Property | Value |
|----------|-------|
| File | `LiteRTTextEngine.kt` |
| Model | Gemma 4 E2B (5.1B params, 2.3B effective) |
| Format | `.litertlm` (~2.6 GB) |
| Runtime | LiteRT-LM `Engine` + `Conversation` API |
| GPU | `Backend.GPU()` auto-detect, `Backend.CPU()` fallback |
| Context | 8192 tokens (managed by engine) |
| Sampling | topK=20, topP=0.95, temperature=0.5 (text) / 0.3 (vision) |

**LiteRT-LM advantages over llama.cpp** for Gemma 4:
- 1.65x faster decode speed on Exynos 2200 CPU
- Native chat template handling (no manual prompt formatting)
- Built-in vision support (image bytes sent directly, no mmproj needed)
- GPU backend via `Backend.GPU()` (driver-level optimization)

**Trade-off**: LiteRT-LM does not support assistant-turn prefilling. Workaround: the prompt includes explicit instructions to start with the required heading, and `finalizeOutput()` post-processes to ensure correct formatting.

### Vision Generation (Multimodal)

`LiteRTTextEngine.generateWithImage()` handles multimodal input:

1. Bitmap converted to JPEG bytes (quality 90)
2. Sent as `Content.ImageBytes` alongside `Content.Text` in a `Contents` message
3. Streaming response via `MessageCallback` with 200ms throttled partial results

This is used both by `Gemma4VisionDetector` (for box_2d detection) and by `ReportGenerator` (for vision-aware reports).

---

## GPU Strategy

### Exynos 2200 Constraints

The Samsung Galaxy S22 Ultra (European variant) has specific GPU limitations:

| Feature | Status |
|---------|--------|
| Xclipse 920 (AMD RDNA2) | Available |
| TFLite GPU delegate | NOT supported |
| MediaPipe GPU | NOT supported |
| NNAPI NPU | NOT accessible (EDEN SDK not public) |
| NNAPI CPU scheduling | Works (+28% via big.LITTLE) |
| Vulkan | Supported (llama.cpp) |
| LiteRT-LM `Backend.GPU()` | Auto-probed at runtime |

### Auto-Detection Strategy

Each engine probes GPU availability at initialization and falls back gracefully:

**LiteRT-LM** (`LiteRTTextEngine.selectBackends()`):
```
try Backend.GPU() -> success: use GPU for both text and vision
                  -> exception: fall back to Backend.CPU()
```

**llama.cpp** (`LlamaCppBridge`):
```
Probe Vulkan at native layer -> success: use GPU layers
                              -> failure: CPU-only inference
```

**TFLite** (`RTDETRInference`):
```
NNAPI_PREFERRED -> NnApiDelegate() -> success: NNAPI scheduling
                                    -> failure: XNNPACK CPU
```

The active backend is exposed to the UI via `LiteRTTextEngine.activeBackendName`.

---

## Coordinate Systems

### RT-DETRv2 Output
- Raw: `[cx, cy, w, h]` normalized [0,1] (center-x, center-y, width, height)
- Converted to: `[x1, y1, x2, y2]` normalized [0,1] in Kotlin postprocessing

### Gemma 4 box_2d Output
- Raw: `[y_min, x_min, y_max, x_max]` on a 1000x1000 integer grid
- Note the **y-first** axis order (different from most object detection conventions)
- Converted to: `[x1, y1, x2, y2]` normalized [0,1] by dividing by 1000

### Pipeline-Wide Convention
All `DetectionResult` objects use **normalized [0,1] `[x1, y1, x2, y2]`** coordinates, regardless of the detector backend. This is the contract of the `ObjectDetector` interface.

---

## Memory Management

### Model Lifecycle States

```
NotLoaded -> Loading -> WarmingUp -> Ready -> Standby -> (re-load on demand)
                                           -> Error
```

### Lazy Loading
- RT-DETRv2: loaded at app startup (bundled in APK, ~83 MB, fast init)
- VLM models: loaded on-demand when user triggers detection or report generation
- Gemma 4 Vision detector: lazy-initialized via Kotlin `by lazy`

### 2-Minute Idle Release

VLM engines are auto-released after 2 minutes of inactivity to free RAM:

```kotlin
private const val VLM_RETAIN_MS = 120_000L

// After each generation completes:
vlmReleaseJob?.cancel()
vlmReleaseJob = applicationScope.launch {
    delay(VLM_RETAIN_MS)
    releaseVlmEngine()
}
```

This balances responsiveness (no reload for consecutive reports) with memory pressure (free ~3 GB when idle).

### Memory Budget (Samsung S22 Ultra, 8 GB RAM)

| Configuration | Estimated RAM |
|--------------|---------------|
| RT-DETRv2 only | ~200 MB |
| RT-DETRv2 + Qwen 0.8B | ~1.2 GB |
| RT-DETRv2 + Qwen 2B | ~1.8 GB |
| RT-DETRv2 + Gemma 4 E2B | ~3.2 GB |
| RT-DETRv2 + Qwen 4B | ~3.6 GB |

---

## Model Download and Management

`VlmModelManager` handles the full model lifecycle:

1. **Availability check**: Validates file exists and exceeds minimum size (prevents corrupt HTML error pages from HuggingFace)
2. **Download**: HTTP GET from HuggingFace CDN, 8 MB buffer, resumable via temp files
3. **Progress**: Exposed via `StateFlow<VlmDownloadState>` for UI progress bars
4. **Validation**: Post-download size check against tier-specific minimums
5. **Foreground service**: `VlmDownloadService` keeps download alive when app is backgrounded

All models are Apache 2.0 licensed and require no HuggingFace authentication.

---

## Database Schema (Room v3)

| Entity | Purpose | Key Fields |
|--------|---------|------------|
| `DetectionSession` | One analyzed image | imageUri, detections (JSON), location, timestamp, healthScore |
| `GeneratedReport` | VLM-generated report | sessionIds, reportText, audience, tier, generationTimeMs |
| `MarineDexEntry` | Collected debris types | classId, firstSeenDate, count, bestConfidence |
| `Achievement` | Gamification milestones | achievementId, unlockedDate, progress |

Type converters serialize complex types (debris lists, GPS coordinates, bounding boxes) as JSON strings in Room columns.

---

## Foreground Services

| Service | Purpose | Runs When |
|---------|---------|-----------|
| `InferenceService` | Batch image analysis | User triggers multi-image detection |
| `ReportGenerationService` | VLM report generation | User requests environmental report |
| `VlmDownloadService` | Model download | User downloads a VLM model |

All services use foreground notifications to prevent Android from killing them during long-running operations (VLM inference can take 30-120s for reports).

---

## Performance Summary (Exynos 2200)

| Operation | Latency | Backend |
|-----------|---------|---------|
| RT-DETRv2 detection | ~30ms | NNAPI (big.LITTLE) |
| Gemma 4 vision detection | ~4-10s | LiteRT-LM (CPU/GPU) |
| Qwen 0.8B report (2000 words) | ~30-45s | llama.cpp (Vulkan) |
| Qwen 2B report (2000 words) | ~60-90s | llama.cpp (Vulkan) |
| Gemma 4 E2B report (2000 words) | ~40-60s | LiteRT-LM (CPU/GPU) |
| Model load (RT-DETRv2) | ~1s | TFLite |
| Model load (Gemma 4 E2B) | ~8-15s | LiteRT-LM |
| Model load (Qwen 2B) | ~5-10s | llama.cpp |
