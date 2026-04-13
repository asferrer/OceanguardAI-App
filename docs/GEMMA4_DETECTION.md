# Gemma 4 Vision Detection -- Prompt Engineering and box_2d Integration

This document details how OceanGuard AI uses Gemma 4 E2B as a visual object detector with bounding box output, including prompt design, JSON parsing, coordinate conversion, and edge case handling.

---

## Overview

Gemma 4 natively supports `box_2d` output -- a structured coordinate format that allows the model to localize objects in images with bounding boxes. OceanGuard AI leverages this capability to perform zero-shot marine debris detection, complementing the trained RT-DETRv2 detector.

**Why use a VLM for object detection?**

- **Zero-shot generalization**: Detects debris types without task-specific training
- **Scene understanding**: The VLM interprets spatial context (debris on coral vs on sand)
- **Verification**: Cross-validates RT-DETRv2 detections with a fundamentally different approach
- **Flexibility**: Adapts to new debris categories by simply editing the prompt

**Trade-off**: ~100-300x slower than RT-DETRv2 (~4-10s vs ~30ms), so it is used for deep analysis, not real-time scanning.

---

## The Detection Prompt

```
Detect all marine debris in this image. Output ONLY a JSON array, nothing else.
Classes: Bottle, Can, Fishing_Net, Glove, Mask, Metal_Debris, Plastic_Debris, Tire

Output format:
[{"box_2d": [y_min, x_min, y_max, x_max], "label": "class_name"}]

Coordinates are integers from 0 to 1000. If no debris is found, output: []
```

### System Message

```
You are a marine debris detection system. Output ONLY valid JSON arrays.
Never include explanations, markdown, or text outside the JSON.
```

### Prompt Design Decisions

1. **Explicit format specification**: The prompt includes the exact JSON schema to minimize format variation. Without this, Gemma 4 may output natural language descriptions instead.

2. **Class list in prompt**: All 8 canonical class names are listed explicitly. This constrains the model to use consistent labels that match the `DebrisClasses.NAMES` array used by RT-DETRv2.

3. **"Output ONLY" instruction**: Repeated in both the user prompt and system message to suppress explanatory text that would break JSON parsing.

4. **Coordinate range specified**: "Integers from 0 to 1000" prevents the model from using pixel coordinates or percentages.

5. **Empty-case instruction**: "If no debris is found, output: []" ensures a parseable response even when the image contains no debris.

6. **Low temperature (0.3)**: Used for vision detection to maximize precision. Higher temperatures increase hallucinated detections.

---

## Gemma 4 box_2d Coordinate System

Gemma 4's `box_2d` format uses a **1000x1000 grid** with **y-first axis order**:

```
box_2d: [y_min, x_min, y_max, x_max]
```

This differs from most object detection conventions:

| Convention | Format | Example |
|-----------|--------|---------|
| Gemma 4 box_2d | [y_min, x_min, y_max, x_max] 0-1000 | [120, 300, 450, 680] |
| COCO | [x, y, w, h] pixels | [300, 120, 380, 330] |
| VOC / xyxy | [x_min, y_min, x_max, y_max] pixels | [300, 120, 680, 450] |
| OceanGuard internal | [x1, y1, x2, y2] normalized 0-1 | [0.3, 0.12, 0.68, 0.45] |

### Conversion Process

```
Gemma 4 output:    [y_min=120, x_min=300, y_max=450, x_max=680]
                          |         |         |          |
Clamp to [0,1000]:   120.0     300.0     450.0      680.0
                          |         |         |          |
Divide by 1000:       0.120     0.300     0.450      0.680
                          |         |         |          |
Swap to x-first:  x1=0.300  y1=0.120  x2=0.680  y2=0.450
```

The conversion code in `Gemma4VisionDetector.kt`:

```kotlin
// box_2d is [y_min, x_min, y_max, x_max] on 1000-grid
val yMin = clampCoord(box[0].asFloat)
val xMin = clampCoord(box[1].asFloat)
val yMax = clampCoord(box[2].asFloat)
val xMax = clampCoord(box[3].asFloat)

// Convert to normalized [0,1] [x1, y1, x2, y2]
DetectionResult(
    x1 = xMin / 1000f,
    y1 = yMin / 1000f,
    x2 = xMax / 1000f,
    y2 = yMax / 1000f,
    ...
)
```

---

## JSON Output Format and Parsing

### Expected Response

```json
[
  {"box_2d": [120, 300, 450, 680], "label": "Bottle"},
  {"box_2d": [50, 100, 200, 350], "label": "Plastic_Debris"}
]
```

### Parsing Strategy

The parser (`parseDetections`) uses a multi-layer extraction approach to handle the variety of formats that VLMs can produce:

#### Layer 1: Extract JSON Array

`extractJsonArray()` handles three response formats:

1. **Clean JSON**: Response starts with `[` -- use directly
2. **Markdown-wrapped**: `` ```json [...] ``` `` -- extract from code block via regex
3. **Embedded**: Find first `[` and last `]` in the response -- extract substring

#### Layer 2: Parse Elements

Each element in the JSON array is parsed independently. If one element is malformed, it is skipped without affecting others:

```kotlin
array.mapNotNull { element ->
    try {
        val obj = element.asJsonObject
        val box = obj.getAsJsonArray("box_2d")
        if (box == null || box.size() < 4) return@mapNotNull null
        val label = obj.get("label")?.asString ?: return@mapNotNull null
        // ... build DetectionResult
    } catch (e: Exception) {
        null  // Skip malformed element
    }
}
```

