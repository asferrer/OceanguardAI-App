package com.oceanguard.ai.inference

/**
 * Suite C — ToolAgentLoop early-exit behaviour.
 *
 * ASSESSMENT: not unit-testable in the current architecture without introducing
 * a mocking framework or refactoring ToolAgentLoop.
 *
 * Root cause: ToolAgentLoop receives a `com.google.ai.edge.litertlm.Conversation`
 * directly. `Conversation` is a concrete final class from the LiteRT-LM Android
 * SDK (closed source), so it cannot be subclassed or proxied without reflection.
 * The project contains no mocking framework (MockK / Mockito are absent from
 * build.gradle.kts test dependencies), and adding one solely for this class would
 * introduce a heavyweight dependency for a single test target.
 *
 * The early-exit path under test is:
 *   ToolAgentLoop.run() line ~115:
 *     if (missing.isEmpty() && dataBundle != null) { return "" }
 *
 * To make this testable, one of the following refactors is needed (production change):
 *
 *   Option A — Extract a ConversationPort interface:
 *     Create `internal interface ConversationPort` with `sendMessageAsync(input, callback)`.
 *     Wrap the real `Conversation` with `ConversationAdapter : ConversationPort`.
 *     ToolAgentLoop accepts `ConversationPort` instead of `Conversation`.
 *     A `FakeConversation : ConversationPort` can then be implemented in the test
 *     source set, simulating tool calls and empty-text turns.
 *
 *   Option B — Expose a testable pure function:
 *     Extract the loop's decision logic (missing-tool computation, redirect injection,
 *     early-exit) into a pure `internal fun computeNextAction(...)` that takes plain
 *     data objects. This pure function can be tested without any SDK dependency.
 *
 * Neither option requires changes to the public API. Both options are low-risk
 * and align with the project's "testable at unit level" goal.
 *
 * Tests will be added here once either refactor is in place.
 */
class ToolAgentLoopTest
