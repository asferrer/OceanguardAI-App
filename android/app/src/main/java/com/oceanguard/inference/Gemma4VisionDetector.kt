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
         * Open-vocabulary prompt: Gemma 4 identifies ANY marine debris type
         * and its material. Kept deliberately short and permissive — empirical
         * A/B (memory: feedback_detector_prompt.md) showed that closed-class
         * lists, chain-of-thought, and confidence fields degrade quality on
         * Gemma 4 E2B. The taxonomy filtering happens client-side in
         * [LABEL_TO_TYPE] + [resolveDebrisType], not in the prompt.
         *
         * Examples cover diverse materials so the model doesn't bias toward
         * plastic; the trailing hint nudges it toward `*_debris` fallbacks
         * when the specific type is unclear, which keeps every detection
         * routable to a [DebrisType] in the app's taxonomy.
         */
        private val DETECTION_PROMPT = """
Detect ALL marine debris and litter in this image. Output ONLY a JSON array.

For each object, provide:
- "box_2d": bounding box as [y_min, x_min, y_max, x_max], integers 0-1000
- "label": specific object type using snake_case
  (e.g. plastic_bottle, glass_bottle, aluminum_can, fishing_net, fishing_line, rope, cigarette_butt, plastic_bag, styrofoam, glove, mask, tire, syringe, battery, clothing, cardboard, lumber, ceramic_fragment, paint_can)
- "material": the primary material (Plastic, Metal, Glass, Rubber, Fabric, Fishing_Net, Wood, Paper, Ceramic, Chemical)

Output format:
[{"box_2d": [y_min, x_min, y_max, x_max], "label": "object_type", "material": "material_type"}]

Be specific: distinguish plastic_bottle from glass_bottle, aluminum_can from tin_can, etc.
If you can't identify the specific type, fall back to a material-based label:
glass_debris, metal_debris, plastic_debris, fabric_debris (one of these is always correct).
If no debris is found, output: []
""".trimIndent()

        private const val SYSTEM_MESSAGE =
            "You are a marine debris detection system. Output ONLY valid JSON arrays. " +
            "Never include explanations, markdown, or text outside the JSON."

        /**
         * Maps Gemma 4 free-form labels to [DebrisType] enum values.
         * Keys are lowercase with underscores. First match wins.
         */
        private val LABEL_TO_TYPE: Map<String, DebrisType> = buildMap {
            // Exact matches for extended types
            put("bottle_cap", DebrisType.BOTTLE_CAP)
            put("plastic_bag", DebrisType.PLASTIC_BAG)
            put("grocery_bag", DebrisType.PLASTIC_BAG)
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
            put("cigarette_butt", DebrisType.CIGARETTE_BUTT)
            put("cigarette", DebrisType.CIGARETTE_BUTT)
            put("cigarette_lighter", DebrisType.CIGARETTE_LIGHTER)
            put("lighter", DebrisType.CIGARETTE_LIGHTER)
            put("fishing_line", DebrisType.FISHING_LINE)
            put("rope", DebrisType.ROPE)
            put("cordage", DebrisType.ROPE)
            put("fishing_buoy", DebrisType.FISHING_BUOY)
            put("buoy", DebrisType.FISHING_BUOY)
            put("float", DebrisType.FISHING_BUOY)
            put("fishing_trap", DebrisType.FISHING_TRAP)
            put("trap", DebrisType.FISHING_TRAP)
            put("crab_pot", DebrisType.FISHING_TRAP)
            put("lobster_pot", DebrisType.FISHING_TRAP)
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
            put("glass_bottle", DebrisType.GLASS_BOTTLE)
            put("glass_jar", DebrisType.GLASS_JAR)
            put("jar", DebrisType.GLASS_JAR)
            put("glass_fragment", DebrisType.GLASS_FRAGMENT)
            put("glass_shard", DebrisType.GLASS_FRAGMENT)
            put("light_bulb", DebrisType.LIGHT_BULB)
            put("bulb", DebrisType.LIGHT_BULB)
            put("fluorescent", DebrisType.LIGHT_BULB)
            put("flip_flop", DebrisType.FLIP_FLOP)
            put("sandal", DebrisType.FLIP_FLOP)
            put("rubber_hose", DebrisType.RUBBER_HOSE)
            put("hose", DebrisType.RUBBER_HOSE)
            put("clothing", DebrisType.CLOTHING)
            put("clothes", DebrisType.CLOTHING)
            put("shirt", DebrisType.CLOTHING)
            put("pants", DebrisType.CLOTHING)
            put("shoe", DebrisType.SHOE)
            put("footwear", DebrisType.SHOE)
            put("boot", DebrisType.SHOE)
            put("cardboard", DebrisType.CARDBOARD)
            put("cardboard_box", DebrisType.CARDBOARD)
            put("paper", DebrisType.PAPER)
            put("newspaper", DebrisType.PAPER)
            put("wood_pallet", DebrisType.WOOD_PALLET)
            put("pallet", DebrisType.WOOD_PALLET)
            put("crate", DebrisType.WOOD_PALLET)
            put("lumber", DebrisType.LUMBER)
            put("plywood", DebrisType.LUMBER)
            put("ceramic_fragment", DebrisType.CERAMIC_FRAGMENT)
            put("ceramic", DebrisType.CERAMIC_FRAGMENT)
            put("pottery", DebrisType.CERAMIC_FRAGMENT)
            put("brick", DebrisType.BRICK)
            put("concrete", DebrisType.BRICK)
            put("paint_can", DebrisType.PAINT_CAN)
            put("oil_container", DebrisType.OIL_CONTAINER)
            put("jerry_can", DebrisType.OIL_CONTAINER)
            put("syringe", DebrisType.SYRINGE)
            put("needle", DebrisType.SYRINGE)
            put("medical_waste", DebrisType.SYRINGE)
            put("chemical_drum", DebrisType.CHEMICAL_DRUM)
            // RT-DETRv2 core class names (fallback compatibility)
            put("bottle", DebrisType.BOTTLE)
            put("plastic_bottle", DebrisType.BOTTLE)
            put("can", DebrisType.CAN)
            put("aluminum_can", DebrisType.CAN)
            put("tin_can", DebrisType.CAN)
            put("beverage_can", DebrisType.CAN)
            put("fishing_net", DebrisType.FISHING_NET)
            put("net", DebrisType.FISHING_NET)
            put("ghost_net", DebrisType.FISHING_NET)
            put("glove", DebrisType.GLOVE)
            put("mask", DebrisType.MASK)
            put("face_mask", DebrisType.MASK)
            put("metal_debris", DebrisType.METAL_DEBRIS)
            put("scrap_metal", DebrisType.METAL_DEBRIS)
            put("plastic_debris", DebrisType.PLASTIC_DEBRIS)
            put("plastic_fragment", DebrisType.PLASTIC_DEBRIS)
            put("tire", DebrisType.TIRE)
            put("tyre", DebrisType.TIRE)
            // Material-debris catch-alls (matches the prompt's fallback hints)
            put("fabric_debris", DebrisType.FABRIC_DEBRIS)
            put("textile_debris", DebrisType.FABRIC_DEBRIS)
            put("textile", DebrisType.FABRIC_DEBRIS)
            put("glass_debris", DebrisType.GLASS_DEBRIS)
            // Common labels Gemma 4 emits that the resolver should not get wrong
            put("glass_cup", DebrisType.GLASS_DEBRIS)
            put("glass_mug", DebrisType.GLASS_DEBRIS)
            put("mug", DebrisType.CERAMIC_FRAGMENT)
            put("ceramic_mug", DebrisType.CERAMIC_FRAGMENT)
            put("ceramic_cup", DebrisType.CERAMIC_FRAGMENT)
            put("dish", DebrisType.CERAMIC_FRAGMENT)
            put("plate", DebrisType.CERAMIC_FRAGMENT)
            put("bowl", DebrisType.CERAMIC_FRAGMENT)
            // Fishing extras (these are sometimes generated as the broad term)
            put("fishing_rod", DebrisType.FISHING_TRAP)
            put("fishing_pole", DebrisType.FISHING_TRAP)
            put("fishing_gear", DebrisType.FISHING_NET)
            // Plastic bag plurals & generics
            put("bag", DebrisType.PLASTIC_BAG)
            put("plastic_grocery_bag", DebrisType.PLASTIC_BAG)
            put("shopping_bag", DebrisType.PLASTIC_BAG)
            // Generic litter (model often falls back to these when uncertain)
            put("litter", DebrisType.PLASTIC_DEBRIS)
            put("debris", DebrisType.PLASTIC_DEBRIS)
            put("trash", DebrisType.PLASTIC_DEBRIS)
            put("garbage", DebrisType.PLASTIC_DEBRIS)
            put("waste", DebrisType.PLASTIC_DEBRIS)
            // Broad material fallbacks
            put("fabric_debris", DebrisType.FABRIC_DEBRIS)
            put("glass_debris", DebrisType.GLASS_DEBRIS)
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
    override val inputSize = 640

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
                maxTokens = 1024,
                systemMessage = SYSTEM_MESSAGE,
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

                    // Map label to DebrisType (material hint disambiguates "Glass_Cup" vs "Plastic_Cup")
                    val debrisType = resolveDebrisType(label, materialStr)
                    // Map material string or infer from type
                    val material = if (materialStr != null) {
                        MATERIAL_MAP[materialStr.lowercase().replace(" ", "_")]
                            ?: inferMaterial(debrisType)
                    } else {
                        inferMaterial(debrisType)
                    }

                    // box_2d is [y_min, x_min, y_max, x_max] on 1000-grid
                    val yMin = clampCoord(box[0].asFloat)
                    val xMin = clampCoord(box[1].asFloat)
                    val yMax = clampCoord(box[2].asFloat)
                    val xMax = clampCoord(box[3].asFloat)

                    DetectionResult(
                        x1 = xMin / 1000f,
                        y1 = yMin / 1000f,
                        x2 = xMax / 1000f,
                        y2 = yMax / 1000f,
                        classId = debrisType.ordinal,
                        className = debrisType.name,
                        confidence = 0.75f,
                        material = material,
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
     * Resolves a free-form VLM label to a [DebrisType].
     *
     * Priority:
     *   1. Exact match in [LABEL_TO_TYPE].
     *   2. Material-prefixed semantic match (e.g. "Glass_Cup" → GLASS_DEBRIS,
     *      not PLASTIC_CUP). The material prefix in the label is a stronger
     *      signal than any sub-string match against generic keys like "cup".
     *   3. The optional [materialHint] (the JSON `material` field) when the
     *      label itself contains no material word.
     *   4. Longest-key-first partial match against [LABEL_TO_TYPE], so that
     *      "plastic_water_bottle" hits "bottle" instead of "cup".
     *   5. Plastic-debris catch-all for generic litter words.
     *   6. [DebrisType.fromString] as last resort, then [DebrisType.OTHER].
     */
    private fun resolveDebrisType(label: String, materialHint: String? = null): DebrisType {
        val normalized = label.trim().lowercase().replace(" ", "_")

        // 1. Exact match
        LABEL_TO_TYPE[normalized]?.let { return it }

        // 2. Material-prefixed semantic match (label-driven)
        materialFromText(normalized)?.let { type ->
            Log.i(TAG, "Label '$label' resolved by label material prefix to ${type.name}")
            return type
        }

        // 3. Material hint from JSON `material` field
        if (materialHint != null) {
            val hintNorm = materialHint.trim().lowercase().replace(" ", "_")
            materialFromText(hintNorm)?.let { type ->
                Log.i(TAG, "Label '$label' resolved by material hint '$materialHint' to ${type.name}")
                return type
            }
        }

        // 4. Longest-key-first partial match (so "glass_bottle" beats "bottle")
        for (key in LABEL_TO_TYPE.keys.sortedByDescending { it.length }) {
            if (normalized.contains(key) || key.contains(normalized)) {
                return LABEL_TO_TYPE.getValue(key)
            }
        }

        // 5. Plastic-debris catch-all for generic litter words
        if (
            "plastic" in normalized || "polymer" in normalized ||
            "trash" in normalized || "garbage" in normalized || "litter" in normalized ||
            "rubbish" in normalized || "junk" in normalized || "debris" in normalized ||
            "fragment" in normalized || "piece" in normalized
        ) {
            Log.w(TAG, "Label '$label' fell back to PLASTIC_DEBRIS catch-all")
            return DebrisType.PLASTIC_DEBRIS
        }

        // 6. Last resort
        val direct = DebrisType.fromString(label)
        if (direct == DebrisType.OTHER && normalized != "other") {
            Log.w(TAG, "Label '$label' (normalized='$normalized') unmapped -> OTHER. Add to LABEL_TO_TYPE or taxonomy.")
        }
        return direct
    }

    /**
     * Given a normalized text fragment that may contain a material noun,
     * return the matching [DebrisType], or null if none match.
     * Used by both the label and the JSON `material` field.
     */
    private fun materialFromText(text: String): DebrisType? = when {
        "glass" in text -> DebrisType.GLASS_DEBRIS
        "metal" in text || "aluminum" in text || "aluminium" in text || "steel" in text || "tin" in text ->
            DebrisType.METAL_DEBRIS
        "fabric" in text || "textile" in text || "cloth" in text -> DebrisType.FABRIC_DEBRIS
        "wood" in text -> DebrisType.LUMBER
        "rubber" in text -> DebrisType.RUBBER_HOSE
        "ceramic" in text -> DebrisType.CERAMIC_FRAGMENT
        else -> null
    }

    /** Infers [DebrisMaterial] from [DebrisType] when the VLM omits it. */
    private fun inferMaterial(type: DebrisType): DebrisMaterial = when (type) {
        DebrisType.BOTTLE, DebrisType.PLASTIC_DEBRIS, DebrisType.BOTTLE_CAP,
        DebrisType.PLASTIC_BAG, DebrisType.FOOD_WRAPPER, DebrisType.STYROFOAM,
        DebrisType.PLASTIC_CUP, DebrisType.STRAW, DebrisType.PLASTIC_UTENSIL,
        DebrisType.SIX_PACK_RING, DebrisType.PLASTIC_SHEETING, DebrisType.DIAPER,
        DebrisType.MASK -> DebrisMaterial.PLASTIC

        DebrisType.CAN, DebrisType.METAL_DEBRIS, DebrisType.AEROSOL_CAN,
        DebrisType.METAL_DRUM, DebrisType.WIRE_CABLE, DebrisType.BATTERY,
        DebrisType.ELECTRONICS -> DebrisMaterial.METAL

        DebrisType.GLASS_BOTTLE, DebrisType.GLASS_JAR, DebrisType.GLASS_FRAGMENT,
        DebrisType.GLASS_DEBRIS, DebrisType.LIGHT_BULB -> DebrisMaterial.GLASS

        DebrisType.TIRE, DebrisType.FLIP_FLOP, DebrisType.RUBBER_HOSE -> DebrisMaterial.RUBBER

        DebrisType.FISHING_NET, DebrisType.FISHING_LINE, DebrisType.ROPE,
        DebrisType.FISHING_BUOY, DebrisType.FISHING_TRAP -> DebrisMaterial.FISHING_NET

        DebrisType.GLOVE, DebrisType.FABRIC_DEBRIS, DebrisType.CLOTHING,
        DebrisType.SHOE -> DebrisMaterial.FABRIC

        DebrisType.CIGARETTE_BUTT, DebrisType.CIGARETTE_LIGHTER -> DebrisMaterial.PLASTIC

        DebrisType.CARDBOARD, DebrisType.PAPER -> DebrisMaterial.PAPER
        DebrisType.WOOD_PALLET, DebrisType.LUMBER -> DebrisMaterial.WOOD
        DebrisType.CERAMIC_FRAGMENT, DebrisType.BRICK -> DebrisMaterial.CERAMIC
        DebrisType.PAINT_CAN, DebrisType.OIL_CONTAINER, DebrisType.SYRINGE,
        DebrisType.CHEMICAL_DRUM -> DebrisMaterial.CHEMICAL

        DebrisType.OTHER -> DebrisMaterial.OTHER
    }

    /** Clamps a coordinate to [0, 1000] and handles out-of-range values. */
    private fun clampCoord(value: Float): Float = min(1000f, max(0f, value))
}