#### Layer 3: Class Matching

`matchClassId()` maps the VLM's label string to the canonical class index:

1. **Exact match** (case-insensitive): "Bottle" -> index 0
2. **Partial match**: "Plastic" -> "Plastic_Debris" (index 6)
3. **No match**: Returns -1, detection is skipped

This handles label variations like "plastic debris", "Plastic", "PLASTIC_DEBRIS" etc.

---

## Edge Cases and Error Handling

### Empty Scene (No Debris)

**Expected**: `[]`

**Also handled**:
- Free text: "No debris found in this image" -- `extractJsonArray()` returns null, empty list returned
- Empty string -- returns empty list

### Malformed JSON

**Handled at multiple levels**:
- `extractJsonArray()` fails to find brackets -- returns null
- `JsonParser.parseString()` throws -- caught, returns empty list
- Individual element parsing throws -- element skipped via `mapNotNull`

### Coordinates Out of Range

**Handled by `clampCoord()`**:
```kotlin
private fun clampCoord(value: Float): Float = min(1000f, max(0f, value))
```

Values below 0 are clamped to 0, values above 1000 are clamped to 1000. This prevents:
- Negative coordinates from VLM hallucinations
- Coordinates exceeding the 1000-grid (rare but observed with temperature > 0.5)

### Missing Fields

- `box_2d` null or fewer than 4 elements -- detection skipped
- `label` null -- detection skipped
- `label` not matching any class -- detection skipped (classId < 0)

### Confidence Scores

VLMs do not output confidence scores. A **fixed confidence of 0.75** is assigned to all Gemma 4 detections. This value was chosen to:
- Be above the default threshold (0.5) so detections are shown
- Be below 1.0 to indicate these are not calibrated probabilities
- Allow RT-DETRv2 detections (which have real scores) to rank higher in mixed results

### Markdown Code Fences

Gemma 4 sometimes wraps JSON in markdown code blocks despite the "no markdown" instruction:

````
```json
[{"box_2d": [120, 300, 450, 680], "label": "Bottle"}]
```
````

The regex pattern ````(?:json)?\s*\n?(\[.*?])\s*``` ` extracts the JSON array from within the fences.

---

## Comparison: RT-DETRv2 vs Gemma 4 Vision

| Aspect | RT-DETRv2 | Gemma 4 Vision |
|--------|-----------|----------------|
| **Architecture** | Transformer encoder-decoder | 5.1B param VLM (2.3B effective) |
| **Training** | Supervised on 8-class COCO dataset | General pretraining (zero-shot) |
| **Runtime** | TFLite + NNAPI/XNNPACK | LiteRT-LM Engine API |
| **Latency** | ~30ms | ~4-10s |
| **Confidence** | Calibrated sigmoid scores | Fixed 0.75 |
| **Boxes** | 150 queries, cxcywh | Unbounded, box_2d y-first |
| **NMS** | Per-class NMS in Kotlin | Not needed (VLM outputs final set) |
| **Model size** | 83 MB (FP16) | 2.6 GB (.litertlm) |
| **Strengths** | Speed, calibrated scores, consistency | Novel debris types, scene context |
| **Weaknesses** | Limited to 8 trained classes | Slow, no confidence, occasional hallucinations |

### When to Use Each

- **RT-DETRv2**: Live camera detection, batch scanning, time-sensitive workflows
- **Gemma 4 Vision**: Detailed analysis of specific images, verification of uncertain RT-DETRv2 detections, scenes with unusual debris types

### Complementary Usage

The `DetectionOrchestrator` can run both detectors:

1. RT-DETRv2 runs first (~30ms) and results are shown immediately
2. Gemma 4 Vision runs in parallel (~4-10s) for deep verification
3. Results are merged: RT-DETRv2 provides calibrated scores, Gemma 4 catches missed items

---

## Latency Breakdown (Exynos 2200)

| Phase | Time |
|-------|------|
| Image to JPEG bytes | ~5-15ms |
| LiteRT-LM engine create conversation | ~50-100ms |
| Vision encoding (image patch processing) | ~500-1500ms |
| Text generation (~50-150 tokens) | ~3-8s |
| JSON parsing + coordinate conversion | <1ms |
| **Total** | **~4-10s** |

The dominant cost is text generation. Token count varies with the number of detected objects:
- 0 objects: ~10 tokens (`[]`) -- ~1-2s
- 1-3 objects: ~50-100 tokens -- ~4-7s
- 5+ objects: ~100-200 tokens -- ~7-10s

---

## Implementation Files

| File | Purpose |
|------|---------|
| `Gemma4VisionDetector.kt` | ObjectDetector wrapper, prompt, JSON parsing, coordinate conversion |
| `LiteRTTextEngine.kt` | LiteRT-LM engine lifecycle, `generateWithImage()` multimodal API |
| `DetectorInterface.kt` | `ObjectDetector` interface, `DetectionResult`, `DetectorType` enum |
| `DetectionOrchestrator.kt` | Parallel pipeline coordinator |

---

## Future Improvements

1. **Confidence calibration**: Use VLM logprobs (when available in LiteRT-LM API) to estimate per-detection confidence instead of fixed 0.75
2. **Segmentation masks**: Gemma 4 supports `segment` output -- could provide pixel-level masks in addition to bounding boxes
3. **Few-shot prompting**: Include example detections in the prompt to improve consistency on edge cases
4. **Batch detection**: Process multiple images in a single conversation turn to amortize KV cache warm-up cost
