# OceanGuard AI · 3-minute video pitch script

**Target length**: 2:55 (180s)
**Voiceover words**: ~430 (≈ 150 wpm)
**Tone**: cinematic, hopeful, grounded. Open with emotion, end with vision.
**Music**: ambient piano under VO; swell on the "tool calling" reveal; cut to silence on the final on-screen line.

Format per scene: **[timecode] · VISUAL · ON-SCREEN TEXT · VOICEOVER**

---

## ACT 1 · The hook (0:00 – 0:25)

**[0:00 – 0:08]**
*Visual*: Underwater drone footage. A plastic bottle drifts past a school of fish. Slow push in.
*On-screen text*: 8,000,000 tonnes / year
*VO*: "Every year, eight million tonnes of plastic enter our oceans."

**[0:08 – 0:18]**
*Visual*: Cut to a volunteer surfacing from a dive in Cabo de Gata, GoPro in hand. Wet, smiling, exhausted.
*On-screen text*: (none)
*VO*: "And right now, the people fighting back, divers, coastal NGOs, scientists in remote field stations, are doing it without the data they need to win."

**[0:18 – 0:25]**
*Visual*: Phone screen, no signal indicator. Hand swiping through hundreds of underwater photos.
*VO*: "No connectivity. No cloud. No time."

---

## ACT 2 · The problem we attack (0:25 – 0:55)

**[0:25 – 0:38]**
*Visual*: Split screen. Left: an environmental NGO report on a desk with a red highlighter circling a percentage. Right: a chatbot interface showing the same percentage being generated.
*On-screen text*: "Hallucinated"
*VO*: "And when AI is brought in, today's models routinely invent the percentages, the degradation times, the budgets. A government cannot act on numbers a model made up."

**[0:38 – 0:55]**
*Visual*: Whiteboard sketch animation: three icons appear, "edge", "vision", "grounded text".
*On-screen text*: EDGE · VISION · GROUNDED
*VO*: "We needed three things at once. Intelligence at the edge, so the field never depends on a network. Multimodal vision, to recognize debris from a single photo. And grounded text, that proves every number it cites. With Gemma 4, an open model finally does all three on a phone."

---

## ACT 3 · The solution reveal (0:55 – 1:20)

**[0:55 – 1:05]**
*Visual*: Title card animates in over an aerial shot of the Mediterranean coast.
*On-screen text*: **OceanGuard AI · powered by Gemma 4**
*VO*: "OceanGuard AI. An offline Android app that turns any phone into a marine biologist."

**[1:05 – 1:20]**
*Visual*: Phone screen recording. User opens the app, takes a photo of debris on a beach. Animated bounding boxes appear over plastic bottles, a fishing net, a tire. Health Score gauge animates from 100 down to 42.
*On-screen text*: 50 debris classes · 6 languages · 100% offline
*VO*: "One tap. Gemma 4 detects fifty classes of marine debris, computes an ecosystem health score, and pins it to the map. Six languages. Zero network calls. Zero cloud bills."

---

## ACT 4 · The wow moment, native tool calling (1:20 – 2:05)

**[1:20 – 1:32]**
*Visual*: Phone screen. User taps "Generate Report". Toast: "Querying database…". Live tool-call chips fade in one after another.
*On-screen text overlay*:
`getDebrisSummary` ✓
`getMaterialBreakdown` ✓
`getEcologicalImpact("FISHING_NET")` ✓
*VO*: "Here is the breakthrough. Gemma 4's native function calling. Instead of stuffing data into a prompt and hoping the model copies it, Gemma queries our database, on device, for every number it puts on the page."

**[1:32 – 1:48]**
*Visual*: Generated report streams in real time. Camera zooms into a percentage table. Numbers highlight green as a tooltip pops up: "from getMaterialBreakdown".
*On-screen text*: "Every percentage traces back to a tool call."
*VO*: "Percentages always sum to one hundred. Degradation times always match NOAA reference data. GPS waypoints are real, ranked by impact. Reports of seven hundred to nine hundred words, fully grounded, on a phone — every number traceable to a tool call."

