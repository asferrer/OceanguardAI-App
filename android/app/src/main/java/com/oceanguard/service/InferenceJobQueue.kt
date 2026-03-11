package com.oceanguard.ai.service

import android.net.Uri
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Job types that can be enqueued in the inference pipeline.
 *
 * Each job carries the data needed to start processing independently.
 * Jobs are processed sequentially — one at a time — by [InferenceService].
 */
sealed class InferenceJob {
    /** Unique ID for notification deep-linking and UI tracking. */
    val id: Long = nextId.getAndIncrement()

    data class SingleImage(val uri: Uri) : InferenceJob()
    data class ImageBatch(val uris: List<Uri>) : InferenceJob()
    data class Video(val uri: Uri) : InferenceJob()

    /** Human-readable type for notification text. */
    val typeLabel: String
        get() = when (this) {
            is SingleImage -> "image"
            is ImageBatch -> "batch"
            is Video -> "video"
        }

    /** Navigation route to open when the notification is clicked. */
    val deepLinkRoute: String
        get() = when (this) {
            is SingleImage -> "results"
            is ImageBatch -> "batch"
            is Video -> "video_results"
        }

    companion object {
        private val nextId = AtomicLong(1)
    }
}

/**
 * Thread-safe FIFO queue for inference jobs.
 *
 * The service enqueues new jobs via [enqueue] and processes them one at a time.
 * When the current job finishes, [dequeue] returns the next pending job.
 */
class InferenceJobQueue {
    private val queue = ConcurrentLinkedQueue<InferenceJob>()

    /** The job currently being processed (null if idle). */
    @Volatile
    var currentJob: InferenceJob? = null
        private set

    /** Add a job to the end of the queue. */
    fun enqueue(job: InferenceJob) {
        queue.add(job)
    }

    /** Remove and return the next job, marking it as current. Returns null if empty. */
    fun dequeue(): InferenceJob? {
        val next = queue.poll()
        currentJob = next
        return next
    }

    /** Number of jobs waiting (excludes current). */
    val pendingCount: Int get() = queue.size

    /** Total count including the current job. */
    val totalCount: Int get() = queue.size + if (currentJob != null) 1 else 0

    /** Clear the current job marker (called when a job completes or is cancelled). */
    fun clearCurrent() {
        currentJob = null
    }

    /** Drain all pending jobs and clear current. Returns the removed jobs. */
    fun cancelAll(): List<InferenceJob> {
        val removed = mutableListOf<InferenceJob>()
        while (true) {
            val job = queue.poll() ?: break
            removed.add(job)
        }
        currentJob = null
        return removed
    }

    /** Snapshot of all pending jobs (for UI display). Does not include current. */
    fun pendingJobs(): List<InferenceJob> = queue.toList()
}
