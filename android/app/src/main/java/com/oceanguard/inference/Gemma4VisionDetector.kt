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
         * and its material. Unlike RT-DETRv2's fixed 8 classes, this covers
         * all categories from NOAA MDMAP, OSPAR, and ICC classifications.
         */
        private val DETECTION_PROMPT = """
Detect ALL marine debris and litter in this image. Output ONLY a JSON array.

For each object, provide:
- "box_2d": bounding box as [y_min, x_min, y_max, x_max], integers 0-1000
- "label": specific object type using snake_case (e.g. Plastic_Bag, Glass_Bottle, Cigarette_Butt, Fishing_Line, Styrofoam, Flip_Flop, Rope, Cardboard, Syringe, etc.)
- "material": the primary material (Plastic, Metal, Glass, Rubber, Fabric, Fishing_Net, Wood, Paper, Ceramic, Chemical)

Output format:
[{"box_2d": [y_min, x_min, y_max, x_max], "label": "object_type", "material": "material_type"}]

Be specific: distinguish Plastic_Bottle from Glass_Bottle, Aluminum_Can from Tin_Can, etc.
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

                    // Map label to DebrisType
                    val debrisType = resolveDebrisType(label)
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
     * Tries exact match, then partial match, falling back to [DebrisType.OTHER].
     */
    private fun resolveDebrisType(label: String): DebrisType {
        val normalized = label.trim().lowercase().replace(" ", "_")
        // Exact match in lookup table
        LABEL_TO_TYPE[normalized]?.let { return it }
        // Partial match (e.g. "plastic_water_bottle" contains "bottle")
        for ((key, type) in LABEL_TO_TYPE) {
            if (normalized.contains(key) || key.contains(normalized)) return type
        }
        // Semantic fallbacks: map generic terms to the closest catch-all instead of
        // letting them silently collapse to OTHER (which poisons downstream reports).
        val semantic: DebrisType? = when {
            "plastic" in normalized || "polymer" in normalized -> DebrisType.PLASTIC_DEBRIS
            "metal" in normalized || "aluminum" in normalized || "steel" in normalized -> DebrisType.METAL_DEBRIS
            "glass" in normalized -> DebrisType.GLASS_DEBRIS
            "fabric" in normalized || "textile" in normalized || "cloth" in normalized -> DebrisType.FABRIC_DEBRIS
            "wood" in normalized -> DebrisType.LUMBER
            "rubber" in normalized -> DebrisType.RUBBER_HOSE
            "trash" in normalized || "garbage" in normalized || "litter" in normalized ||
                "rubbish" in normalized || "junk" in normalized || "debris" in normalized ||
                "fragment" in normalized || "piece" in normalized -> DebrisType.PLASTIC_DEBRIS
            else -> null
        }
        if (semantic != null) {
            Log.w(TAG, "Label '$label' (normalized='$normalized') fell back to semantic match ${semantic.name}")
            return semantic
        }
        // Last resort: try enum directly (fromString returns OTHER on mismatch).
        val direct = DebrisType.fromString(label)
        if (direct == DebrisType.OTHER && normalized != "other") {
            Log.w(TAG, "Label '$label' (normalized='$normalized') unmapped -> OTHER. Add to LABEL_TO_TYPE or taxonomy.")
        }
        return direct
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
