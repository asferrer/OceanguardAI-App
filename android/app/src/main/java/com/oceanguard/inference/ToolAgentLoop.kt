package com.oceanguard.ai.inference

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolSet
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives a multi-turn conversation with manual tool dispatch.
 *
 * Used by [LiteRTTextEngine.generateWithTools]. Operates with
 * `automaticToolCalling = false` so we keep control over streaming, progress
 * UI, and a hard cap on iterations.
 *
 * Loop:
 *  1. Send user prompt → collect token deltas (text) and any tool calls.
 *  2. If no tool calls → done, return accumulated text.
 *  3. Else execute each tool call via [Conversation.toolManager], wrap results
 *     as [Content.ToolResponse], send back, increment round, repeat.
 *
 * Hard exits at [maxToolRounds] to cap latency on CPU-only inference.
 */
@OptIn(ExperimentalApi::class)
internal class ToolAgentLoop(
    private val conversation: Conversation,
    @Suppress("unused") private val toolSet: ToolSet,
) {

    companion object {
        private const val TAG = "ToolAgentLoop"
        private const val PARTIAL_THROTTLE_MS = 500L
        private const val DEFAULT_MAX_REDIRECTS = 3
        // When a dataBundle is provided, PHASE 2 happens on a fresh conversation,
        // so any prose the model emits in the tool-dispatch conversation is
        // wasted CPU. Cancel the current turn once the model starts writing
        // more than a handful of characters without tool calls.
        private const val PHASE1_PROSE_ABORT_CHARS = 64
    }

    private val gson = Gson()

    /**
     * Run the agent loop and return the final assistant text.
     *
     * @param prompt              First user-turn prompt.
     * @param maxToolRounds       Hard cap on tool dispatch rounds (≥1).
     * @param requiredToolNames   Tools the model MUST call before it is allowed to finish.
     *                            If the model tries to end the turn without emitting a tool
     *                            call AND still has missing entries in this set, a corrective
     *                            user message is injected and any premature draft text is
     *                            discarded. Pass an empty set to disable enforcement.
     * @param maxRedirects        Max number of corrective injections before giving up and
     *                            returning whatever text is currently accumulated.
     * @param onPartialResult     Streaming callback for accumulated TEXT only.
     * @param onToolCallStarted   Notified once per tool invocation, with name.
     */
    suspend fun run(
        prompt: String,
        maxToolRounds: Int,
        requiredToolNames: Set<String> = emptySet(),
        maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
        dataBundle: String? = null,
        onPartialResult: (String) -> Unit,
        onToolCallStarted: (String) -> Unit,
    ): String {
        val phase1Mode = dataBundle != null
        val accumulated = StringBuilder()
        val calledTools = mutableSetOf<String>()
        var nextInput: Any = prompt   // first turn: text; subsequent: Contents of ToolResponse or redirect String
        var round = 0
        var redirects = 0

        while (round <= maxToolRounds) {
            val toolCalls = sendTurn(nextInput, accumulated, onPartialResult, phase1Mode)

            if (toolCalls.isNotEmpty()) {
                if (round >= maxToolRounds) {
                    Log.w(TAG, "Hit maxToolRounds=$maxToolRounds; stopping with partial output")
                    return accumulated.toString()
                }
                val responses = executeAll(toolCalls, onToolCallStarted)
                toolCalls.forEach { calledTools.add(it.name) }
                nextInput = Contents.of(*responses.toTypedArray())
                round++
                continue
            }

            // Model produced no tool calls this turn. Decide: allow finish, or redirect
            // because required tools are still missing. Gemma 4 sometimes abbreviates
            // snake_case names (e.g. `get_temporal` instead of `get_temporal_trend`)
            // which the SDK dispatcher resolves via prefix; treat any called name
            // that is a prefix of a required name as satisfying that requirement.
            val missing = requiredToolNames.filterNot { req ->
                calledTools.any { called -> req == called || req.startsWith(called) || called.startsWith(req) }
            }.toSet()

            // PHASE-1 early exit: when a dataBundle was supplied and all required
            // tools have run, stop immediately. The LiteRT-LM Conversation keeps
            // the full KV cache across turns — letting the model write prose here
            // would accumulate a garbled draft (Gemma 4 E2B emits template
            // placeholders when transcribing raw tool-response JSON) and slow the
            // next turn by 10x. The caller is expected to invoke generateText()
            // on a FRESH conversation using the bundle as the prompt, which
            // collapses PHASE-2 to a simple "copy these tables" task.
            if (missing.isEmpty() && dataBundle != null) {
                Log.i(TAG, "PHASE-1 done (tools=$calledTools); exiting agent loop so caller can run PHASE-2 on a fresh conversation")
                return ""
            }
            if (missing.isEmpty() || redirects >= maxRedirects) {
                if (missing.isNotEmpty()) {
                    Log.w(TAG, "AgentLoop EXHAUSTED redirects=$redirects rounds=$round; STILL MISSING tools=$missing; called=$calledTools; chars=${accumulated.length}")
                } else {
                    Log.i(TAG, "AgentLoop summary: rounds=$round redirects=$redirects called=$calledTools chars=${accumulated.length}")
                }
                return accumulated.toString()
            }

            // Premature wrap-up: discard the draft (it was built on incomplete data) and
            // force the model back to PHASE 1 with an explicit list of the missing tools.
            Log.w(TAG, "Premature finish after round=$round; called=$calledTools; missing=$missing. Injecting redirect #${redirects + 1}.")
            if (accumulated.isNotEmpty()) {
                accumulated.setLength(0)
                onPartialResult("")
            }
            redirects++
            val missingList = missing.joinToString(", ")
            nextInput = "STOP. You have not yet invoked the required tools: $missingList. " +
                "Invoke each of them now (all of them, in a single turn is fine). " +
                "Do NOT write any report prose until every required tool has returned its data. " +
                "Your previous draft was discarded because it was based on incomplete information."
            round++
        }
        return accumulated.toString()
    }

    /**
     * Sends one turn to the conversation and returns any tool calls the model
     * emitted. Streams text deltas via [onPartialResult] (throttled).
     */
    private suspend fun sendTurn(
        input: Any,
        accumulated: StringBuilder,
        onPartialResult: (String) -> Unit,
        phase1Mode: Boolean = false,
    ): List<ToolCall> = suspendCancellableCoroutine { continuation ->
        val collectedToolCalls = mutableListOf<ToolCall>()
        var lastPartialMs = 0L
        var resumed = false

        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                if (resumed) return
                if (message.toolCalls.isNotEmpty()) {
                    collectedToolCalls.addAll(message.toolCalls)
                }
                val token = message.contents.toString()
                if (token.isNotEmpty()) {
                    accumulated.append(token)
                    // In PHASE 1 (tool-dispatch only; final writing happens on a
                    // fresh conversation), any prose past the abort threshold is
                    // wasted CPU. Resume early so the outer loop can move on.
                    if (phase1Mode && collectedToolCalls.isEmpty() && accumulated.length >= PHASE1_PROSE_ABORT_CHARS) {
                        Log.i(TAG, "PHASE-1 prose detected (${accumulated.length} chars); aborting turn to hand off to PHASE-2")
                        resumed = true
                        continuation.resume(emptyList())
                        return
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastPartialMs >= PARTIAL_THROTTLE_MS) {
                        onPartialResult(accumulated.toString())
                        lastPartialMs = now
                    }
                }
            }
            override fun onDone() {
                if (resumed) return
                resumed = true
                continuation.resume(collectedToolCalls.toList())
            }
            override fun onError(error: Throwable) {
                if (resumed) return
                resumed = true
                // Defensive: tool-call parser failures (LiteRtLmJniException) from Gemma 4
                // emitting slightly malformed FC syntax must NOT abort the report. Swallow
                // them unconditionally, log the offending payload, and end the turn — the
                // outer loop will exit cleanly and return whatever text was accumulated
                // (possibly empty on PHASE 1 failures, which callers surface as a retry).
                val msg = error.message.orEmpty()
                val isToolParseError = msg.contains("Failed to parse tool calls") ||
                    msg.contains("Failed to parse FC tool calls")
                if (isToolParseError) {
                    Log.w(TAG, "Tool-call parse error swallowed (accumulated=${accumulated.length} chars): $msg")
                    continuation.resume(emptyList())
                } else {
                    continuation.resumeWithException(error)
                }
            }
        }

        when (input) {
            is String -> conversation.sendMessageAsync(input, callback)
            is Contents -> conversation.sendMessageAsync(input, callback)
            else -> continuation.resumeWithException(
                IllegalArgumentException("Unsupported input type: ${input::class.simpleName}")
            )
        }
    }

    /**
     * Executes every tool call via the conversation's [ToolManager] and wraps
     * each result as a [Content.ToolResponse]. Errors become an "error" map so
     * the model can recover gracefully.
     */
    private fun executeAll(
        toolCalls: List<ToolCall>,
        onToolCallStarted: (String) -> Unit,
    ): List<Content.ToolResponse> = toolCalls.map { call ->
        onToolCallStarted(call.name)
        val response: Any = try {
            val argsJson: JsonObject = gson.toJsonTree(call.arguments).asJsonObject
            val result = conversation.toolManager.execute(call.name, argsJson)
            Log.d(TAG, "Tool '${call.name}' executed OK")
            result ?: mapOf("ok" to true)
        } catch (e: Exception) {
            Log.w(TAG, "Tool '${call.name}' failed: ${e.message}")
            mapOf("error" to (e.message ?: "Tool execution failed"))
        }
        Content.ToolResponse(call.name, response)
    }
}
