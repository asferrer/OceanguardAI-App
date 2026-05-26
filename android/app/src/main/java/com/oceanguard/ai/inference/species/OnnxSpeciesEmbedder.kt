package com.oceanguard.ai.inference.species

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * ONNX Runtime Mobile implementation of [SpeciesEmbedder].
 *
 * Runs an exported OpenCLIP ViT-B/32 (or MobileCLIP-S2) ONNX model via
 * ONNX Runtime for Android. Uses the NNAPI Execution Provider if available,
 * falling back to CPU (XNNPACK). GpuDelegate is NOT used — same reasoning as
 * [com.oceanguard.ai.inference.RTDETRInference]: Exynos Xclipse 920 raises a
 * NoClassDefFoundError for GpuDelegateFactory$Options.
 *
 * ## Preprocessing (CLIP canonical):
 *   1. Resize + center-crop to 224×224.
 *   2. Normalize: mean=[0.481, 0.457, 0.408], std=[0.268, 0.261, 0.275]
 *      (OpenCLIP LAION-2B statistics).
 *   3. Layout: NCHW float32 [1, 3, 224, 224].
 *
 * ## Postprocessing:
 *   1. Extract output tensor (shape [1, 512]).
 *   2. L2-normalise the 512-dim vector before returning.
 *
 * @param modelFile  Path to the downloaded `.onnx` model file.
 */
class OnnxSpeciesEmbedder(private val modelFile: File) : SpeciesEmbedder {

    companion object {
        private const val INPUT_SIZE = 224
        private const val INPUT_NAME = "pixel_values"   // matches the exported ONNX model
        private const val OUTPUT_NAME = "image_features"

        // CLIP LAION-2B normalization constants
        private val CLIP_MEAN = floatArrayOf(0.48145466f, 0.4578275f,  0.40821073f)
        private val CLIP_STD  = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    @Volatile
    override var isReady: Boolean = false
        private set

    /**
     * Load the ONNX model and initialise the ORT session.
     * Must be called from a background dispatcher before the first [embed] call.
     *
     * @throws IllegalStateException if [modelFile] does not exist.
     * @throws ai.onnxruntime.OrtException on ONNX Runtime errors.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        check(modelFile.exists()) {
            "Species encoder not found at ${modelFile.absolutePath}. Download it first."
        }
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            addNnapi()           // NNAPI EP — falls back to CPU if unavailable
            setIntraOpNumThreads(4)
        }
        val session = env.createSession(modelFile.absolutePath, opts)
        ortEnv = env
        ortSession = session
        isReady = true
    }

    override suspend fun embed(bitmap: Bitmap): FloatArray = withContext(Dispatchers.IO) {
        check(isReady) { "OnnxSpeciesEmbedder not initialized. Call initialize() first." }
        val session = ortSession ?: error("ORT session is null")
        val env = ortEnv ?: error("ORT environment is null")

        val preprocessed = preprocess(bitmap)
        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(preprocessed),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        )

        inputTensor.use { tensor ->
            val results = session.run(mapOf(INPUT_NAME to tensor))
            results.use { output ->
                val rawFeatures = (output[OUTPUT_NAME].get().value as Array<*>)[0] as FloatArray
                l2Normalize(rawFeatures)
            }
        }
    }

    override fun close() {
        ortSession?.close()
        ortEnv?.close()
        isReady = false
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /** Center-crop + resize to [INPUT_SIZE], convert to NCHW float32, apply CLIP normalization. */
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val size = minOf(bitmap.width, bitmap.height)
        val xOff = (bitmap.width - size) / 2
        val yOff = (bitmap.height - size) / 2
        val cropped = Bitmap.createBitmap(bitmap, xOff, yOff, size, size)
        val scaled = Bitmap.createScaledBitmap(cropped, INPUT_SIZE, INPUT_SIZE, true)

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val tensor = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8)  and 0xFF) / 255f
            val b = ( pixel         and 0xFF) / 255f
            tensor[i]                              = (r - CLIP_MEAN[0]) / CLIP_STD[0]
            tensor[INPUT_SIZE * INPUT_SIZE + i]    = (g - CLIP_MEAN[1]) / CLIP_STD[1]
            tensor[2 * INPUT_SIZE * INPUT_SIZE + i]= (b - CLIP_MEAN[2]) / CLIP_STD[2]
        }

        if (cropped != bitmap) cropped.recycle()
        if (scaled != cropped) scaled.recycle()
        return tensor
    }

    /** In-place L2-normalisation. Returns [v] for chaining. */
    private fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 1e-8f) for (i in v.indices) v[i] /= norm
        return v
    }
}
