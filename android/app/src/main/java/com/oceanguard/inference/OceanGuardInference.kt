package com.oceanguard.ai.inference

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.oceanguard.ai.data.DebrisDetection
import com.oceanguard.ai.utils.DebrisJsonParser
import com.oceanguard.ai.utils.ImagePreprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * OceanGuardInference - Core VLM inference wrapper
 *
 * Uses MediaPipe LlmInference with the Session-based API for:
 * - Vision modality (native image input via addImage)
 * - LoRA adapter support (session-level configuration)
 * - Configurable sampling (topK, temperature per session)
 *
 * Architecture: Base Gemma 3n E2B + optional OceanGuard LoRA Adapter
 */
class OceanGuardInference(private val context: Context) {

    companion object {
        private const val TAG = "OceanGuardInference"

        // Model file names searched in order of priority
        // (app internal files dir, then external /sdcard/Download/models/)
        private val BASE_MODEL_FILENAMES = listOf(
            "gemma-3n-E2B-it-int4.task",       // Pre-converted from HuggingFace (.task)
            "gemma-3n-E2B-it-int4.litertlm",   // Pre-converted from HuggingFace (.litertlm)
            "oceanguard_base.bin",               // Custom fine-tuned (future)
            "oceanguard_base.task",              // Custom fine-tuned (.task format)
        )
        private const val LORA_ADAPTER_FILENAME = "oceanguard_adapter.bin"

        // Inference parameters — optimized for Exynos 2200
        private const val MAX_TOKENS = 2048 // Reports need ~1000 tokens; detection finishes early
        private const val TOP_K = 20
        private const val TEMPERATURE = 0.3f
        private const val TARGET_IMAGE_SIZE = 512

        // Compact detection prompt — fewer prefill tokens = faster TTFT
        private const val DETECTION_PROMPT = """Detect marine debris. Return ONLY JSON:
{"debris_detected":[{"bbox":[x,y,w,h],"material":"Plastic|Metal|Fabric|Rubber|Glass|Other","type":"Bottle|Can|Fishing_Net|Glove|Mask|Plastic_Debris|Metal_Debris|Tire","confidence":0.0}],"total_count":0,"image_quality":"good|fair|poor"}"""

        private const val COUNTING_PROMPT = """Count debris objects. Return ONLY JSON:
{"total_count":0,"by_material":{"Plastic":0,"Metal":0,"Fabric":0,"Other":0}}"""
    }

    // Task-level inference engine (loaded once)
    private var llmInference: LlmInference? = null

    // Active backend (GPU or CPU) — set after successful initialization
    private var activeBackend: String = "CPU"

    // Resolved paths
    private var resolvedLoraPath: String? = null

    @Volatile
    private var isInitialized = false

    private val imagePreprocessor = ImagePreprocessor(context)
    private val jsonParser = DebrisJsonParser()

    /**
     * Initialize the LLM inference engine (called once during app startup).
     *
     * Only sets model path and max tokens at the task level.
     * Session-level options (topK, temperature, LoRA, vision) are
     * configured per-query in [createVisionSession].
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) {
            Log.i(TAG, "Model already initialized ($activeBackend backend)")
            return@withContext
        }

        try {
            Log.i(TAG, "Initializing MediaPipe LLM Inference...")
            val startTime = System.currentTimeMillis()

            val baseModelPath = resolveModelPath(BASE_MODEL_FILENAMES)
                ?: throw ModelInitializationException(
                    "Base model not found. Place one of $BASE_MODEL_FILENAMES in " +
                    "${context.filesDir}/models/ or /sdcard/Download/models/"
                )

            resolvedLoraPath = resolveModelPath(LORA_ADAPTER_FILENAME)

            Log.i(TAG, "Base model: $baseModelPath")
            Log.i(TAG, "LoRA adapter: ${resolvedLoraPath ?: "Not found - using base model only"}")

            activeBackend = initWithBackendFallback(baseModelPath)

            isInitialized = true
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Initialization complete ($activeBackend backend) in ${elapsed}ms")

        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            isInitialized = false
            throw ModelInitializationException("Failed to initialize model: ${e.message}", e)
        }
    }

    /**
     * Try GPU backend first (faster prefill), fallback to CPU if it fails.
     * Xclipse 920 (AMD RDNA2) is atypical — GPU may not work.
     */
    private fun initWithBackendFallback(modelPath: String): String {
        if (isGPUAvailable()) {
            try {
                Log.i(TAG, "Attempting GPU backend...")
                llmInference = LlmInference.createFromOptions(
                    context, buildInferenceOptions(modelPath, LlmInference.Backend.GPU),
                )
                Log.i(TAG, "GPU backend initialized successfully")
                return "GPU"
            } catch (e: Exception) {
                Log.w(TAG, "GPU backend failed, falling back to CPU: ${e.message}")
                llmInference = null
            }
        }
        llmInference = LlmInference.createFromOptions(
            context, buildInferenceOptions(modelPath, LlmInference.Backend.CPU),
        )
        return "CPU"
    }

