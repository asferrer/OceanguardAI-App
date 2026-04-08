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
            .let { truncateRepetitionLoop(it) }
    }
}

/**
 * Detects and truncates repetition loops in LLM output.
 *
 * Small models (0.8B–2B) degenerate in two ways:
 * 1. Repeating the same paragraph until maxTokens (caught by paragraph dedup)
 * 2. Generating infinite empty/identical table rows (caught by line-run detection)
 *
 * Both are truncated at the point where the loop starts.
 */
fun truncateRepetitionLoop(text: String): String {
    // --- Pass 1: Collapse runs of identical consecutive lines (e.g. empty table rows) ---
    val cleaned = collapseRepeatedLines(text)

    // --- Pass 2: Paragraph-level dedup (catches repeated paragraphs) ---
    val paragraphs = cleaned.split(Regex("""\n{2,}""")).map { it.trim() }.filter { it.length >= 40 }
    if (paragraphs.size < 3) return cleaned

    val seen = mutableMapOf<String, Int>()
    for ((index, para) in paragraphs.withIndex()) {
        val normalized = para.lowercase().replace(Regex("""\s+"""), " ")
        val prevIndex = seen[normalized]
        if (prevIndex != null) {
            val cutPoint = findParagraphStart(cleaned, index, paragraphs)
            if (cutPoint > 0) {
                val truncated = cleaned.substring(0, cutPoint).trimEnd()
                android.util.Log.w("RepetitionLoop",
                    "Truncated paragraph at $index (${cleaned.length}->${truncated.length} chars)")
                return truncated
            }
        }
        seen[normalized] = index
    }
    return cleaned
}

/**
 * Detects runs of ≥3 identical consecutive lines and keeps only the first 2.
 * Handles the "infinite empty table row" degeneration pattern where the model
 * outputs `| | | | | | |` hundreds of times consuming the entire token budget.
 */
private fun collapseRepeatedLines(text: String): String {
    val lines = text.split("\n")
    if (lines.size < 5) return text

    val result = StringBuilder()
    var prevNorm = ""
    var runCount = 0
    val maxConsecutive = 2  // keep at most 2 identical lines in a row

    for (line in lines) {
        val norm = line.trim().lowercase()
        if (norm == prevNorm && norm.isNotEmpty()) {
            runCount++
            if (runCount <= maxConsecutive) {
                result.append(line).append("\n")
            }
            // else: skip — degenerate repetition
        } else {
            if (runCount > maxConsecutive) {
                android.util.Log.w("RepetitionLoop",
                    "Collapsed ${runCount - maxConsecutive} repeated lines")
            }
            runCount = 1
            prevNorm = norm
            result.append(line).append("\n")
        }
    }
    if (runCount > maxConsecutive) {
        android.util.Log.w("RepetitionLoop",
            "Collapsed ${runCount - maxConsecutive} repeated lines (end)")
    }

    return result.toString().trimEnd()
}

/** Finds the char index where the Nth paragraph starts in the original text. */
private fun findParagraphStart(text: String, paragraphIndex: Int, paragraphs: List<String>): Int {
    var searchFrom = 0
    for (i in 0 until paragraphIndex) {
        val idx = text.indexOf(paragraphs[i], searchFrom)
        if (idx < 0) return -1
        searchFrom = idx + paragraphs[i].length
    }
    return searchFrom
}
