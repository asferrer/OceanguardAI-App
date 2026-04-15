# OceanGuard AI: marine pollution intelligence in your pocket

**An offline Android platform that turns any phone into a marine biologist, powered end-to-end by Gemma 4 with native tool calling, so its reports never invent a single number.**

Google · The Gemma 4 Impact Challenge
Tracks: Global Resilience · LiteRT · llama.cpp
Hackathon Writeup · Apr 2026

---

## The story

A volunteer surfaces from a dive in Cabo de Gata holding a GoPro full of plastic. A marine NGO in Senegal needs to brief a minister tomorrow on what is washing up on the coast. A doctoral researcher in Galicia wants three months of survey data turned into a peer-review-ready section. None of them have stable internet. None of them can wait for a cloud round-trip. None of them can afford a model that confidently invents the percentages it cites.

OceanGuard AI is built for these three people, and for the millions like them. It runs on a single Android device, fully offline, and produces a scientific-grade report on marine debris in under 90 seconds. It uses Gemma 4 E2B for **everything**: detecting and classifying debris from photos, computing the ecosystem health score, and writing the final narrative report in six languages. The breakthrough that makes this trustworthy is **native function calling**: the same feature that landed with Gemma 4 lets the model query the local database for every number it puts on the page, eliminating the hallucinated statistics that have made on-device LLM reports unusable for environmental policy until now.

## The problem we attack

Marine pollution is a global resilience emergency. Eight million tonnes of plastic enter the ocean every year and existing monitoring is reactive, expensive, and centralized. Even when AI is brought in, environmental reports written by LLMs routinely fabricate degradation times, risk percentages, and mitigation budgets. A government cannot act on numbers a model invented.

We need three things at once: **edge intelligence** so fieldwork in remote coastlines does not depend on connectivity, **multimodal vision** capable of identifying 50+ debris classes from a single photo, and **grounded text generation** that proves every number it cites. Gemma 4 is the first open model that delivers all three on a phone.

## What OceanGuard AI does

📱 **Offline-first Android app** that uses the camera or the gallery as input and produces detections, an ecosystem health score, geospatial visualizations, and Markdown reports. No network calls, no telemetry, no cloud bills.

🌊 **53-class marine debris taxonomy** mapped to 11 material families, each linked to NOAA/UNEP-grade ecological impact data: degradation time, primary ecological risk, annual ocean volume.

🩺 **Ecosystem Health Score**, a 0 to 100 vital sign computed per image and per zone from debris density, material risk and material diversity. Surfaced everywhere: detection cards, map markers, zone summaries, report headings.

🗺️ **Geospatial dashboard** built on MapLibre + OpenFreeMap, fully offline-capable, that turns every geotagged detection into a hotspot a coordinator can act on.

📜 **Tool-calling executive reports** in three voices (Scientific, NGO Manager, Citizen) and **six languages** (English, Spanish, French, German, Italian, Portuguese), with day-by-day temporal evolution for repeat-surveyed zones.

🎯 **Prioritized GPS waypoints** ranked HIGH/MEDIUM/LOW so cleanup crews go where the impact is biggest first.

## How we use Gemma 4

Gemma 4 E2B is the entire AI stack. There are no other models. To get the best of both worlds we ship **two runtimes for Gemma 4** and route work between them at runtime, qualifying for both the **LiteRT** and **llama.cpp** Special Tech Tracks.

### Runtime A: LiteRT-LM 0.10.0 (Vulkan GPU) with native tool calling

This is the report-generation path and the heart of the project. We use Google AI Edge's brand-new LiteRT-LM 0.10.0 runtime to load a `.litertlm` build of Gemma 4 E2B on the Vulkan GPU (Adreno, Xclipse, Mali) with automatic CPU XNNPACK fallback. On top of it, we wired the new `@Tool` / `ToolSet` function-calling API to a custom Kotlin agent loop (`ToolAgentLoop`, 130 lines) that runs with `automaticToolCalling = false` so we keep token-level streaming and full UI control while Gemma decides which tools to call.

We expose nine grounded tools, all reading from a pre-computed `ToolReportContext`:

