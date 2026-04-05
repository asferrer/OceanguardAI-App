package com.oceanguard.ai.inference

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.oceanguard.ai.utils.BitmapAnnotator
import com.oceanguard.ai.utils.VideoEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "VideoProcessor"
private const val MAX_WIDTH = 1920
private const val MAX_HEIGHT = 1080
private const val DEFAULT_FRAME_RATE = 30
private const val ENCODER_BIT_RATE_MULTIPLIER = 4

data class VideoProgress(
    val currentFrame: Int,
    val totalFrames: Int,
    val elapsedTimeMs: Long,
    val estimatedRemainingMs: Long,
    /** Latest annotated frame for live preview. Null when no frame processed yet. */
    val liveFrame: Bitmap? = null,
)

data class VideoProcessingResult(
    val outputVideoUri: String,
    val thumbnailUri: String?,
    val durationMs: Long,
    val totalFrameCount: Int,
    val processedFrameCount: Int,
    val uniqueDebrisCount: Int,
    val classCounts: Map<String, Int>,
    val totalProcessingTimeMs: Long,
    val avgInferenceTimeMs: Float,
    val healthScore: Int
)

class VideoProcessor(
    private val context: Context,
    private val detector: ObjectDetector,
    private val confidenceThreshold: Float = 0.5f,
) {
    private val _progress = MutableStateFlow(VideoProgress(0, 0, 0L, 0L))
    val progress: StateFlow<VideoProgress> = _progress.asStateFlow()

    private val _cancelled = AtomicBoolean(false)
    var isCancelled: Boolean
        get() = _cancelled.get()
        set(value) { _cancelled.set(value) }

    suspend fun processVideo(videoUri: Uri): VideoProcessingResult = withContext(Dispatchers.Default) {
        val startTimeMs = System.currentTimeMillis()
        val retriever = MediaMetadataRetriever()
        var encoder: VideoEncoder? = null
        var outputFile: File? = null

        try {
            try {
                retriever.setDataSource(context, videoUri)
            } catch (e: Exception) {
                throw IllegalArgumentException("Cannot open video: file may be corrupted or unsupported", e)
            }
            val (durationMs, frameRate, outputW, outputH) = probeVideo(retriever)
            if (durationMs <= 0L) {
                throw IllegalArgumentException("Invalid video: duration is $durationMs ms")
            }
            if (outputW <= 0 || outputH <= 0) {
                throw IllegalArgumentException("Invalid video dimensions: ${outputW}x${outputH}")
            }
            val totalFrames = ((durationMs * frameRate) / 1000L).toInt().coerceAtLeast(1)
            val intervalUs = 1_000_000L / frameRate

            outputFile = prepareOutputFile()
            encoder = VideoEncoder(outputFile, outputW, outputH, frameRate, outputW * outputH * ENCODER_BIT_RATE_MULTIPLIER)
            encoder.start()

            val tracker = IoUTracker()
            val inferenceTimes = mutableListOf<Long>()
            var processedFrames = 0
            var thumbnailUri: String? = null

            for (frameIdx in 0 until totalFrames) {
                if (isCancelled) {
                    encoder.release()
                    outputFile.delete()
                    throw CancellationException("Video processing cancelled at frame $frameIdx")
                }

                val timeUs = frameIdx * intervalUs
                val rawBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (rawBitmap == null) {
                    Log.w(TAG, "Frame $frameIdx skipped: getFrameAtTime returned null at ${timeUs}us")
                    continue
                }

                val t0 = System.currentTimeMillis()
                val detections = detector.detect(rawBitmap, confidenceThreshold)
                inferenceTimes.add(System.currentTimeMillis() - t0)

                val trackingResult = tracker.update(frameIdx, detections)
                val annotated = BitmapAnnotator.annotateWithTrackIds(rawBitmap, trackingResult.activeTracks)

                if (frameIdx == 0) thumbnailUri = saveThumbnail(annotated)

                encoder.encodeFrame(annotated, timeUs)

                // Emit live preview frame (copy for UI, recycle original)
                val previewCopy = annotated.copy(annotated.config ?: Bitmap.Config.ARGB_8888, false)
                rawBitmap.recycle()
                annotated.recycle()

                processedFrames++
                emitProgress(frameIdx + 1, totalFrames, startTimeMs, inferenceTimes, previewCopy)
            }

            encoder.finish()

            val finalResult = tracker.getFinalResult()
            return@withContext VideoProcessingResult(
                outputVideoUri = Uri.fromFile(outputFile).toString(),
                thumbnailUri = thumbnailUri,
                durationMs = durationMs,
                totalFrameCount = totalFrames,
                processedFrameCount = processedFrames,
                uniqueDebrisCount = finalResult.totalUniqueCount,
                classCounts = finalResult.uniqueCountByClass,
                totalProcessingTimeMs = System.currentTimeMillis() - startTimeMs,
                avgInferenceTimeMs = if (inferenceTimes.isEmpty()) 0f else inferenceTimes.average().toFloat(),
                healthScore = calculateHealthScore(finalResult)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            outputFile?.delete()
            Log.e(TAG, "Video processing failed", e)
            throw e
        } finally {
            try { retriever.release() } catch (e: Exception) {
                Log.w(TAG, "MediaMetadataRetriever.release() failed", e)
            }
            if (encoder != null) try { encoder.release() } catch (e: Exception) {
                Log.w(TAG, "VideoEncoder.release() failed", e)
            }
        }
    }

    private data class VideoMeta(
        val durationMs: Long,
        val frameRate: Int,
        val width: Int,
        val height: Int
    )

    private fun probeVideo(retriever: MediaMetadataRetriever): VideoMeta {
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        // METADATA_KEY_CAPTURE_RATE = 25; available since API 28
        val frameRate = retriever.extractMetadata(25)
            ?.toFloatOrNull()?.toInt()?.takeIf { it > 0 } ?: DEFAULT_FRAME_RATE
        val srcW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: MAX_WIDTH
        val srcH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: MAX_HEIGHT

        val (outW, outH) = scaleDown(srcW, srcH)
        Log.i(TAG, "Video: ${srcW}x${srcH} -> ${outW}x${outH}, ${durationMs}ms @ ${frameRate}fps")
        return VideoMeta(durationMs, frameRate, outW, outH)
    }

    private fun scaleDown(w: Int, h: Int): Pair<Int, Int> {
        if (w <= MAX_WIDTH && h <= MAX_HEIGHT) return w to h
        val scale = minOf(MAX_WIDTH.toFloat() / w, MAX_HEIGHT.toFloat() / h)
        return ((w * scale).toInt() and 0xFFFFFFFE.toInt()) to
               ((h * scale).toInt() and 0xFFFFFFFE.toInt())
    }

    private fun prepareOutputFile(): File {
        val dir = File(context.filesDir, "videos/annotated").also { it.mkdirs() }
        return File(dir, "video_${System.currentTimeMillis()}.mp4")
    }

    private fun saveThumbnail(bitmap: Bitmap): String? {
        return try {
            val dir = File(context.filesDir, "videos/thumbnails").also { it.mkdirs() }
            val file = File(dir, "thumb_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save thumbnail", e)
            null
        }
    }

    private fun emitProgress(
        current: Int,
        total: Int,
        startMs: Long,
        inferenceTimes: List<Long>,
        liveFrame: Bitmap? = null,
    ) {
        val elapsed = System.currentTimeMillis() - startMs
        val avgMs = if (inferenceTimes.isEmpty()) 0L else inferenceTimes.average().toLong()
        val remaining = avgMs * (total - current)
        // Capture the outgoing frame reference BEFORE publishing the new value.
        // Recycling after the swap ensures no collector can receive a recycled bitmap:
        // once the new value is visible, the old one is no longer reachable via StateFlow.
        val outgoingFrame = _progress.value.liveFrame
        _progress.value = VideoProgress(current, total, elapsed, remaining, liveFrame)
        outgoingFrame?.recycle()
    }

    private fun calculateHealthScore(result: TrackingResult): Int {
        if (result.totalUniqueCount == 0) return 100
        val avgRisk = result.allTracks
            .filter { it.hitCount >= 2 }
            .map { riskForClass(it.className) }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0
        val densityFactor = minOf(result.totalUniqueCount / 10.0, 1.0)
        return (100 - (avgRisk * 10 + densityFactor * 30).toInt()).coerceIn(0, 100)
    }

    private fun riskForClass(className: String): Int = when (className.lowercase()) {
        "fishing_net"                  -> 5
        "bottle", "plastic_debris", "mask" -> 5
        "glove"                        -> 4
        "can", "metal_debris"          -> 3
        "tire"                         -> 3
        else                           -> 2
    }
}
