package com.oceanguard.ai.inference

import kotlin.math.max
import kotlin.math.min

data class Track(
    val id: Int,
    val bbox: FloatArray,        // [x1, y1, x2, y2]
    val classId: Int,
    val className: String,
    var bestConfidence: Float,
    var lastSeenFrame: Int,
    var hitCount: Int,
    var isActive: Boolean = true
)

data class TrackingResult(
    val activeTracks: List<Track>,
    val allTracks: List<Track>,
    val uniqueCountByClass: Map<String, Int>,
    val totalUniqueCount: Int
)

class IoUTracker(
    private val iouThreshold: Float = 0.3f,
    private val maxAge: Int = 10,
    private val minHits: Int = 2
) {
    private val tracks = mutableListOf<Track>()
    private var nextId = 0

    fun update(frameIndex: Int, detections: List<DetectionResult>): TrackingResult {
        val activeTracks = tracks.filter { it.isActive }

        val matched = mutableSetOf<Int>()       // track ids
        val matchedDets = mutableSetOf<Int>()   // detection indices

        // Build candidate pairs sorted by IoU descending (same classId only)
        data class Candidate(val trackIdx: Int, val detIdx: Int, val iouScore: Float)

        val candidates = mutableListOf<Candidate>()
        activeTracks.forEachIndexed { ti, track ->
            detections.forEachIndexed { di, det ->
                if (track.classId == det.classId) {
                    val score = iou(
                        track.bbox[0], track.bbox[1], track.bbox[2], track.bbox[3],
                        det.x1, det.y1, det.x2, det.y2
                    )
                    if (score >= iouThreshold) candidates += Candidate(ti, di, score)
                }
            }
        }
        candidates.sortByDescending { it.iouScore }

        // Greedy 1-to-1 assignment
        for (c in candidates) {
            val track = activeTracks[c.trackIdx]
            if (track.id in matched || c.detIdx in matchedDets) continue
            val det = detections[c.detIdx]
            track.bbox[0] = det.x1; track.bbox[1] = det.y1
            track.bbox[2] = det.x2; track.bbox[3] = det.y2
            if (det.confidence > track.bestConfidence) track.bestConfidence = det.confidence
            track.lastSeenFrame = frameIndex
            track.hitCount++
            matched += track.id
            matchedDets += c.detIdx
        }

        // Unmatched detections -> new tracks
        detections.forEachIndexed { di, det ->
            if (di !in matchedDets) {
                tracks += Track(
                    id = nextId++,
                    bbox = floatArrayOf(det.x1, det.y1, det.x2, det.y2),
                    classId = det.classId,
                    className = det.className,
                    bestConfidence = det.confidence,
                    lastSeenFrame = frameIndex,
                    hitCount = 1
                )
            }
        }

        // Age out stale tracks
        tracks.filter { it.isActive && (frameIndex - it.lastSeenFrame) > maxAge }
            .forEach { it.isActive = false }

        return buildResult()
    }

    fun reset() {
        tracks.clear()
        nextId = 0
    }

    fun getFinalResult(): TrackingResult = buildResult()

    private fun buildResult(): TrackingResult {
        val active = tracks.filter { it.isActive }
        val valid = tracks.filter { it.hitCount >= minHits }
        val countByClass = valid.groupBy { it.className }.mapValues { it.value.size }
        return TrackingResult(
            activeTracks = active,
            allTracks = tracks.toList(),
            uniqueCountByClass = countByClass,
            totalUniqueCount = valid.size
        )
    }

    private fun iou(
        ax1: Float, ay1: Float, ax2: Float, ay2: Float,
        bx1: Float, by1: Float, bx2: Float, by2: Float
    ): Float {
        val ix1 = max(ax1, bx1); val iy1 = max(ay1, by1)
        val ix2 = min(ax2, bx2); val iy2 = min(ay2, by2)
        val intersection = max(0f, ix2 - ix1) * max(0f, iy2 - iy1)
        val areaA = (ax2 - ax1) * (ay2 - ay1)
        val areaB = (bx2 - bx1) * (by2 - by1)
        val union = areaA + areaB - intersection
        return if (union > 0f) intersection / union else 0f
    }
}