| Tool | Returns |
|---|---|
| `getDebrisSummary` | Totals, average health score, dominant material/type, risk breakdown |
| `getMaterialBreakdown` | Counts and percentages per material, **always sums to 100** |
| `getTypeBreakdown` | Counts and percentages per debris type |
| `getRiskAssessment` | Ranked risk table with ecological mechanisms |
| `getCollectionWaypoints` | Top 10 GPS waypoints by intervention priority |
| `getTemporalTrend` | Day-by-day evolution for zone reports |
| `getEcologicalImpact` | NOAA/UNEP degradation time and risk for a debris type |
| `computeStatistics` | min/max/avg/median/stdDev over health score or debris count |
| `getSessionDetail` | Per-session breakdown for tables |

The payoff is dramatic. The legacy approach front-loaded ~2,500 tokens of JSON aggregates into the prompt and let Gemma copy them, with all the hallucinations that implies. The tool-calling prompt is **~200 tokens**, freeing 2,300 tokens for the actual report body. Reports went from ~600 to over 1,200 words, with mathematically guaranteed numbers, in under 90 seconds on a Galaxy S22 Ultra. This directly answers the **Safety & Trust** challenge: the model is grounded and explainable by construction.

### Runtime B: llama.cpp (Vulkan GPU) with multimodal mmproj

For broader hardware support and the open-source ecosystem, we also ship a llama.cpp path that loads the same Gemma 4 E2B as a Q4_K_M GGUF plus its mmproj projector for vision, with our recently-added **Vulkan GPU acceleration**. This path serves devices where LiteRT-LM is not yet available, and it powers the open-vocabulary VLM detector via a custom `Gemma4PromptFormatter` that returns `box_2d` coordinates in Gemma 4's expected format. Token-by-token streaming, a temperature-aware sampler, and an idle auto-release keep memory pressure low on resource-constrained phones.

Having two runtimes for the **same** model means OceanGuard runs on practically any modern Android device while always selecting the best path: tool-calling reports through LiteRT-LM where available, llama.cpp for portable VLM detection and as a graceful fallback.

## Architecture

```
Camera or Gallery
       v
Gemma 4 Vision  --- LiteRT-LM (Vulkan)  OR  llama.cpp + mmproj (Vulkan)
       v
 Room database    (DetectionSession + Debris + GPS + EXIF)
       v
ToolReportContext  (pre-computed aggregates, O(1) lookups)
       v
OceanGuardTools  (9 @Tool methods)  <----+
       v                                  |
ToolAgentLoop  <---->  Gemma 4 Text  <----+
       v                              (function calls)
Streaming Markdown report (3 audiences x 6 languages)
```

Every layer runs on the device. No data ever leaves the phone.

## Engineering details that matter

- **Single shared `LiteRTTextEngine`** for the vision detector and the report generator, protected by a Mutex against concurrent `initialize()` races that would otherwise spawn two native engines on the same GPU and slow inference 10×.
- **Persistent `Conversation`** across all tool-calling rounds reuses the KV cache, so each tool round only re-prefills the ~200-token tool response, not the full system prompt.
- **Defense-in-depth agent loop** that swallows tool-call parser errors gracefully and returns the partial report instead of aborting the user's work.
- **17 unit tests** validate the tool layer end-to-end: percentages summing to 100, IMPACT_MAP values, waypoint priority sort, statistics arithmetic, edge cases.
- **Built in 100% Kotlin** with Jetpack Compose, CameraX, Room 2.7, Material 3, MapLibre. No Java except a tiny MapLibre expression bridge.

## Real-world impact

OceanGuard is more than a tool, it is a force multiplier for ocean conservation:

- **Scientists and researchers** get reports they can cite without first re-checking every number. Three months of dives become a publication-ready section in 90 seconds, offline, in the field.
- **NGOs and conservation groups** get prioritized GPS waypoints for cleanup logistics and audience-tuned reports for donors and ministries, in any of six languages.
- **Government agencies** monitor coastlines and enact policy on data the model did not invent: every percentage in the report traces back to a tool call against the local database.
- **Citizen scientists** contribute by sharing their dive photos, and on-device analysis preserves their privacy completely. Privacy is non-negotiable here, and Gemma 4 makes it free.

## Vision

We aim to build the world's largest crowd-sourced database of marine pollution. Every underwater camera becomes a scientific instrument. Every photo becomes a step toward a cleaner ocean. With Gemma 4 running natively on the device, that step now fits in your pocket, costs nothing per inference, works without a network, and tells the truth.

This is what edge-first frontier AI is for.
