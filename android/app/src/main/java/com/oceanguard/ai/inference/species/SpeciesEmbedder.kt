package com.oceanguard.ai.inference.species

import android.graphics.Bitmap

/**
 * Interface for on-device image embedding used in the BioDex RAG pipeline.
 *
 * Implementations convert a [Bitmap] (any size) into an L2-normalised
 * float32 embedding vector of dimension [EMBEDDING_DIM] (512).
 *
 * The contract requires L2 normalisation so that cosine similarity equals
 * the dot product — the brute-force search in [SpeciesReferenceIndex]
 * relies on this.
 *
 * All implementations must:
 *  - Resize/center-crop internally before feeding the model.
 *  - L2-normalise the output before returning.
 *  - Be safe to call concurrently from a single coroutine on Dispatchers.IO
 *    (not required to be thread-safe for multi-coroutine access).
 *  - Throw [IllegalStateException] if called before [isReady] is true.
 */
interface SpeciesEmbedder {
    companion object {
        /** Fixed embedding dimension. All implementations must output this size. */
        const val EMBEDDING_DIM = 512
    }

    /** True once the model is loaded and ready for inference. */
    val isReady: Boolean

    /**
     * Embed [bitmap] and return a L2-normalised float32 vector of length [EMBEDDING_DIM].
     *
     * @throws IllegalStateException if the embedder is not ready.
     */
    suspend fun embed(bitmap: Bitmap): FloatArray

    /** Release native resources. After calling this [isReady] returns false. */
    fun close()
}
