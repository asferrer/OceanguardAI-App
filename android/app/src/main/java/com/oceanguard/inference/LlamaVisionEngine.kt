package com.oceanguard.ai.inference

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [VlmVisionEngine] implementation backed by llama.cpp + llava (via [LlamaCppBridge]).
 *
 * Supports multiple model families via [formatter] parameter:
 * - Qwen3.5-2B Q4_K_M + mmproj FP16 (default)
 * - Gemma 4 E2B Q4_K_M + mmproj FP16
 *
 * Two handles are maintained: one for the LLM, one for the CLIP projector.
 * Both must be loaded before [generateWithImage] can be called.
 */
class LlamaVisionEngine(
    private val formatter: PromptFormatter = QwenPromptFormatter,
    displayLabel: String = "Qwen3.5-2B Q4_K_M + mmproj",
) : VlmVisionEngine {

    companion object {
        private const val TAG = "LlamaVisionEngine"

        private const val N_CTX = 8192   // raised from 4096 — new prompts reach 3700-5000 tok
        private const val N_THREADS = 4
        private const val N_THREADS_BATCH = 6
        private const val TEMPERATURE = 0.3f
        private const val TOP_K = 20

        // Qwen vision filenames
        const val QWEN_MODEL_FILENAME  = "Qwen3.5-2B-Q4_K_M.gguf"
        const val QWEN_MMPROJ_FILENAME = "mmproj-F16.gguf"

        @Deprecated("Use QWEN_MODEL_FILENAME", replaceWith = ReplaceWith("QWEN_MODEL_FILENAME"))
        const val MODEL_FILENAME  = QWEN_MODEL_FILENAME
        @Deprecated("Use QWEN_MMPROJ_FILENAME", replaceWith = ReplaceWith("QWEN_MMPROJ_FILENAME"))
        const val MMPROJ_FILENAME = QWEN_MMPROJ_FILENAME
    }

    override val displayName: String = displayLabel

    private val initMutex = Mutex()

    @Volatile private var llmHandle: Long = 0L
    @Volatile private var clipHandle: Long = 0L

    override fun isReady(): Boolean = llmHandle != 0L && clipHandle != 0L

    /** Not supported — vision engine always requires mmproj. */
    override suspend fun initialize(modelPath: String): Unit =
        throw UnsupportedOperationException(
            "LlamaVisionEngine requires mmprojPath. Use initialize(modelPath, mmprojPath)."
        )

    override suspend fun initialize(modelPath: String, mmprojPath: String) =
        withContext(Dispatchers.IO) {
            initMutex.withLock {
                if (isReady()) {
                    Log.d(TAG, "Already initialized, skipping")
                    return@withLock
                }

                // Vision requires native compilation with mtmd support.
                // Stub build (LLAVA_AVAILABLE=false) returns false — skip gracefully.
                if (!LlamaCppBridge.nativeIsVisionSupported()) {
                    Log.w(TAG, "Vision not compiled into this build — skipping initialization")
                    return@withLock
                }

                Log.i(TAG, "Loading LLM: $modelPath")
                val llm = LlamaCppBridge.nativeInitTextModel(modelPath, N_CTX, N_THREADS, N_THREADS_BATCH)
                if (llm == 0L) throw IllegalStateException("Failed to load 2B model: $modelPath")
                llmHandle = llm

                Log.i(TAG, "Loading CLIP: $mmprojPath")
                val clip = LlamaCppBridge.nativeInitClipModel(llm, mmprojPath, N_THREADS)
                if (clip == 0L) {
                    LlamaCppBridge.nativeReleaseModel(llm)
                    llmHandle = 0L
                    throw IllegalStateException("Failed to load mmproj: $mmprojPath")
                }
                clipHandle = clip
                Log.i(TAG, "Vision model ready (llm=$llm, clip=$clip)")
            }
        }

    override suspend fun warmUp() = withContext(Dispatchers.IO) {
        val handle = llmHandle
        if (handle == 0L) return@withContext
        try {
            LlamaCppBridge.nativeWarmUp(handle)
            Log.d(TAG, "Warm-up complete")
        } catch (e: Exception) {
            Log.w(TAG, "Warm-up failed (non-fatal): ${e.message}")
        }
    }

    override fun release() {
        val llm  = llmHandle
        val clip = clipHandle
        llmHandle  = 0L
        clipHandle = 0L
        if (clip != 0L) LlamaCppBridge.nativeReleaseClipModel(clip)
        if (llm  != 0L) LlamaCppBridge.nativeReleaseModel(llm)
        Log.i(TAG, "Vision model released")
    }

    override suspend fun generateText(
        prompt: String,
        maxTokens: Int,
        systemMessage: String?,
        assistantPrefill: String?,
        thinkingEnabled: Boolean,
        onPartialResult: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val handle = llmHandle
        check(handle != 0L) { "Vision model not loaded." }
        val prefill = assistantPrefill?.takeIf { it.isNotEmpty() }?.let { "$it\n" } ?: ""
        val formatted = formatter.format(
            userMessage      = prompt,
            systemMessage    = systemMessage ?: PromptFormatter.DEFAULT_SYSTEM_PROMPT,
            assistantPrefill = assistantPrefill ?: "",
            thinkingEnabled  = thinkingEnabled,
        )
        val estimatedPromptTokens = formatted.length / 3
        val safeMaxTokens = (N_CTX - estimatedPromptTokens - 256)
            .coerceAtLeast(256)
            .coerceAtMost(maxTokens)
        Log.d(TAG, "generateText prompt=${formatted.length}ch (~${estimatedPromptTokens}tok) maxTokens=$maxTokens→$safeMaxTokens")

        val accumulated = StringBuilder(prefill)
        var lastPartialMs = 0L
        val callback = object : TokenStreamCallback {
            override fun onToken(piece: String) {
                accumulated.append(piece)
                val now = System.currentTimeMillis()
                if (now - lastPartialMs >= 200L) {
                    onPartialResult(formatter.sanitizePartial(accumulated.toString()))
                    lastPartialMs = now
                }
            }
        }
        val raw = LlamaCppBridge.nativeGenerateText(
            handle, formatted, safeMaxTokens, TEMPERATURE, TOP_K, callback
        )
        prefill + formatter.sanitizeOutput(raw)
    }

    override suspend fun generateWithImage(
        bitmap: Bitmap,
        prompt: String,
        maxTokens: Int,
        systemMessage: String?,
        assistantPrefill: String?,
        onPartialResult: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        check(isReady()) { "Vision model not loaded. Call initialize(modelPath, mmprojPath) first." }

        // Convert Bitmap to ARGB_8888 pixel array for JNI.
        // Capture dimensions before recycle to avoid use-after-free in the log below.
        val argbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val imgW = argbBitmap.width
        val imgH = argbBitmap.height
        val pixels = IntArray(imgW * imgH)
        argbBitmap.getPixels(pixels, 0, imgW, 0, 0, imgW, imgH)
        if (argbBitmap !== bitmap) argbBitmap.recycle()

        val prefill = assistantPrefill?.takeIf { it.isNotEmpty() }?.let { "$it\n" } ?: ""
        val formatted = formatter.format(
            userMessage      = prompt,
            systemMessage    = systemMessage ?: PromptFormatter.DEFAULT_SYSTEM_PROMPT,
            assistantPrefill = assistantPrefill ?: "",
            thinkingEnabled  = false,  // Vision mode: no thinking
        )
        Log.d(TAG, "Vision generation (${imgW}x${imgH}, maxTokens=$maxTokens, prefill=${prefill.length}b)")

        val accumulated = StringBuilder(prefill)
        var lastPartialMs = 0L
        val callback = object : TokenStreamCallback {
            override fun onToken(piece: String) {
                accumulated.append(piece)
                val now = System.currentTimeMillis()
                if (now - lastPartialMs >= 200L) {
                    onPartialResult(formatter.sanitizePartial(accumulated.toString()))
                    lastPartialMs = now
                }
            }
        }
        val raw = LlamaCppBridge.nativeGenerateWithImage(
            llmHandle, clipHandle,
            pixels, imgW, imgH,
            formatted, maxTokens, TEMPERATURE, callback,
        )
        prefill + formatter.sanitizeOutput(raw)
    }
}
