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
        // Agentic mode: the user is reading the streamed text in real time, so
        // discarding the draft to redirect for missing tools causes a visible
        // "report restart". Once the model has clearly committed to writing the
        // report (substantial body or a markdown heading), accept whatever it
        // produced instead of restarting.
        private const val AGENTIC_KEEP_DRAFT_CHARS = 300
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
        /**
         * Subset of [requiredToolNames] whose absence MUST trigger a redirect
         * even when the model has already committed prose to the user-visible
         * stream. The agentic escape hatch is only allowed when every missing
         * tool is OUTSIDE this set. Default: every required tool is critical —
         * callers must opt-in to softening a specific tool by passing a smaller
         * set here.
         */
        criticalToolNames: Set<String> = requiredToolNames,
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

        // Snapshot of critical tools still missing — recomputed on each turn
        // and passed into sendTurn so the prose stream can be aborted as soon
        // as it crosses [AGENTIC_EARLY_PROSE_ABORT_CHARS] without all critical
        // tools having returned. Without this, Gemma 4 E2B's 6000-9000 char
        // wrap-up steals ~10 min of CPU on Exynos before the outer-loop check
        // fires, doubling latency on every missed-tool round.
        fun currentCriticalMissing(): Set<String> =
            criticalToolNames.filterNot { req ->
                calledTools.any { called -> req == called || req.startsWith(called) || called.startsWith(req) }
            }.toSet()

        while (round <= maxToolRounds) {
            val toolCalls = sendTurn(
                input = nextInput,
                accumulated = accumulated,
                onPartialResult = onPartialResult,
                phase1Mode = phase1Mode,
                criticalMissingProvider = ::currentCriticalMissing,
            )

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

            // Agentic mode (no dataBundle): the streamed prose IS the user-visible
            // report. If the model has already committed to writing a real body
            // (≥ AGENTIC_KEEP_DRAFT_CHARS or contains a markdown heading), accept
            // it as the final report rather than discarding and restarting, which
            // would visibly wipe the report on the user's screen.
            //
            // BUT: never apply this escape hatch when a CRITICAL tool is missing.
            // A wrong-numbers report is strictly worse than a brief stream
            // restart. The caller marks a tool as soft-optional by leaving it
            // OUT of [criticalToolNames] while still listing it in
            // [requiredToolNames] (e.g. `get_temporal_trend` for zone reports
            // where the survey only spans one day).
            val agenticMode = dataBundle == null
            val draftLooksLikeReport = accumulated.length >= AGENTIC_KEEP_DRAFT_CHARS ||
                accumulated.contains("\n## ") ||
                accumulated.contains("\n### ")
            val criticalMissing = missing.intersect(criticalToolNames)
            if (agenticMode && draftLooksLikeReport && criticalMissing.isEmpty()) {
                Log.w(TAG, "AgentLoop: accepting partial report (chars=${accumulated.length}) despite missing tools=$missing — only non-critical tools missing")
                return accumulated.toString()
            }
            if (agenticMode && draftLooksLikeReport && criticalMissing.isNotEmpty()) {
                Log.w(TAG, "AgentLoop: CRITICAL tools still missing $criticalMissing after ${accumulated.length} chars of prose — discarding draft to enforce grounding (redirect #${redirects + 1})")
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
        criticalMissingProvider: () -> Set<String> = { emptySet() },
    ): List<ToolCall> = suspendCancellableCoroutine { continuation ->
        val collectedToolCalls = mutableListOf<ToolCall>()
        var lastPartialMs = 0L
        var resumed = false
        // criticalMissingProvider is kept on the API for future use by the
        // PHASE-1-only mode but is no longer consulted on the streaming path
        // (the v0.2.7 early-abort regressed report quality — see release notes).
        @Suppress("UNUSED_VARIABLE")
        val criticalMissingAtTurnStart = criticalMissingProvider()

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
                        // CRITICAL: cancel the native decode loop before resuming.
                        // Without this, `conv.close()` in the engine's finally
                        // block waits for the async decode to finish on its own —
                        // observed to hang 5+ min (logcat 2026-05-18 09:51/09:59/
                        // 10:02), during which the PHASE 2 prose generation
                        // NEVER starts. cancelProcess() drops the native session
                        // back to idle so close() returns immediately.
                        try { conversation.cancelProcess() } catch (_: Throwable) {}
                        continuation.resume(emptyList())
                        return
                    }
                    // No more early-abort here. v0.2.7 aborted prose at 240 chars
                    // when critical tools were missing, then injected a STOP redirect.
                    // The discarded partial-draft stayed in the model's KV cache and
                    // corrupted the subsequent retry (placeholder rows, repeated
                    // tokens, fabricated debris types). With v0.2.8 the bundle is
                    // injected into the user prompt at turn 0, so the model has all
                    // CONFIRMED DATA in KV cache from the start — even if it skips a
                    // tool call, the prose is still grounded on the bundle and the
                    // outer loop's end-of-turn enforcement (with its single-pass
                    // redirect) is enough as a backstop.
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
