package com.oceanguard.ai.inference

import android.content.Context
import android.net.Uri
import android.util.Log
import com.oceanguard.ai.data.*
import com.oceanguard.ai.utils.ImagePreprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Orchestrates the dual inference pipeline:
 * 1. RT-DETRv2 for fast object detection (~30ms)
 * 2. Gemma 3n VLM for deep analysis (~8-15s) - only if debris detected
 *
 * Emits partial results via StateFlow for progressive UI updates.
 */
class DetectionOrchestrator(
    private val context: Context,
    private val rtdetrInference: RTDETRInference,
    private val vlmInference: OceanGuardInference
) {
    companion object {
        private const val TAG = "DetectionOrchestrator"
    }

    private val imagePreprocessor = ImagePreprocessor(context)

    private val _analysisState = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val analysisState: StateFlow<AnalysisState> = _analysisState.asStateFlow()

    /**
     * Full analysis pipeline
     *
     * @param imageUri URI of the image to analyze
     * @return Complete analysis result
     */
    suspend fun analyzeImage(
        imageUri: Uri,
        skipVLM: Boolean = false,
        confidenceThreshold: Float = 0.5f,
    ): AnalysisResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        try {
            _analysisState.value = AnalysisState.LoadingImage

            // Load and preprocess image
            val bitmap = imagePreprocessor.loadAndPreprocess(imageUri, 640)
            Log.i(TAG, "Image loaded: ${bitmap.width}x${bitmap.height}")

            // Step 1: Fast detection with RT-DETRv2
            _analysisState.value = AnalysisState.Detecting
            val detections = if (rtdetrInference.isReady()) {
                rtdetrInference.detect(bitmap, confidenceThreshold)
            } else {
                Log.w(TAG, "RT-DETRv2 not ready, skipping fast detection")
                emptyList()
            }

            val detectionTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "Fast detection: ${detections.size} objects in ${detectionTime}ms")

            // Emit partial result for immediate UI update
            _analysisState.value = AnalysisState.DetectionsReady(detections)

            // Step 2: Deep analysis with VLM (only if enabled and debris found or RT-DETR unavailable)
            var vlmAnalysis: DebrisDetection? = null
            if (!skipVLM && (detections.isNotEmpty() || !rtdetrInference.isReady())) {
                _analysisState.value = AnalysisState.AnalyzingDeep(detections)

                try {
                    vlmAnalysis = vlmInference.detectDebris(imageUri)
                    Log.i(TAG, "VLM analysis: ${vlmAnalysis.debrisList.size} objects")
                } catch (e: Exception) {
                    Log.e(TAG, "VLM analysis failed, using RT-DETR results only", e)
                }
            } else if (skipVLM) {
                Log.i(TAG, "VLM analysis skipped (disabled in settings)")
            }

            val totalTime = System.currentTimeMillis() - startTime

            // Build final result combining both models
            val result = buildResult(detections, vlmAnalysis, totalTime)

            _analysisState.value = AnalysisState.Complete(result)
            Log.i(TAG, "Analysis complete in ${totalTime}ms")

            result

        } catch (e: Exception) {
            Log.e(TAG, "Analysis pipeline failed", e)
            _analysisState.value = AnalysisState.Error(e.message ?: "Unknown error")
            throw e
        }
    }

    /**
     * Quick detection only (RT-DETRv2, no VLM)
     */
    suspend fun detectOnly(
        imageUri: Uri,
        confidenceThreshold: Float = 0.5f,
    ): List<DetectionResult> = withContext(Dispatchers.Default) {
        val bitmap = imagePreprocessor.loadAndPreprocess(imageUri, 640)
        rtdetrInference.detect(bitmap, confidenceThreshold)
    }

    /**
     * Build combined result from both detection models
     */
    private fun buildResult(
        rtdetrDetections: List<DetectionResult>,
        vlmAnalysis: DebrisDetection?,
        processingTimeMs: Long
    ): AnalysisResult {
        // Use VLM analysis if available (more detailed), otherwise convert RT-DETR results
        val debrisDetection = vlmAnalysis ?: convertRTDETRToDebrisDetection(rtdetrDetections)

        return AnalysisResult(
            rtdetrDetections = rtdetrDetections,
            vlmAnalysis = debrisDetection,
            healthScore = debrisDetection.calculateHealthScore(),
            totalDebrisCount = maxOf(rtdetrDetections.size, debrisDetection.debrisList.size),
            processingTimeMs = processingTimeMs,
            hasVLMAnalysis = vlmAnalysis != null
        )
    }

    /**
     * Convert RT-DETRv2 detections to DebrisDetection format
     * Used as fallback when VLM is not available
     */
    private fun convertRTDETRToDebrisDetection(detections: List<DetectionResult>): DebrisDetection {
        val debrisList = detections.map { det ->
            Debris(
                bbox = BoundingBox(det.x1, det.y1, det.width, det.height),
                material = classNameToMaterial(det.className),
                type = DebrisType.fromString(det.className),
                confidence = det.confidence
            )
        }
        return DebrisDetection(
            debrisList = debrisList,
            totalCount = debrisList.size,
            imageQuality = ImageQuality.FAIR
        )
    }

    /**
     * Map class name to material type
     */
    private fun classNameToMaterial(className: String): DebrisMaterial {
        return when (className.uppercase()) {
            "BOTTLE", "PLASTIC_DEBRIS" -> DebrisMaterial.PLASTIC
            "CAN", "METAL_DEBRIS" -> DebrisMaterial.METAL
            "FISHING_NET" -> DebrisMaterial.FISHING_NET
            "GLOVE", "MASK", "FABRIC_DEBRIS" -> DebrisMaterial.FABRIC
            "TIRE" -> DebrisMaterial.RUBBER
            "GLASS_DEBRIS" -> DebrisMaterial.GLASS
            else -> DebrisMaterial.OTHER
        }
    }

    /**
     * Reset state to idle
     */
    fun reset() {
        _analysisState.value = AnalysisState.Idle
    }
}

/**
 * Analysis pipeline state for progressive UI updates
 */
sealed class AnalysisState {
    data object Idle : AnalysisState()
    data object LoadingImage : AnalysisState()
    data object Detecting : AnalysisState()
    data class DetectionsReady(val detections: List<DetectionResult>) : AnalysisState()
    data class AnalyzingDeep(val detections: List<DetectionResult>) : AnalysisState()
    data class Complete(val result: AnalysisResult) : AnalysisState()
    data class Error(val message: String) : AnalysisState()
}

/**
 * Complete analysis result combining RT-DETRv2 and VLM
 */
data class AnalysisResult(
    val rtdetrDetections: List<DetectionResult>,
    val vlmAnalysis: DebrisDetection,
    val healthScore: Int,
    val totalDebrisCount: Int,
    val processingTimeMs: Long,
    val hasVLMAnalysis: Boolean
)