    private fun buildInferenceOptions(
        modelPath: String,
        backend: LlmInference.Backend,
    ): LlmInference.LlmInferenceOptions =
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(MAX_TOKENS)
            .setMaxNumImages(1)
            .setPreferredBackend(backend)
            .build()

    /**
     * Create a vision-enabled session with sampling and optional LoRA.
     *
     * Each detection call gets its own session so vision images don't
     * leak between queries.
     */
    private fun createVisionSession(): LlmInferenceSession {
        val inference = llmInference
            ?: throw IllegalStateException("Model not initialized. Call initialize() first.")

        val sessionBuilder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(TOP_K)
            .setTemperature(TEMPERATURE)
            .setGraphOptions(
                GraphOptions.builder()
                    .setEnableVisionModality(true)
                    .build()
            )

        resolvedLoraPath?.let { path ->
            sessionBuilder.setLoraPath(path)
            Log.d(TAG, "Session configured with LoRA adapter")
        }

        return LlmInferenceSession.createFromOptions(inference, sessionBuilder.build())
    }

    /**
     * Detect marine debris in an image using vision-native inference.
     */
    suspend fun detectDebris(imageUri: Uri): DebrisDetection = withContext(Dispatchers.Default) {
        checkInitialized()

        try {
            Log.i(TAG, "Starting debris detection...")
            val startTime = System.currentTimeMillis()

            val bitmap = imagePreprocessor.loadAndPreprocess(imageUri, TARGET_IMAGE_SIZE)
            Log.i(TAG, "Image preprocessed: ${bitmap.width}x${bitmap.height}")

            val response = runVisionInference(bitmap, DETECTION_PROMPT)
            val detection = jsonParser.parseDetection(response)

            val totalTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "Detection complete in ${totalTime}ms - Found ${detection.debrisList.size} objects")

            detection

        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            throw InferenceException("Debris detection failed: ${e.message}", e)
        }
    }

    /**
     * Count debris objects in an image (faster than full detection).
     */
    suspend fun countDebris(imageUri: Uri): Map<String, Any> = withContext(Dispatchers.Default) {
        checkInitialized()

        try {
            Log.i(TAG, "Counting debris...")

            val bitmap = imagePreprocessor.loadAndPreprocess(imageUri, TARGET_IMAGE_SIZE)
            val response = runVisionInference(bitmap, COUNTING_PROMPT)
            val counts = jsonParser.parseCounts(response)

            Log.i(TAG, "Count complete: ${counts["total_count"]} objects")
            counts

        } catch (e: Exception) {
            Log.e(TAG, "Counting failed", e)
            throw InferenceException("Debris counting failed: ${e.message}", e)
        }
    }

    /**
     * Run vision inference using MediaPipe's native multimodal API.
     *
     * Creates a per-query session, passes the image via addImage()
     * (no base64 encoding needed), and returns the model response.
     */
    private fun runVisionInference(bitmap: Bitmap, prompt: String): String {
        val session = createVisionSession()
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            session.addQueryChunk(prompt)
            session.addImage(mpImage)
            session.generateResponse()
                ?: throw InferenceException("Empty response from model")
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during inference, retrying with smaller image", e)
            session.close()

            val smallerBitmap = imagePreprocessor.resize(bitmap, 256)
            val retrySession = createVisionSession()
            try {
                val mpImage = BitmapImageBuilder(smallerBitmap).build()
                retrySession.addQueryChunk(prompt)
                retrySession.addImage(mpImage)
                retrySession.generateResponse()
                    ?: throw InferenceException("Empty response from model (retry)")
            } finally {
                retrySession.close()
            }
        } finally {
            try { session.close() } catch (_: Exception) {}
        }
    }

    /**
     * Generate a text-only response (no image input).
     *
     * Creates a session WITHOUT vision modality for text-only inference.
     * Used by [ReportGenerator] for AI-assisted environmental reports.
     *
     * @param prompt The text prompt to send to the model
     * @return The model's text response
     */
    suspend fun generateTextResponse(prompt: String): String = withContext(Dispatchers.Default) {
        checkInitialized()

        val inference = llmInference
            ?: throw IllegalStateException("Model not initialized")

        val sessionBuilder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(TOP_K)
            .setTemperature(TEMPERATURE)

        resolvedLoraPath?.let { path ->
            sessionBuilder.setLoraPath(path)
        }

        val sessionStart = System.currentTimeMillis()
        val session = LlmInferenceSession.createFromOptions(inference, sessionBuilder.build())
        Log.d(TAG, "Text session created in ${System.currentTimeMillis() - sessionStart}ms")
        try {
            session.addQueryChunk(prompt)
            val inferenceStart = System.currentTimeMillis()
            val response = session.generateResponse()
                ?: throw InferenceException("Empty response from model")
            val inferenceTime = System.currentTimeMillis() - inferenceStart
            Log.i(TAG, "Text inference: ${response.length} chars in ${inferenceTime}ms (~${response.length * 1000 / maxOf(inferenceTime, 1)} chars/s)")
            response
        } finally {
            session.close()
        }
    }

    /**
     * Run a short text-only inference to warm up the native pipeline.
     * Call once after [initialize] to exercise JIT, allocate buffers, and
     * prime the MediaPipe inference graph.
     */
    suspend fun warmUp() = withContext(Dispatchers.Default) {
        if (!isInitialized) return@withContext
        val inference = llmInference ?: return@withContext

        val session = LlmInferenceSession.createFromOptions(
            inference,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(1)
                .setTemperature(0f)
                .build()
        )
        try {
            val startTime = System.currentTimeMillis()
            session.addQueryChunk("Say OK")
            session.generateResponse()
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "VLM warm-up complete in ${elapsed}ms")
        } catch (e: Exception) {
            Log.w(TAG, "VLM warm-up failed (non-fatal)", e)
        } finally {
            session.close()
        }
    }

    /**
     * Check whether the model is initialized and ready for inference.
     */
    fun isModelReady(): Boolean = isInitialized && llmInference != null

    /**
     * Fast filesystem check: is any recognised VLM model file present?
     * Does NOT load the model — use [initialize] for that.
     */
    fun isModelFileAvailable(): Boolean {
        return resolveModelPath(BASE_MODEL_FILENAMES) != null
    }

    /**
     * Clear GPU cache (called during memory pressure).
     */
    fun clearCache() {
        Log.i(TAG, "Clearing inference cache")
        System.gc()
    }

    /**
     * Release all resources.
     */
    fun release() {
        Log.i(TAG, "Releasing model resources")
        try {
            llmInference?.close()
            llmInference = null
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }

    private fun checkInitialized() {
        if (!isInitialized || llmInference == null) {
            throw IllegalStateException("Model not initialized. Call initialize() first.")
        }
    }

    /**
     * Resolve a model filename to an absolute path.
     *
     * Search order per filename:
     * 1. App-private external files: {externalFilesDir}/models/{filename}
     * 2. App internal files: {filesDir}/models/{filename}
     * 3. Download/models/{filename} via MediaStore (Scoped Storage safe)
     *    → auto-copy to app-private dir
     *
     * Returns null if the file is not found in any location.
     */
    private fun resolveModelPath(filenames: List<String>): String? {
        val appModelsDir = File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

        for (filename in filenames) {
            // 1. App-private external storage (native code has full access)
            val appPrivatePath = File(appModelsDir, filename)
            if (appPrivatePath.exists()) {
                Log.i(TAG, "Found model in app-private storage: ${appPrivatePath.absolutePath}")
                return appPrivatePath.absolutePath
            }

            // 2. App internal storage
            val internalPath = File(context.filesDir, "models/$filename")
            if (internalPath.exists()) {
                Log.i(TAG, "Found model in internal storage: ${internalPath.absolutePath}")
                return internalPath.absolutePath
            }

            // 3. Download/models/ via MediaStore → copy to app-private dir
            val copied = copyModelFromDownloads(filename, appPrivatePath)
            if (copied) return appPrivatePath.absolutePath
        }

        Log.w(TAG, "Model not found with any filename: $filenames")
        return null
    }

    /**
     * Copy a model file from Download/models/ to app-private storage
     * using MediaStore (Scoped Storage compatible on Android 10+).
     */
    private fun copyModelFromDownloads(filename: String, destination: File): Boolean {
        val relativePath = "Download/models/$filename"

        // Try MediaStore query for Android 10+ (Scoped Storage)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf(filename, "%models%")

            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val id = cursor.getLong(idColumn)
                    val contentUri = android.content.ContentUris.withAppendedId(collection, id)

                    Log.i(TAG, "Found model via MediaStore (id=$id), copying to app-private storage...")
                    try {
                        context.contentResolver.openInputStream(contentUri)?.use { input ->
                            destination.outputStream().use { output ->
                                input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                            }
                        }
                        Log.i(TAG, "Model copied to: ${destination.absolutePath}")
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to copy model via MediaStore", e)
                        destination.delete()
                    }
                }
            }
        }

        // Fallback: direct File access (pre-Android 10 or MANAGE_EXTERNAL_STORAGE)
        val downloadPath = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "models/$filename")
        if (downloadPath.exists() && downloadPath.canRead()) {
            Log.i(TAG, "Found model via direct file access, copying to app-private storage...")
            try {
                downloadPath.inputStream().use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                    }
                }
                Log.i(TAG, "Model copied to: ${destination.absolutePath}")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy model from Download", e)
                destination.delete()
            }
        }

        return false
    }

    /**
     * Resolve a single model filename to an absolute path.
     */
    private fun resolveModelPath(filename: String): String? {
        return resolveModelPath(listOf(filename))
    }

    fun isGPUAvailable(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val configInfo = activityManager.deviceConfigurationInfo
        val supportsES32 = configInfo.reqGlEsVersion >= 0x00030002
        Log.i(TAG, "GPU check: OpenGL ES ${configInfo.glEsVersion}, supports ES 3.2: $supportsES32")
        return supportsES32
    }

    fun getModelInfo(): Map<String, Any> {
        return mapOf(
            "initialized" to isInitialized,
            "active_backend" to activeBackend,
            "gpu_available" to isGPUAvailable(),
            "base_model_candidates" to BASE_MODEL_FILENAMES,
            "lora_adapter" to (resolvedLoraPath ?: "not found"),
            "target_image_size" to TARGET_IMAGE_SIZE,
            "max_tokens" to MAX_TOKENS,
            "temperature" to TEMPERATURE,
            "top_k" to TOP_K,
        )
    }
}

class ModelInitializationException(message: String, cause: Throwable? = null) : Exception(message, cause)
class InferenceException(message: String, cause: Throwable? = null) : Exception(message, cause)
