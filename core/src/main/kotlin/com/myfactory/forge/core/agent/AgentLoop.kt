package com.myfactory.forge.core.agent

import com.myfactory.forge.core.agent.tools.ApplyPatchTool
import com.myfactory.forge.core.agent.tools.EditFileTool
import com.myfactory.forge.core.agent.tools.WriteFileTool
import com.myfactory.forge.core.ai.AiError
import com.myfactory.forge.core.ai.AiProvider
import com.myfactory.forge.core.ai.ChatMessage
import com.myfactory.forge.core.ai.ChatRequest
import com.myfactory.forge.core.ai.ProviderConfig
import com.myfactory.forge.core.ai.Role
import com.myfactory.forge.core.ai.StopReason
import com.myfactory.forge.core.ai.StreamEvent
import com.myfactory.forge.core.ai.TokenUsage
import com.myfactory.forge.core.ai.ToolCall
import com.myfactory.forge.core.ai.ToolResult
import com.myfactory.forge.core.util.Json
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What the UI renders and what the persistence layer records. */
sealed interface AgentEvent {
    data class TurnStarted(val iteration: Int) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class AssistantMessage(val text: String, val toolCalls: List<ToolCall>) : AgentEvent

    /** The model asked for a tool. Approval has not been requested yet. */
    data class ToolRequested(val call: ToolCall, val summary: String) : AgentEvent

    /** Blocking on the user. The UI shows the approval sheet on this event. */
    data class ApprovalRequired(val request: ApprovalRequest) : AgentEvent

    data class ToolApproved(val callId: String, val remembered: Boolean) : AgentEvent
    data class ToolRejected(val callId: String, val reason: String) : AgentEvent
    data class ToolStarted(val callId: String, val toolName: String) : AgentEvent
    data class ToolFinished(
        val callId: String,
        val toolName: String,
        val outcome: ToolOutcome,
    ) : AgentEvent

    data class UsageReported(val usage: TokenUsage) : AgentEvent

    /** Terminal. Exactly one of these, or [Failed], ends every run. */
    data class TurnComplete(val stopReason: StopReason, val iterations: Int) : AgentEvent
    data class Failed(val error: AiError) : AgentEvent

    /** The loop hit its iteration cap; the conversation is still usable. */
    data class IterationLimitReached(val limit: Int) : AgentEvent
}

/** Immutable conversation state. Callers keep the value returned by the loop. */
data class AgentSessionState(
    val messages: List<ChatMessage> = emptyList(),
    /** Tools the user chose to stop being asked about, for this session only. */
    val sessionApprovals: Set<String> = emptySet(),
)

/**
 * The multi-turn coding agent.
 *
 * The loop is: stream a model turn, collect any tool calls, ask the user about
 * the ones that need approval, run what was approved, feed the results back,
 * repeat. It ends when the model stops asking for tools, when the iteration
 * cap is hit, or on an error.
 *
 * Two invariants hold regardless of what the model does:
 *  - No tool marked [AgentTool.requiresApproval] runs without an explicit
 *    approval for that specific call.
 *  - A rejected or failed tool still gets a result fed back, so the model can
 *    react instead of the conversation dead-ending.
 */