**[1:48 – 2:05]**
*Visual*: Code split-screen. Left: 8 `@Tool` Kotlin methods. Right: terminal showing logcat lines `Tool 'getDebrisSummary' executed OK`, `Loop done after 5 tool round(s)`. Highlight `automaticToolCalling = false`.
*On-screen text*: 8 tools · 17 unit tests · grounded by construction
*VO*: "Eight tools. A custom Kotlin agent loop with manual streaming. Seventeen unit tests prove the contract. The model is grounded and explainable by construction."

---

## ACT 5 · Two runtimes, one model (2:05 – 2:25)

**[2:05 – 2:15]**
*Visual*: Two parallel pipes animate side by side. Left pipe labeled "LiteRT-LM 0.10.0 · CPU XNNPACK · @Tool". Right pipe labeled "llama.cpp · Vulkan · mmproj". Both feed into the same Gemma 4 logo in the center.
*On-screen text*: One model. Two runtimes. Every device.
*VO*: "We ship Gemma 4 on two runtimes. LiteRT-LM with native function calling for grounded reports. And llama.cpp with mmproj for portable open-vocabulary vision."

**[2:15 – 2:25]**
*Visual*: Quick cuts of the app running on three different Android devices: a high-end flagship, a mid-range phone, an older device.
*On-screen text*: (none)
*VO*: "OceanGuard always picks the best path for the device in your hand. From a flagship to a four-year-old phone."

---

## ACT 6 · Impact close (2:25 – 2:55)

**[2:25 – 2:40]**
*Visual*: Three quick portraits intercut, each holding a phone with OceanGuard open:
- A coastal NGO coordinator in Senegal showing a heatmap to a colleague.
- A doctoral researcher in Galicia exporting a peer-review-ready report.
- A teenage diver in Indonesia uploading a photo to the marine debris map.
*VO*: "A coordinator in Senegal briefs a minister tomorrow. A researcher in Galicia ships a peer-review-ready section without leaving the field. A teenager in Indonesia adds one more photo to the world's largest crowd-sourced marine pollution map."

**[2:40 – 2:50]**
*Visual*: Pull back from a phone screen showing the OceanGuard map of the world, dots filling in across coastlines. Camera keeps pulling until the phone is in someone's hand on a beach at sunset.
*On-screen text*: Every camera. A scientific instrument.
*VO*: "Every underwater camera, a scientific instrument. Every photo, a step toward a cleaner ocean."

**[2:50 – 2:55]**
*Visual*: Black frame. Logo + tagline.
*On-screen text*:
**OceanGuard AI**
*Marine pollution intelligence in your pocket.*
Powered by Gemma 4.
*VO*: (silence, then soft) "Powered by Gemma 4."

---

## Production notes

- **B-roll sources to license or shoot**: free underwater stock (Pexels, Coverr), original footage of a dive in any Mediterranean cove, a beach cleanup, a phone in hand on a coastline at golden hour.
- **Screen recordings**: capture from Galaxy S22 Ultra at 1080p 60fps. Slow-mo the bounding-box reveal to 1.5×.
- **Code shots**: use a clean dark theme (Material 3 dark). Highlight `@Tool`, `automaticToolCalling = false`, and the 8 tool names.
- **Captions**: full English subtitles burnt-in, plus an SRT in Spanish for the YouTube CC track to reinforce the multilingual story.
- **Thumbnail**: split image, left half a plastic bottle underwater, right half the OceanGuard report on a phone, big "Powered by Gemma 4" in the corner.
- **Hard cut-off**: the spec says 3:00. Aim for 2:55 with 5 seconds of safety margin.

## Voiceover delivery cues

- 0:00 – 0:25: low, somber, breath between phrases.
- 0:55 – 1:20: lift, brighter cadence, optimism enters.
- 1:20 – 2:05: this is the climax. Crisp, confident, punch the phrases ("every number traceable", "fifty classes", "grounded by construction").
- 2:25 – 2:55: warm, slow down 10%, let the visuals breathe.

## Talking points if you record a presenter cut instead

If you prefer on-camera over pure VO, replace ACT 1 with you on a Mediterranean beach, phone in hand, opening line:
> "I am standing on a beach where eight million tonnes of plastic land every year. The people trying to fix this need data, and they need it offline. So we built it. With Gemma 4."

Then cut to screen recordings for ACTs 3, 4, 5, and return to camera for the close.
