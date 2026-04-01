package com.oceanguard.ai.inference

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.oceanguard.ai.data.BoundingBox
import com.oceanguard.ai.data.Debris
import com.oceanguard.ai.data.DebrisDetection
import com.oceanguard.ai.data.DebrisMaterial
import com.oceanguard.ai.data.DebrisType
import com.oceanguard.ai.data.DetectionRepository
import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ImageQuality
import com.oceanguard.ai.utils.BitmapAnnotator
import com.oceanguard.ai.utils.LocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Manages continuous object detection on camera frames for live detection mode.
 *
 * Key design choices:
 * - Single inference at a time via Mutex (prevents overlap)
 * - Frame dropping: if inference is in progress, new frames are discarded
 * - Uses RTDETRInference.detectPreResized() to skip redundant resize
 * - FPS calculated over a rolling 1-second window
 * - Auto-saves frames with detections to history (throttled)
 */
class LiveDetectionManager(
    private val detector: ObjectDetector,
    private val context: Context? = null,
    private val repository: DetectionRepository? = null,
    private val locationProvider: LocationProvider? = null,
    var confidenceThreshold: Float = 0.7f,
) {
    companion object {
        private const val TAG = "LiveDetectionManager"
        private const val SAVE_INTERVAL_MS = 10_000L
    }

    data class LiveDetectionState(
        val detections: List<DetectionResult> = emptyList(),
        val inferenceTimeMs: Long = 0,
        val frameCount: Long = 0,
        val isProcessing: Boolean = false,
        val inputResolution: Int = 480,
        val fps: Float = 0f,
        val savedCount: Int = 0,
    )

    private val _state = MutableStateFlow(LiveDetectionState())
    val state: StateFlow<LiveDetectionState> = _state.asStateFlow()

    private val inferenceMutex = Mutex()

    @Volatile
    private var isActive = false

    // FPS calculation
    private var lastFpsTimestamp = 0L
    private var framesSinceLastFps = 0

    // Auto-save throttle
    private var lastSaveTimestamp = 0L
    private var savedCount = 0

    /**
     * Process a single camera frame.
     * Called from ImageAnalysis.Analyzer on the analysis executor.
     * Returns immediately if an inference is already in progress (frame dropping).
     * The bitmap is always resized to INPUT_SIZE (640) for the TFLite model.
     */
    suspend fun processFrame(bitmap: Bitmap) {
        if (!isActive) return
        if (inferenceMutex.isLocked) return // Drop frame — backpressure

        inferenceMutex.withLock {
            if (!isActive) return

            _state.value = _state.value.copy(isProcessing = true)

            try {
                val startTime = System.currentTimeMillis()

                val targetSize = detector.inputSize
                // Resize bitmap to detector's fixed input size
                val resized = if (bitmap.width != targetSize || bitmap.height != targetSize) {
                    Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
                } else {
                    bitmap
                }

                // Run inference directly (bypasses orchestrator, no VLM)
                val detections = detector.detect(resized, confidenceThreshold)

                if (resized !== bitmap) {
                    resized.recycle()
                }

                val inferenceTime = System.currentTimeMillis() - startTime

                // Auto-save frames with detections (throttled)
                if (detections.isNotEmpty() && shouldSave()) {
                    saveFrameToHistory(bitmap, detections, inferenceTime)
                }

                // FPS calculation (rolling 1-second window)
                framesSinceLastFps++
                val now = System.currentTimeMillis()
                val fps = if (now - lastFpsTimestamp >= 1000L) {
                    val calculatedFps = framesSinceLastFps * 1000f / (now - lastFpsTimestamp)
                    lastFpsTimestamp = now
                    framesSinceLastFps = 0
                    calculatedFps
                } else {
                    _state.value.fps
                }

                _state.value = LiveDetectionState(
                    detections = detections,
                    inferenceTimeMs = inferenceTime,
                    frameCount = _state.value.frameCount + 1,
                    isProcessing = false,
                    inputResolution = targetSize,
                    fps = fps,
                    savedCount = savedCount,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing failed", e)
                _state.value = _state.value.copy(isProcessing = false)
            }
        }
    }

    fun start() {
        isActive = true
        lastFpsTimestamp = System.currentTimeMillis()
        framesSinceLastFps = 0
        savedCount = 0
        lastSaveTimestamp = 0L
        _state.value = LiveDetectionState()
    }

    fun stop() {
        isActive = false
        _state.value = LiveDetectionState()
    }

    // ---------------------------------------------------------------------
    // Auto-save logic
    // ---------------------------------------------------------------------

    private fun shouldSave(): Boolean {
        if (context == null || repository == null) return false
        return System.currentTimeMillis() - lastSaveTimestamp >= SAVE_INTERVAL_MS
    }

    private suspend fun saveFrameToHistory(
        originalBitmap: Bitmap,
        detections: List<DetectionResult>,
        inferenceTimeMs: Long,
    ) {
        val ctx = context ?: return
        val repo = repository ?: return

        withContext(Dispatchers.IO) {
            try {
                // 1. Save original frame to file
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(System.currentTimeMillis())
                val photoFile = File(
                    ctx.getExternalFilesDir(null),
                    "OceanGuard_Live_$timestamp.jpg",
                )
                FileOutputStream(photoFile).use { out ->
                    originalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                val imageUri = Uri.fromFile(photoFile)

                // 2. Create annotated thumbnail
                val annotated = BitmapAnnotator.annotate(originalBitmap, detections)
                val thumbDir = File(ctx.filesDir, "annotated").also { it.mkdirs() }
                val thumbFile = File(thumbDir, "annotated_live_$timestamp.jpg")
                FileOutputStream(thumbFile).use { out ->
                    annotated.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                annotated.recycle()
                val thumbnailUri = Uri.fromFile(thumbFile).toString()

                // 3. Convert DetectionResult → Debris
                val imgW = originalBitmap.width.toFloat()
                val imgH = originalBitmap.height.toFloat()
                val debrisList = detections.map { det ->
                    Debris(
                        bbox = BoundingBox(
                            x = det.x1 * imgW,
                            y = det.y1 * imgH,
                            width = (det.x2 - det.x1) * imgW,
                            height = (det.y2 - det.y1) * imgH,
                        ),
                        material = classNameToMaterial(det.className),
                        type = classNameToType(det.className),
                        confidence = det.confidence,
                    )
                }

                // 4. Calculate health score
                val detection = DebrisDetection(
                    debrisList, debrisList.size, ImageQuality.FAIR, inferenceTimeMs,
                )
                val healthScore = detection.calculateHealthScore()

                // 5. Get GPS location
                val location = locationProvider?.getLastKnownLocation()

                // 6. Save session
                val session = DetectionSession(
                    imageUri = imageUri.toString(),
                    thumbnailUri = thumbnailUri,
                    debrisList = debrisList,
                    totalCount = debrisList.size,
                    healthScore = healthScore,
                    location = location,
                    imageQuality = ImageQuality.FAIR,
                    processingTimeMs = inferenceTimeMs,
                    tags = "live_detection",
                )
                repo.saveSession(session, skipAchievements = true)

                savedCount++
                lastSaveTimestamp = System.currentTimeMillis()
                Log.i(TAG, "Auto-saved live frame #$savedCount (${debrisList.size} objects)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save live detection frame", e)
            }
        }
    }

    private fun classNameToType(className: String): DebrisType = when (className.lowercase()) {
        "bottle" -> DebrisType.BOTTLE
        "can" -> DebrisType.CAN
        "fishing_net" -> DebrisType.FISHING_NET
        "glove" -> DebrisType.GLOVE
        "mask" -> DebrisType.MASK
        "metal_debris" -> DebrisType.METAL_DEBRIS
        "plastic_debris" -> DebrisType.PLASTIC_DEBRIS
        "tire" -> DebrisType.TIRE
        else -> DebrisType.OTHER
    }

    private fun classNameToMaterial(className: String): DebrisMaterial = when (className.lowercase()) {
        "bottle", "plastic_debris" -> DebrisMaterial.PLASTIC
        "can", "metal_debris" -> DebrisMaterial.METAL
        "fishing_net" -> DebrisMaterial.FISHING_NET
        "glove", "mask" -> DebrisMaterial.FABRIC
        "tire" -> DebrisMaterial.RUBBER
        else -> DebrisMaterial.OTHER
    }
}
