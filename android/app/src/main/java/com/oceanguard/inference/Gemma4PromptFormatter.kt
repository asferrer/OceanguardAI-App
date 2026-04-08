package com.oceanguard.ai.inference

/**
 * Formats prompts and sanitizes output for Gemma 4 models.
 *
 * Gemma 4 uses its own chat template:
 *   <start_of_turn>system\n{system}<end_of_turn>\n
 *   <start_of_turn>user\n{user}<end_of_turn>\n
 *   <start_of_turn>model\n
 *
 * The model generates until <end_of_turn> or EOS token.
 * Gemma 4 does NOT use `<think>` blocks — thinking is disabled.
 */
object Gemma4PromptFormatter : PromptFormatter {

    private const val START = "<start_of_turn>"
    private const val END   = "<end_of_turn>"

    private val ROLE_MARKER_RE  = Regex("^(system|user|model)\\n?", RegexOption.MULTILINE)
    private val CONTROL_CHAR_RE = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]")
    private val EXCESS_BOLD_RE  = Regex("\\*{4,}")

    override fun format(
        userMessage: String,
        systemMessage: String,
        assistantPrefill: String,
        thinkingEnabled: Boolean,
    ): String = buildString {
        append(START).append("system\n")
        append(systemMessage)
        append(END).append("\n")
        append(START).append("user\n")
        append(userMessage)
        append(END).append("\n")
        append(START).append("model\n")
        // Gemma 4 does not support <think> blocks — skip thinkingEnabled.
        if (assistantPrefill.isNotEmpty()) {
            append(assistantPrefill).append("\n")
        }
    }

    override fun sanitizePartial(raw: String): String {
        return raw
            .replace(START, "")
            .replace(END, "")
            .replace("<eos>", "")
            .replace("\uFFFD", "")
    }

    override fun sanitizeOutput(raw: String): String {
        return raw
            .replace(START, "")
            .replace(END, "")
            .replace("<eos>", "")
            .replace(ROLE_MARKER_RE, "")
            .replace(CONTROL_CHAR_RE, "")
            .replace("\uFFFD", "")
            .replace("\uFEFF", "")
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace(EXCESS_BOLD_RE, "***")
            .trim()
            .let { truncateRepetitionLoop(it) }
    }
}
