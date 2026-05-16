package com.oceanguard.ai.inference

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.JsonParser
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import kotlin.math.max
import kotlin.math.min

/**
 * [ObjectDetector] implementation that uses Gemma 4 E2B vision to detect
 * marine debris with bounding boxes via structured JSON output.
 *
 * Unlike RT-DETRv2 (fixed 8 classes), this detector uses **open-vocabulary
 * detection** -- Gemma 4 identifies any type of marine debris and also reports
 * the material, enabling richer analysis (e.g. "glass bottle" vs "plastic bottle").
 *
 * Gemma 4 natively supports `box_2d` output -- coordinates on a 1000x1000 grid
 * in `[y_min, x_min, y_max, x_max]` order. This detector converts them to
 * normalized [0,1] `[x1, y1, x2, y2]` matching the rest of the pipeline.
 *
 * Typical latency: ~4-10s on CPU (vs ~30ms for RT-DETRv2). Use for deep
 * analysis where accuracy matters more than speed.
 */
class Gemma4VisionDetector(
    internal val textEngine: LiteRTTextEngine,
) : ObjectDetector {

    companion object {
        private const val TAG = "Gemma4VisionDetector"

        /**
         * Detection prompt tuned for underwater capture. Kept short on purpose
         * (memory: `feedback_detector_prompt.md`) — closed-class lists,
         * chain-of-thought, negative rules and verbose disclaimers degrade
         * Gemma 4 E2B output and inflate prefill latency on Exynos 2200 CPU
         * sampling.
         *
         * Design principles:
         * 1. **Force a specific object label**, not the canonical category.
         *    "aluminum_can" beats "metal_debris", "rubber_glove" beats
         *    "rubber_debris". The client-side [LABEL_TO_TYPE] folds the free
         *    label into one of the canonical [DebrisType]s, so accuracy comes
         *    from the model recognising the actual object, not from picking
         *    the right bucket itself.
         * 2. **No negative rules.** Mentioning "fishing_net" in a "do NOT use
         *    for X" sentence inflates fishing_net recall via the pink-elephant
         *    effect — Gemma 4 E2B does not handle explicit negations well.
         * 3. **Material list is the 5 universal materials + Other**, *not* the
         *    full 11-value DebrisMaterial enum. Anything beyond Plastic/Metal/
         *    Glass/Rubber/Fabric/Other is mapped client-side. Crucially,
         *    `Fishing_Net` is NOT in the material list — it is an object, not
         *    a material, and putting it there doubled the fishing_net bias.
         *
         * The anti-organism rule lives in [SYSTEM_MESSAGE] so it can be cached
         * across calls; the user prompt only carries the output schema +
         * positively-framed examples.
         */
        private val DETECTION_PROMPT = """
Detect every man-made debris object. Output ONLY a JSON array of:
{"box_2d":[y_min,x_min,y_max,x_max],"label":"<snake_case>","material":"<Plastic|Metal|Glass|Rubber|Fabric|Other>"}
Coords are integers 0-1000.

Label MUST be the specific object (plastic_bottle, aluminum_can, glass_bottle,
rubber_glove, tire, face_mask, fishing_net, styrofoam_cup, cigarette_butt,
plastic_bag, syringe, battery, flip_flop). Generic *_debris labels are
forbidden unless the object is truly unrecognisable.

If nothing visible: [].
""".trimIndent()

        private const val SYSTEM_MESSAGE =
            "Marine debris detector for underwater photos. NEVER label fish, coral, " +
            "seaweed, sand, rocks, shells, bubbles, divers or any living organism / " +
            "natural feature as debris. Output ONLY valid JSON arrays, no markdown or prose."

        /**
         * Maps Gemma 4 free-form labels to one of the 11 canonical [DebrisType]s.
         * Keys are lowercase with underscores; values are always canonical so a
         * detection never lands in an extended enum (PLASTIC_BAG, STYROFOAM, ...)
         * that would later be invisible in the MarineDex or duplicated against
         * its canonical parent in the report tables.
         *
         * Adding new alias keys is cheap and improves recall. Point each alias
         * at the **finest-grained sub-type that exists in the enum** (e.g.
         * `glass_bottle -> GLASS_BOTTLE`, not `GLASS_DEBRIS`). The resolver
         * never canonicalises here — `parseDetections` keeps the sub-type as
         * `DetectionResult.subType` and stores `subType.canonical()` as the
         * family in `DetectionResult.className`, so achievements, MarineDex
         * and reports keep working off the canonical level while the bounding
         * box and the per-object list get the granular sub-type.
         */
        private val LABEL_TO_TYPE: Map<String, DebrisType> = buildMap {
            // ---- BOTTLE family (canonical: BOTTLE) ----
            put("bottle", DebrisType.BOTTLE)
            put("plastic_bottle", DebrisType.BOTTLE)
            put("water_bottle", DebrisType.BOTTLE)
            put("soda_bottle", DebrisType.BOTTLE)
            put("bottle_cap", DebrisType.BOTTLE_CAP)
            put("cap", DebrisType.BOTTLE_CAP)

            // ---- CAN family (canonical: CAN) ----
            put("can", DebrisType.CAN)
            put("aluminum_can", DebrisType.CAN)
            put("tin_can", DebrisType.CAN)
            put("beverage_can", DebrisType.CAN)
            put("soda_can", DebrisType.CAN)
            put("beer_can", DebrisType.CAN)

            // ---- FISHING family (canonical: FISHING_NET) ----
            // Generic words like rope/cordage/buoy/float/trap have been moved
            // to OTHER because they cause false positives: a decorative rope,
            // a navigation buoy or a mouse trap are NOT fishing gear and
            // were systematically pushed into FISHING_NET by the model. The
            // labels kept here all carry the "fishing_" qualifier or are
            // species-specific (ghost net, crab/lobster pot).
            put("fishing_net", DebrisType.FISHING_NET)
            put("ghost_net", DebrisType.FISHING_NET)
            put("fishing_gear", DebrisType.FISHING_NET)
            put("trawl_net", DebrisType.FISHING_NET)
            put("gill_net", DebrisType.FISHING_NET)
            put("seine_net", DebrisType.FISHING_NET)
            put("fishing_line", DebrisType.FISHING_LINE)
            put("fishing_rope", DebrisType.FISHING_LINE)
            put("fishing_buoy", DebrisType.FISHING_BUOY)
            put("fishing_trap", DebrisType.FISHING_TRAP)
            put("crab_pot", DebrisType.FISHING_TRAP)
            put("lobster_pot", DebrisType.FISHING_TRAP)
            put("fishing_rod", DebrisType.FISHING_TRAP)
            put("fishing_pole", DebrisType.FISHING_TRAP)

            // ---- GLOVE family (canonical: GLOVE) ----
            put("glove", DebrisType.GLOVE)
            put("rubber_glove", DebrisType.GLOVE)
            put("latex_glove", DebrisType.GLOVE)
            put("work_glove", DebrisType.GLOVE)

            // ---- MASK family (canonical: MASK) ----
            put("mask", DebrisType.MASK)
            put("face_mask", DebrisType.MASK)
            put("surgical_mask", DebrisType.MASK)
            put("respirator", DebrisType.MASK)

            // ---- TIRE family (canonical: TIRE) ----
            put("tire", DebrisType.TIRE)
            put("tyre", DebrisType.TIRE)
            put("flip_flop", DebrisType.FLIP_FLOP)
            put("sandal", DebrisType.FLIP_FLOP)
            put("rubber_hose", DebrisType.RUBBER_HOSE)
            put("hose", DebrisType.RUBBER_HOSE)

            // ---- METAL_DEBRIS family (canonical: METAL_DEBRIS) ----
            put("metal_debris", DebrisType.METAL_DEBRIS)
            put("scrap_metal", DebrisType.METAL_DEBRIS)
            put("metal_fragment", DebrisType.METAL_DEBRIS)
            put("metal", DebrisType.METAL_DEBRIS)
            put("aerosol_can", DebrisType.AEROSOL_CAN)
            put("spray_can", DebrisType.AEROSOL_CAN)
            put("metal_drum", DebrisType.METAL_DRUM)
            put("barrel", DebrisType.METAL_DRUM)
            put("wire_cable", DebrisType.WIRE_CABLE)
            put("wire", DebrisType.WIRE_CABLE)
            put("cable", DebrisType.WIRE_CABLE)
            put("battery", DebrisType.BATTERY)
            put("electronics", DebrisType.ELECTRONICS)
            put("electronic", DebrisType.ELECTRONICS)
            put("paint_can", DebrisType.PAINT_CAN)
            put("oil_container", DebrisType.OIL_CONTAINER)
            put("jerry_can", DebrisType.OIL_CONTAINER)
            put("chemical_drum", DebrisType.CHEMICAL_DRUM)

            // ---- PLASTIC_DEBRIS family (canonical: PLASTIC_DEBRIS) ----
            put("plastic_debris", DebrisType.PLASTIC_DEBRIS)
            put("plastic_fragment", DebrisType.PLASTIC_DEBRIS)
            put("plastic", DebrisType.PLASTIC_DEBRIS)
            put("plastic_bag", DebrisType.PLASTIC_BAG)
            put("bag", DebrisType.PLASTIC_BAG)
            put("grocery_bag", DebrisType.PLASTIC_BAG)
            put("shopping_bag", DebrisType.PLASTIC_BAG)
            put("plastic_grocery_bag", DebrisType.PLASTIC_BAG)
            put("food_wrapper", DebrisType.FOOD_WRAPPER)
            put("wrapper", DebrisType.FOOD_WRAPPER)
            put("styrofoam", DebrisType.STYROFOAM)
            put("polystyrene", DebrisType.STYROFOAM)
            put("foam", DebrisType.STYROFOAM)
            put("plastic_cup", DebrisType.PLASTIC_CUP)
            put("cup", DebrisType.PLASTIC_CUP)
            put("straw", DebrisType.STRAW)
            put("plastic_straw", DebrisType.STRAW)
            put("plastic_utensil", DebrisType.PLASTIC_UTENSIL)
            put("utensil", DebrisType.PLASTIC_UTENSIL)
            put("fork", DebrisType.PLASTIC_UTENSIL)
            put("spoon", DebrisType.PLASTIC_UTENSIL)
            put("knife", DebrisType.PLASTIC_UTENSIL)
            put("six_pack_ring", DebrisType.SIX_PACK_RING)
            put("plastic_sheeting", DebrisType.PLASTIC_SHEETING)
            put("tarp", DebrisType.PLASTIC_SHEETING)
            put("diaper", DebrisType.DIAPER)
            // Generic litter words: Gemma 4 emits these when uncertain.
            put("litter", DebrisType.PLASTIC_DEBRIS)
            put("debris", DebrisType.PLASTIC_DEBRIS)
            put("trash", DebrisType.PLASTIC_DEBRIS)
            put("garbage", DebrisType.PLASTIC_DEBRIS)
            put("waste", DebrisType.PLASTIC_DEBRIS)
            put("rubbish", DebrisType.PLASTIC_DEBRIS)

            // ---- GLASS_DEBRIS family (canonical: GLASS_DEBRIS) ----
            put("glass_debris", DebrisType.GLASS_DEBRIS)
            put("glass", DebrisType.GLASS_DEBRIS)
            put("glass_bottle", DebrisType.GLASS_BOTTLE)
            put("glass_jar", DebrisType.GLASS_JAR)
            put("jar", DebrisType.GLASS_JAR)
            put("glass_fragment", DebrisType.GLASS_FRAGMENT)
            put("glass_shard", DebrisType.GLASS_FRAGMENT)
            put("glass_cup", DebrisType.GLASS_FRAGMENT)
            put("glass_mug", DebrisType.GLASS_FRAGMENT)
            put("light_bulb", DebrisType.LIGHT_BULB)
            put("bulb", DebrisType.LIGHT_BULB)
            put("fluorescent", DebrisType.LIGHT_BULB)

            // ---- FABRIC_DEBRIS family (canonical: FABRIC_DEBRIS) ----
            put("fabric_debris", DebrisType.FABRIC_DEBRIS)
            put("textile_debris", DebrisType.FABRIC_DEBRIS)
            put("textile", DebrisType.FABRIC_DEBRIS)
            put("cloth", DebrisType.FABRIC_DEBRIS)
            put("fabric", DebrisType.FABRIC_DEBRIS)
            put("clothing", DebrisType.CLOTHING)
            put("clothes", DebrisType.CLOTHING)
            put("shirt", DebrisType.CLOTHING)
            put("pants", DebrisType.CLOTHING)
            put("shoe", DebrisType.SHOE)
            put("footwear", DebrisType.SHOE)
            put("boot", DebrisType.SHOE)
            put("sneaker", DebrisType.SHOE)

            // ---- OTHER family (canonical: OTHER) ----
            put("other", DebrisType.OTHER)
            put("cigarette_butt", DebrisType.CIGARETTE_BUTT)
            put("cigarette", DebrisType.CIGARETTE_BUTT)
            put("cigarette_lighter", DebrisType.CIGARETTE_LIGHTER)
            put("lighter", DebrisType.CIGARETTE_LIGHTER)
            put("syringe", DebrisType.SYRINGE)
            put("needle", DebrisType.SYRINGE)
            put("medical_waste", DebrisType.SYRINGE)
            put("cardboard", DebrisType.CARDBOARD)
            put("cardboard_box", DebrisType.CARDBOARD)
            put("paper", DebrisType.PAPER)
            put("newspaper", DebrisType.PAPER)
            put("wood_pallet", DebrisType.WOOD_PALLET)
            put("pallet", DebrisType.WOOD_PALLET)
            put("crate", DebrisType.WOOD_PALLET)
            put("lumber", DebrisType.LUMBER)
            put("plywood", DebrisType.LUMBER)
            put("wood", DebrisType.LUMBER)
            put("ceramic_fragment", DebrisType.CERAMIC_FRAGMENT)
            put("ceramic", DebrisType.CERAMIC_FRAGMENT)
            put("pottery", DebrisType.CERAMIC_FRAGMENT)
            put("mug", DebrisType.CERAMIC_FRAGMENT)
            put("ceramic_mug", DebrisType.CERAMIC_FRAGMENT)
            put("ceramic_cup", DebrisType.CERAMIC_FRAGMENT)
            put("dish", DebrisType.CERAMIC_FRAGMENT)
            put("plate", DebrisType.CERAMIC_FRAGMENT)
            put("bowl", DebrisType.CERAMIC_FRAGMENT)
            put("brick", DebrisType.BRICK)
            put("concrete", DebrisType.BRICK)
        }

        /** Maps Gemma 4 material strings to [DebrisMaterial]. */
        private val MATERIAL_MAP: Map<String, DebrisMaterial> = mapOf(
            "plastic" to DebrisMaterial.PLASTIC,
            "metal" to DebrisMaterial.METAL,
            "glass" to DebrisMaterial.GLASS,
            "rubber" to DebrisMaterial.RUBBER,
            "fabric" to DebrisMaterial.FABRIC,
            "textile" to DebrisMaterial.FABRIC,
            "fishing_net" to DebrisMaterial.FISHING_NET,
            "nylon" to DebrisMaterial.FISHING_NET,
            "wood" to DebrisMaterial.WOOD,
            "paper" to DebrisMaterial.PAPER,
            "cardboard" to DebrisMaterial.PAPER,
            "ceramic" to DebrisMaterial.CERAMIC,
            "chemical" to DebrisMaterial.CHEMICAL,
        )
    }

    override val displayName = "Gemma 4 E2B Vision"
    // 448 keeps LiteRT-LM on the vision_140 path (4 tiles) instead of vision_280
    // (16 tiles). On Exynos 2200 this drops the encoder pass from ~12 s to ~3 s
    // with no measurable recall loss for the canonical 11 debris classes.
    override val inputSize = 448

    override fun isReady(): Boolean = textEngine.isReady()

    override suspend fun initialize() {
        // Engine initialization handled by OceanGuardApp -- nothing to do here.
    }

    override suspend fun warmUp() {
        // Warm-up handled by LiteRTTextEngine -- nothing extra needed.
    }

    override suspend fun detect(
        bitmap: Bitmap,
        confidenceThreshold: Float,
    ): List<DetectionResult> {
        check(textEngine.isReady()) { "Gemma 4 engine not loaded. Download the model first." }

        Log.d(TAG, "Detecting debris in ${bitmap.width}x${bitmap.height} image...")
        val startMs = System.currentTimeMillis()

        val response = try {
            textEngine.generateWithImage(
                bitmap = bitmap,
                prompt = DETECTION_PROMPT,
                // 512 fits ~14 typical detections in JSON without truncation.
                // 384 (previous value) cut off cluttered scenes mid-array, and
                // a truncated JSON makes the parser drop every box after the
                // failure point — including the one the user actually cared
                // about. 1024 was wasteful: prefill rarely filled past 200
                // tokens but the CPU sampler pays per slot allocated.
                maxTokens = 512,
                systemMessage = SYSTEM_MESSAGE,
                // Structured JSON output benefits from low entropy. 0.1 + topK=10
                // keeps the model deterministic (less drift mid-array) and cuts
                // per-token decode time on CPU-sampled fallback.
                temperature = 0.1,
                topK = 10,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gemma 4 vision inference failed", e)
            return emptyList()
        }

        val elapsedMs = System.currentTimeMillis() - startMs
        Log.d(TAG, "Raw response (${elapsedMs}ms): ${response.take(800)}")

        val detections = parseDetections(response)
        Log.i(TAG, "Detected ${detections.size} objects in ${elapsedMs}ms")
        return detections.filter { it.confidence >= confidenceThreshold }
    }

    override fun release() {
        // Engine lifecycle managed by OceanGuardApp -- do not release here.
    }

    // ---------------------------------------------------------------------
    // JSON parsing with robust fallbacks
    // ---------------------------------------------------------------------

    /**
     * Parses Gemma 4's JSON response into [DetectionResult] list.
     *
     * Handles multiple response formats:
     * - Clean JSON array: `[{"box_2d": [...], "label": "...", "material": "..."}]`
     * - Markdown-wrapped: ````json ... ````
     * - Empty/no-debris: `[]` or free-text "no debris"
     * - Missing material field: inferred from label via [inferMaterial]
     * - Malformed: returns empty list (never crashes)
     */
    private fun parseDetections(raw: String): List<DetectionResult> {
        val json = extractJsonArray(raw) ?: return emptyList()
        return try {
            val array = JsonParser.parseString(json).asJsonArray
            array.mapNotNull { element ->
                try {
                    val obj = element.asJsonObject
                    val box = obj.getAsJsonArray("box_2d")
                    if (box == null || box.size() < 4) return@mapNotNull null

                    val label = obj.get("label")?.asString ?: return@mapNotNull null
                    val materialStr = obj.get("material")?.asString

                    // Resolve label and read back HOW it was resolved so we can
                    // assign a realistic confidence (exact matches are high
                    // certainty, generic catch-alls are low). Gemma 4 does not
                    // expose token logprobs through LiteRT-LM and emitting an
                    // explicit confidence field in the JSON would degrade
                    // detection quality (see feedback_detector_prompt.md), so
                    // we derive it deterministically from the resolution path.
                    val resolution = resolveDebrisTypeWithConfidence(label, materialStr)
                    val subType = resolution.type                  // fine-grained, e.g. PLASTIC_BAG
                    val canonical = subType.canonical()            // family, e.g. PLASTIC_DEBRIS
                    // Use the sub-type for material inference so that, e.g., a
                    // GLASS_BOTTLE sub-type contributes GLASS rather than the
                    // canonical BOTTLE family's PLASTIC default.
                    val material = if (materialStr != null) {
                        MATERIAL_MAP[materialStr.lowercase().replace(" ", "_")]
                            ?: inferMaterial(subType)
                    } else {
                        inferMaterial(subType)
                    }

                    // box_2d is [y_min, x_min, y_max, x_max] on 1000-grid
                    val yMin = clampCoord(box[0].asFloat)
                    val xMin = clampCoord(box[1].asFloat)
                    val yMax = clampCoord(box[2].asFloat)
                    val xMax = clampCoord(box[3].asFloat)

                    // Material/type consistency bonus: if Gemma's material
                    // string lines up with what we'd infer from the resolved
                    // type, treat that as corroboration and nudge confidence up.
                    val consistencyBonus = if (materialStr != null) {
                        val mappedMaterial = MATERIAL_MAP[materialStr.lowercase().replace(" ", "_")]
                        if (mappedMaterial != null && mappedMaterial == inferMaterial(subType)) 0.05f else 0f
                    } else 0f
                    val finalConfidence = (resolution.confidence + consistencyBonus).coerceIn(0.30f, 0.95f)

                    DetectionResult(
                        x1 = xMin / 1000f,
                        y1 = yMin / 1000f,
                        x2 = xMax / 1000f,
                        y2 = yMax / 1000f,
                        classId = canonical.ordinal,
                        className = canonical.name,        // canonical family (back-compat)
                        confidence = finalConfidence,
                        material = material,
                        rawLabel = label,                  // verbatim VLM label for the BBox overlay
                        subType = subType.name,            // fine-grained sub-type for icon / list chip
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping malformed detection element: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse JSON array: ${e.message}")
            emptyList()
        }
    }

    /** Extracts the first JSON array from a potentially noisy response. */
    private fun extractJsonArray(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            return trimmed.substringBeforeLast("]") + "]"
        }
        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?(\\[.*?])\\s*```", RegexOption.DOT_MATCHES_ALL)
        codeBlockPattern.find(trimmed)?.let { return it.groupValues[1] }
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1)
        }
        return null
    }

    /**
     * Resolves a free-form VLM label to one of the 11 canonical [DebrisType]s.
     *
     * Every return path runs through [DebrisType.canonical] so the result is
     * guaranteed to be in [DebrisType.CANONICAL] — extended types (PLASTIC_BAG,
     * STYROFOAM, ...) never leak into detections, MarineDex unlocks, or reports.
     *
     * Priority:
     *   1. Exact match in [LABEL_TO_TYPE] (already canonical by construction).
     *   2. Material-prefixed semantic match (e.g. "Glass_Cup" -> GLASS_DEBRIS,
     *      not PLASTIC_CUP). The material prefix in the label is a stronger
     *      signal than any sub-string match against generic keys like "cup".
     *   3. The optional [materialHint] (the JSON `material` field) when the
     *      label itself contains no material word.
     *   4. Longest-key-first partial match against [LABEL_TO_TYPE], so that
     *      "plastic_water_bottle" hits "bottle" instead of "cup".
     *   5. Plastic-debris catch-all for generic litter words.
     *   6. [DebrisType.fromString] -> [DebrisType.canonical] as last resort,
     *      then [DebrisType.OTHER].
     */
    private fun resolveDebrisType(label: String, materialHint: String? = null): DebrisType =
        resolveDebrisTypeWithConfidence(label, materialHint).type

    /** Detection result paired with a derived confidence in [0.30, 0.95]. */
    internal data class ResolvedDetection(val type: DebrisType, val confidence: Float)

    /**
     * Same resolution priority as [resolveDebrisType] but also returns a
     * realistic confidence based on WHICH path matched:
     *   1. Exact label match      -> 0.85 (e.g. Gemma said "fishing_net" verbatim)
     *   2. Label material prefix  -> 0.78 (e.g. "Glass_Cup" -> GLASS_DEBRIS)
     *   3. Material hint only     -> 0.65 (label was unknown, only material agreed)
     *   4. Compound-word partial  -> 0.72 (e.g. "plastic_water_bottle" -> BOTTLE)
     *   5. Generic litter catch   -> 0.55 (e.g. "trash" -> PLASTIC_DEBRIS)
     *   6. Unmapped -> OTHER      -> 0.40 (we genuinely could not place it)
     *
     * Gemma 4 does not expose token-level probabilities through LiteRT-LM
     * (and the documented JSON `confidence` field destabilises the parser on
     * Gemma 4 E2B, per feedback_detector_prompt.md), so this deterministic
     * mapping is the closest we can get to a per-detection certainty score.
     */
    private fun resolveDebrisTypeWithConfidence(
        label: String,
        materialHint: String? = null,
    ): ResolvedDetection {
        val normalized = label.trim().lowercase().replace(" ", "_")

        // 1. Exact match — return the SUB-TYPE, not the canonical parent.
        //    The caller (parseDetections) keeps both: subType for the
        //    bounding-box / list icon, subType.canonical() for the
        //    MarineDex / achievements / reports.
        LABEL_TO_TYPE[normalized]?.let {
            return ResolvedDetection(it, 0.85f)
        }

        // 2. Material-prefixed semantic match (label-driven). These map straight
        //    to the canonical *_DEBRIS bucket because there is no finer
        //    sub-type to recover ("glass_X" with X unknown).
        materialFromText(normalized)?.let { type ->
            Log.i(TAG, "Label '$label' resolved by label material prefix to ${type.name}")
            return ResolvedDetection(type, 0.78f)
        }

        // 3. Material hint from JSON `material` field — same rationale.
        if (materialHint != null) {
            val hintNorm = materialHint.trim().lowercase().replace(" ", "_")
            materialFromText(hintNorm)?.let { type ->
                Log.i(TAG, "Label '$label' resolved by material hint '$materialHint' to ${type.name}")
                return ResolvedDetection(type, 0.65f)
            }
        }

        // 4. Longest-key-first partial match. We deliberately drop the inverse
        //    direction (key.contains(normalized)) because a short Gemma 4 label
        //    like "net" or "rope" should NOT be force-fit into a longer category
        //    such as "fishing_net" / "fishing_rope": that path was the main
        //    source of FISHING_NET false positives (decorative rope, sports
        //    nets, navigation buoys all got hijacked). Only match when the
        //    Gemma label genuinely contains a known compound word. Sub-type
        //    is preserved.
        for (key in LABEL_TO_TYPE.keys.sortedByDescending { it.length }) {
            if (normalized.contains(key)) {
                return ResolvedDetection(LABEL_TO_TYPE.getValue(key), 0.72f)
            }
        }

        // 5. Plastic-debris catch-all for generic litter words. No sub-type to
        //    recover, so we stay at the canonical bucket.
        if (
            "plastic" in normalized || "polymer" in normalized ||
            "trash" in normalized || "garbage" in normalized || "litter" in normalized ||
            "rubbish" in normalized || "junk" in normalized || "debris" in normalized ||
            "fragment" in normalized || "piece" in normalized
        ) {
            Log.w(TAG, "Label '$label' fell back to PLASTIC_DEBRIS catch-all")
            return ResolvedDetection(DebrisType.PLASTIC_DEBRIS, 0.55f)
        }

        // 6. Last resort — DebrisType.fromString may hit an extended type (preserve it).
        val direct = DebrisType.fromString(label)
        if (direct == DebrisType.OTHER && normalized != "other") {
            Log.w(TAG, "Label '$label' (normalized='$normalized') unmapped -> OTHER. Add to LABEL_TO_TYPE or taxonomy.")
        }
        // OTHER from unmapped label: low confidence. OTHER from explicit "other"
        // label: medium confidence (the model deliberately chose it).
        val confidence = if (normalized == "other") 0.60f else 0.40f
        return ResolvedDetection(direct, confidence)
    }

    /**
     * Given a normalized text fragment that may contain a material noun,
     * return the matching canonical [DebrisType], or null if none match.
     * Used by both the label and the JSON `material` field.
     */
    private fun materialFromText(text: String): DebrisType? = when {
        "glass" in text -> DebrisType.GLASS_DEBRIS
        "metal" in text || "aluminum" in text || "aluminium" in text || "steel" in text || "tin" in text ->
            DebrisType.METAL_DEBRIS
        "fabric" in text || "textile" in text || "cloth" in text -> DebrisType.FABRIC_DEBRIS
        "plastic" in text -> DebrisType.PLASTIC_DEBRIS
        "rubber" in text -> DebrisType.TIRE
        "wood" in text || "paper" in text || "ceramic" in text -> DebrisType.OTHER
        else -> null
    }

    /**
     * Infers [DebrisMaterial] from a canonical [DebrisType] when the VLM omits
     * the material field. Operates on the canonical projection so extended types
     * that slip through still produce sensible materials.
     */
    private fun inferMaterial(type: DebrisType): DebrisMaterial = when (type.canonical()) {
        DebrisType.BOTTLE,
        DebrisType.PLASTIC_DEBRIS,
        DebrisType.MASK -> DebrisMaterial.PLASTIC

        DebrisType.CAN,
        DebrisType.METAL_DEBRIS -> DebrisMaterial.METAL

        DebrisType.FISHING_NET -> DebrisMaterial.FISHING_NET
        DebrisType.GLOVE,
        DebrisType.FABRIC_DEBRIS -> DebrisMaterial.FABRIC
        DebrisType.TIRE -> DebrisMaterial.RUBBER
        DebrisType.GLASS_DEBRIS -> DebrisMaterial.GLASS

        DebrisType.OTHER -> DebrisMaterial.OTHER

        // Unreachable after canonical(); kept exhaustive for compiler peace.
        else -> DebrisMaterial.OTHER
    }

    /** Clamps a coordinate to [0, 1000] and handles out-of-range values. */
    private fun clampCoord(value: Float): Float = min(1000f, max(0f, value))
}