class AgentLoop(
    private val provider: AiProvider,
    private val config: ProviderConfig,
    private val apiKeyProvider: suspend () -> String?,
    private val tools: List<AgentTool>,
    private val approvals: ApprovalGate,
    private val context: ToolContext,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val maxIterations: Int = 25,
) {

    private val toolsByName = tools.associateBy { it.spec.name }

    /**
     * Runs one user request to completion.
     *
     * @param onStateChanged receives the updated conversation after each turn
     *   so a caller can persist it without waiting for the flow to finish.
     */
    fun run(
        state: AgentSessionState,
        userMessage: String,
        onStateChanged: (AgentSessionState) -> Unit = {},
    ): Flow<AgentEvent> = flow {
        var conversation = state.messages + ChatMessage.user(userMessage)
        var approvedTools = state.sessionApprovals
        val apiKey = apiKeyProvider()

        var iteration = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            iteration++
            if (iteration > maxIterations) {
                emit(AgentEvent.IterationLimitReached(maxIterations))
                onStateChanged(AgentSessionState(conversation, approvedTools))
                return@flow
            }
            emit(AgentEvent.TurnStarted(iteration))

            val turn = streamOneTurn(conversation, apiKey)
            if (turn.error != null) {
                emit(AgentEvent.Failed(turn.error))
                onStateChanged(AgentSessionState(conversation, approvedTools))
                return@flow
            }
            turn.usage?.let { emit(AgentEvent.UsageReported(it)) }

            val assistant = ChatMessage.assistant(
                turn.text.takeIf { it.isNotBlank() },
                turn.toolCalls,
            )
            conversation = conversation + assistant
            emit(AgentEvent.AssistantMessage(turn.text, turn.toolCalls))
            onStateChanged(AgentSessionState(conversation, approvedTools))

            if (turn.toolCalls.isEmpty()) {
                emit(AgentEvent.TurnComplete(turn.stopReason, iteration))
                return@flow
            }

            val results = mutableListOf<ToolResult>()
            for (call in turn.toolCalls) {
                currentCoroutineContext().ensureActive()
                val tool = toolsByName[call.name]
                if (tool == null) {
                    // The model invented a tool. Tell it plainly; it recovers.
                    val message = "No tool named '${call.name}' exists. Available tools: " +
                        toolsByName.keys.sorted().joinToString(", ")
                    results += ToolResult(call.id, call.name, message, isError = true)
                    emit(
                        AgentEvent.ToolFinished(
                            call.id,
                            call.name,
                            ToolOutcome.error(message),
                        ),
                    )
                    continue
                }

                val arguments = parseArguments(call)
                if (arguments == null) {
                    val message = "The arguments for '${call.name}' were not valid JSON."
                    results += ToolResult(call.id, call.name, message, isError = true)
                    emit(AgentEvent.ToolFinished(call.id, call.name, ToolOutcome.error(message)))
                    continue
                }

                val summary = runCatching { tool.describe(arguments) }.getOrDefault(call.name)
                emit(AgentEvent.ToolRequested(call, summary))

                if (tool.requiresApproval && call.name !in approvedTools) {
                    val request = buildApprovalRequest(call, tool, arguments, summary)
                    emit(AgentEvent.ApprovalRequired(request))

                    when (val decision = approvals.request(request)) {
                        is ApprovalDecision.Reject -> {
                            val message = "The user declined this action. " + decision.reason
                            results += ToolResult(call.id, call.name, message, isError = true)
                            emit(AgentEvent.ToolRejected(call.id, decision.reason))
                            continue
                        }
                        is ApprovalDecision.AlwaysAllowTool -> {
                            approvedTools = approvedTools + decision.toolName
                            emit(AgentEvent.ToolApproved(call.id, remembered = true))
                        }
                        ApprovalDecision.Approve -> {
                            emit(AgentEvent.ToolApproved(call.id, remembered = false))
                        }
                    }
                }

                emit(AgentEvent.ToolStarted(call.id, call.name))
                val outcome = try {
                    tool.execute(arguments, context)
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    ToolOutcome.error(e.message ?: "The tool failed unexpectedly.")
                }
                emit(AgentEvent.ToolFinished(call.id, call.name, outcome))
                results += ToolResult(
                    callId = call.id,
                    name = call.name,
                    content = outcome.content.take(context.maxOutputBytes),
                    isError = outcome.isError,
                )
            }

            conversation = conversation + ChatMessage.toolResults(results)
            onStateChanged(AgentSessionState(conversation, approvedTools))
        }
    }

    /** Collects one provider stream into a complete turn. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.streamOneTurn(
        conversation: List<ChatMessage>,
        apiKey: String?,
    ): TurnResult {
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var usage: TokenUsage? = null
        var stopReason = StopReason.END_TURN
        var error: AiError? = null

        val request = ChatRequest(
            config = config,
            apiKey = apiKey,
            system = systemPrompt,
            messages = conversation.filter { it.role != Role.SYSTEM },
            tools = if (config.toolsEnabled) tools.map { it.spec } else emptyList(),
        )

        provider.stream(request).collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> {
                    text.append(event.text)
                    emit(AgentEvent.TextDelta(event.text))
                }
                is StreamEvent.ToolCallCompleted -> calls += event.call
                is StreamEvent.Usage -> usage = event.usage
                is StreamEvent.Completed -> stopReason = event.stopReason
                is StreamEvent.Failed -> error = event.error
                // Started and Delta drive a typing indicator in the UI; the
                // completed call carries everything the loop needs.
                is StreamEvent.ToolCallStarted, is StreamEvent.ToolCallDelta -> Unit
            }
        }

        return TurnResult(text.toString(), calls, usage, stopReason, error)
    }

    private fun buildApprovalRequest(
        call: ToolCall,
        tool: AgentTool,
        arguments: JsonObject,
        summary: String,
    ): ApprovalRequest {
        // A preview is the difference between informed consent and a yes/no
        // box. Build one whenever the tool can produce it.
        val preview = runCatching {
            when (tool) {
                is WriteFileTool -> tool.previewDiff(arguments, context)
                is EditFileTool -> tool.previewDiff(arguments, context)
                is ApplyPatchTool -> tool.previewDiff(arguments)
                else -> null
            }
        }.getOrNull()

        val command = if (call.name == "run_command") {
            arguments["command"]?.let { element ->
                runCatching { element.toString().trim('"') }.getOrNull()
            }
        } else {
            null
        }

        return ApprovalRequest(
            callId = call.id,
            toolName = call.name,
            summary = summary,
            arguments = arguments,
            previewDiff = preview,
            command = command,
        )
    }

    private fun parseArguments(call: ToolCall): JsonObject? = runCatching {
        val raw = call.argumentsJson.trim().ifEmpty { "{}" }
        Json.lenient.parseToJsonElement(raw).jsonObject
    }.getOrNull()

    private data class TurnResult(
        val text: String,
        val toolCalls: List<ToolCall>,
        val usage: TokenUsage?,
        val stopReason: StopReason,
        val error: AiError?,
    )

    companion object {
        val DEFAULT_SYSTEM_PROMPT: String = listOf(
            "You are a coding assistant running inside a mobile IDE, on the user's phone.",
            "",
            "Working rules:",
            "- Read before you write. Use project_overview and read_file to understand the",
            "  code before changing it.",
            "- Make the smallest change that does the job. Match the surrounding style.",
            "- Every write and every command needs the user's approval, shown to them as a",
            "  diff. Explain what you are about to do before you request it.",
            "- Screens are small. Keep replies short and concrete. Put code in fenced blocks.",
            "- If a tool fails, read the error and adapt. Do not repeat the same call.",
            "- Some devices have no Linux runtime. If run_command reports that, work by",
            "  editing files instead and say so.",
        ).joinToString(10.toChar().toString())
    }
}
