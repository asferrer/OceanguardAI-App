package com.oceanguard.ai.inference

/**
 * Chat-template abstraction. Each LLM family has its own formatting conventions.
 * Implement this to add a new model without touching [LlamaTextEngine].
 */
interface PromptFormatter {
    /** Wraps user+system messages in the model's chat template. */
    fun format(
        userMessage: String,
        systemMessage: String,
        assistantPrefill: String,
        thinkingEnabled: Boolean,
    ): String

    /** Remove model-specific control tokens and artifacts from final output. */
    fun sanitizeOutput(raw: String): String

    /** Lightweight partial sanitizer for streaming — no heavy regex. */
    fun sanitizePartial(raw: String): String
}

/**
 * Formats prompts and sanitizes output for Qwen3.5 models.
 *
 * Qwen3.5 uses the ChatML template:
 *   <|im_start|>system\n{system}<|im_end|>\n
 *   <|im_start|>user\n{user}<|im_end|>\n
 *   <|im_start|>assistant\n
 *
 * The model generates until <|im_end|> or EOS token.
 */
object QwenPromptFormatter : PromptFormatter {

    private const val IM_START = "<|im_start|>"
    private const val IM_END   = "<|im_end|>"

    // Pre-compiled regex patterns — Regex compilation is expensive; cache at object level.
    private val THINK_BLOCK_RE  = Regex("<think>[\\s\\S]*?</think>\\n?")
    private val ROLE_MARKER_RE  = Regex("^(system|user|assistant)\\n?", RegexOption.MULTILINE)
    private val CONTROL_CHAR_RE = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]")
    private val EXCESS_BOLD_RE  = Regex("\\*{4,}")

    const val DEFAULT_SYSTEM_PROMPT =
        "You are a marine conservation scientist. " +
        "Generate environmental assessment reports using ONLY the provided data. " +
        "GROUNDING: never mention species, debris types, or percentages not present in the input. " +
        "Format: ## headings, bullet points, markdown tables with | separators. " +
        "Never invent or extrapolate data not in the input."

    /**
     * Wraps a user message in the ChatML template ready for inference.
     *
     * @param userMessage      Full user message (prompt + JSON data).
     * @param systemMessage    Optional system prompt override.
     * @param assistantPrefill Text to inject at the start of the assistant turn.
     *                         The native code generates AFTER this prefix; callers
     *                         must prepend it to the final result themselves.
     * @param thinkingEnabled  When true (default), Qwen3.5 reasoning mode is active —
     *                         the model emits a <think> block before the final answer.
     *                         Set false for quick responses (maxTokens ≤ 200) to avoid
     *                         spending the token budget on internal reasoning.
     */
    override fun format(
        userMessage: String,
        systemMessage: String,
        assistantPrefill: String,
        thinkingEnabled: Boolean,
    ): String = buildString {
        append(IM_START).append("system\n")
        append(systemMessage)
        append(IM_END).append("\n")
        append(IM_START).append("user\n")
        append(userMessage)
        append(IM_END).append("\n")
        append(IM_START).append("assistant\n")
        if (!thinkingEnabled) {
            // Empty <think> block signals Qwen3/3.5 to skip internal reasoning.
            append("<think>\n\n</think>\n")
        }
        if (assistantPrefill.isNotEmpty()) {
            append(assistantPrefill).append("\n")
        }
    }

    /**
     * Lightweight sanitizer for streaming callbacks — strips ChatML tokens and
     * hides the internal reasoning block while thinking is still in progress.
     *
     * During thinking mode the model first emits `<think>...reasoning...</think>`
     * before the actual report. Returns an empty string while the block is open so
     * the UI shows "Thinking..." instead of raw reasoning text. Once `</think>` is
     * seen, returns only the text that follows it.
     */
    override fun sanitizePartial(raw: String): String {
        // Thinking in progress — block still open
        if (raw.contains("<think>") && !raw.contains("</think>")) return ""
        // Thinking done — show only what comes after the closing tag
        val afterThink = if (raw.contains("</think>")) {
            raw.substringAfter("</think>").trimStart()
        } else raw
        return afterThink
            .replace(IM_START, "")
            .replace(IM_END, "")
            .replace("<|endoftext|>", "")
            .replace("\uFFFD", "")
    }

    /**
     * Strips Qwen special tokens and control characters from generated text.
     * Must be applied to the raw model output before displaying or saving.
     */
    override fun sanitizeOutput(raw: String): String {
        return raw
            // Strip Qwen3/3.5 thinking blocks (defensive: should not appear with pre-filled <think>)
            .replace(THINK_BLOCK_RE, "")
            // Remove ChatML tokens
            .replace(IM_START, "")
            .replace(IM_END, "")
            .replace("<|endoftext|>", "")
            // Remove role markers that may appear in output
            .replace(ROLE_MARKER_RE, "")
            // Strip C0 control chars (keep \t=0x09, \n=0x0A, \r=0x0D)
            .replace(CONTROL_CHAR_RE, "")
            // Remove Unicode replacement char, BOM, zero-width spaces
            .replace("\uFFFD", "")
            .replace("\uFEFF", "")
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            // Collapse excessive markdown repeats (e.g. ****text**** → **text**)
            .replace(EXCESS_BOLD_RE, "***")
            .trim()
    }
}
