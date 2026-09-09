package com.myfactory.forge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myfactory.forge.core.agent.AgentEvent
import com.myfactory.forge.core.agent.AgentLoop
import com.myfactory.forge.core.agent.AgentSessionState
import com.myfactory.forge.core.agent.ApprovalDecision
import com.myfactory.forge.core.agent.ApprovalGate
import com.myfactory.forge.core.agent.ApprovalRequest
import com.myfactory.forge.core.agent.ToolContext
import com.myfactory.forge.core.agent.ToolRegistry
import com.myfactory.forge.core.ai.AiError
import com.myfactory.forge.core.ai.ProviderConfig
import com.myfactory.forge.core.capability.Capabilities
import com.myfactory.forge.core.checkpoint.CheckpointManager
import com.myfactory.forge.core.files.WorkspaceFs
import com.myfactory.forge.core.session.AuditCategory
import com.myfactory.forge.di.AppContainer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** One entry in the visible transcript. */
sealed interface ChatEntry {
    val id: String

    data class User(override val id: String, val text: String) : ChatEntry

    data class Assistant(
        override val id: String,
        val text: String,
        val streaming: Boolean,
    ) : ChatEntry

    data class Tool(
        override val id: String,
        val toolName: String,
        val summary: String,
        val status: ToolStatus,
        val detail: String,
    ) : ChatEntry

    data class Notice(override val id: String, val text: String, val isError: Boolean) : ChatEntry
}

enum class ToolStatus { RUNNING, SUCCEEDED, FAILED, REJECTED }

data class ChatUiState(
    val entries: List<ChatEntry> = emptyList(),
    val isRunning: Boolean = false,
    val pendingApproval: ApprovalRequest? = null,
    val activeProvider: ProviderConfig? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val shellDescription: String? = null,
) {
    val canSend: Boolean get() = !isRunning && activeProvider != null
}

/**
 * Drives one agent conversation.
 *
 * The approval gate is implemented here as a suspending handshake: the loop
 * calls [ApprovalGate.request], this class publishes the request into the UI
 * state and waits on a deferred that the screen completes when the user taps.
 * That keeps the loop's contract honest without the loop ever knowing a screen
 * exists.
 */
class ChatViewModel(
    private val container: AppContainer,
    private val projectId: String,
    private val projectRoot: File,
    private val sessionId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var agentState = AgentSessionState()
    private var runJob: Job? = null
    private var pendingDecision: CompletableDeferred<ApprovalDecision>? = null

    /** Supplied by the caller so the terminal and the agent share one runtime. */
    var shellRunnerProvider: () -> com.myfactory.forge.core.agent.ShellRunner? = { null }

    private val workspace by lazy { WorkspaceFs(projectRoot) }

    private val checkpoints by lazy {
        CheckpointManager(workspace, File(container.checkpointsRoot, projectId))
    }

    private val gate = object : ApprovalGate {
        override suspend fun request(request: ApprovalRequest): ApprovalDecision {
            val deferred = CompletableDeferred<ApprovalDecision>()
            pendingDecision = deferred
            _state.update { it.copy(pendingApproval = request) }
            val decision = deferred.await()
            _state.update { it.copy(pendingApproval = null) }
            pendingDecision = null

            container.repository.let { repo ->
                viewModelScope.launch {
                    repo.record(
                        category = AuditCategory.PERMISSION_DECISION,
                        summary = when (decision) {
                            is ApprovalDecision.Reject -> "Declined ${request.toolName}"
                            is ApprovalDecision.AlwaysAllowTool ->
                                "Allowed ${request.toolName} for the session"
                            ApprovalDecision.Approve -> "Allowed ${request.toolName} once"
                        },
                        detail = request.summary,
                        projectId = projectId,
                        sessionId = sessionId,
                    )
                }
            }
            return decision
        }
    }

    fun refreshProvider() {
        viewModelScope.launch {
            val activeId = container.settings.current.activeProviderConfigId
            val config = activeId?.let { container.repository.providerConfig(it) }
                ?: container.repository.providerConfigs().firstOrNull()
            _state.update {
                it.copy(
                    activeProvider = config,
                    shellDescription = shellRunnerProvider()?.describeEnvironment(),
                )
            }
        }
    }

    fun send(message: String) {
        val config = _state.value.activeProvider ?: return
        if (message.isBlank() || _state.value.isRunning) return

        val provider = container.providers.getOrNull(config.providerId) ?: return
        val capabilities = container.capabilities.value

        appendEntry(ChatEntry.User(newId(), message))
        _state.update { it.copy(isRunning = true) }

        runJob = viewModelScope.launch {
            if (container.settings.current.autoCheckpointBeforeAgentWrites) {
                takeAutomaticCheckpoint()
            }

            val loop = buildLoop(config, provider, capabilities)
            var assistantId: String? = null

            loop.run(agentState, message) { agentState = it }
                .collect { event -> assistantId = handle(event, assistantId) }

            _state.update { it.copy(isRunning = false) }
        }
    }

    fun stop() {
        runJob?.cancel()
        pendingDecision?.complete(ApprovalDecision.Reject("Cancelled."))
        _state.update { it.copy(isRunning = false, pendingApproval = null) }
    }

    fun resolveApproval(decision: ApprovalDecision) {
        pendingDecision?.complete(decision)
    }

    private fun buildLoop(
        config: ProviderConfig,
        provider: com.myfactory.forge.core.ai.AiProvider,
        capabilities: Capabilities,
    ): AgentLoop {
        val shell = shellRunnerProvider()
        return AgentLoop(
            provider = provider,
            config = config,
            apiKeyProvider = { config.apiKeyAlias?.let { container.secretStore.get(it) } },
            tools = ToolRegistry.forCapabilities(
                capabilities = capabilities,
                allowCommands = shell != null,
            ),
            approvals = gate,
            context = ToolContext(
                workspace = workspace,
                shell = shell,
                checkpoints = checkpoints,
                maxOutputBytes = capabilities.maxToolOutputBytes,
                projectId = projectId,
                sessionId = sessionId,
            ),
            maxIterations = capabilities.maxAgentIterations,
        )
    }

    /**
     * @return the id of the assistant entry currently streaming, so the next
     *   delta appends to it rather than creating a new bubble per token.
     */
    private suspend fun handle(event: AgentEvent, streamingId: String?): String? = when (event) {
        is AgentEvent.TextDelta -> {
            val id = streamingId ?: newId().also {
                appendEntry(ChatEntry.Assistant(it, "", streaming = true))
            }
            updateEntry(id) { entry ->
                (entry as ChatEntry.Assistant).copy(text = entry.text + event.text)
            }
            id
        }

        is AgentEvent.AssistantMessage -> {
            streamingId?.let { id ->
                updateEntry(id) { (it as ChatEntry.Assistant).copy(streaming = false) }
            }
            null
        }

        is AgentEvent.ToolRequested -> {
            appendEntry(
                ChatEntry.Tool(
                    id = event.call.id,
                    toolName = event.call.name,
                    summary = event.summary,
                    status = ToolStatus.RUNNING,
                    detail = "",
                ),
            )
            streamingId
        }

        is AgentEvent.ToolRejected -> {
            updateEntry(event.callId) {
                (it as ChatEntry.Tool).copy(status = ToolStatus.REJECTED, detail = event.reason)
            }
            streamingId
        }

        is AgentEvent.ToolFinished -> {
            updateEntry(event.callId) { entry ->
                (entry as ChatEntry.Tool).copy(
                    status = if (event.outcome.isError) ToolStatus.FAILED else ToolStatus.SUCCEEDED,
                    detail = event.outcome.display,
                )
            }
            if (event.outcome.mutatedPaths.isNotEmpty()) {
                container.repository.record(
                    category = AuditCategory.FILE_WRITE,
                    summary = event.outcome.display,
                    detail = event.outcome.mutatedPaths.joinToString(", "),
                    projectId = projectId,
                    sessionId = sessionId,
                )
            }
            streamingId
        }

        is AgentEvent.UsageReported -> {
            _state.update {
                it.copy(
                    inputTokens = it.inputTokens + event.usage.inputTokens,
                    outputTokens = it.outputTokens + event.usage.outputTokens,
                )
            }
            streamingId
        }

        is AgentEvent.Failed -> {
            appendEntry(ChatEntry.Notice(newId(), describe(event.error), isError = true))
            streamingId
        }

        is AgentEvent.IterationLimitReached -> {
            appendEntry(
                ChatEntry.Notice(
                    newId(),
                    "The agent stopped after ${event.limit} turns. Send another message to continue.",
                    isError = false,
                ),
            )
            streamingId
        }

        // These drive nothing the transcript shows on its own.
        is AgentEvent.TurnStarted,
        is AgentEvent.ApprovalRequired,
        is AgentEvent.ToolApproved,
        is AgentEvent.ToolStarted,
        is AgentEvent.TurnComplete,
        -> streamingId
    }

    private suspend fun takeAutomaticCheckpoint() {
        runCatching {
            val checkpoint = checkpoints.create(
                projectId = projectId,
                label = "Before agent turn",
                automatic = true,
                sessionId = sessionId,
            )
            container.repository.saveCheckpoint(checkpoint)
            container.repository.pruneAutomaticCheckpoints(projectId).forEach {
                checkpoints.delete(it)
            }
            container.repository.record(
                category = AuditCategory.CHECKPOINT_CREATE,
                summary = "Automatic checkpoint, ${checkpoint.fileCount} files",
                projectId = projectId,
                sessionId = sessionId,
            )
        }.onFailure { error ->
            // A project too large to snapshot should not block the agent; the
            // user is told, and can turn the setting off.
            appendEntry(
                ChatEntry.Notice(
                    newId(),
                    "Could not take a checkpoint: ${error.message}",
                    isError = false,
                ),
            )
        }
    }

    private fun describe(error: AiError): String = when (error) {
        is AiError.Auth -> "The provider rejected your API key. Check it in Settings."
        is AiError.RateLimited -> "Rate limited by the provider. Try again shortly."
        is AiError.Network -> "Could not reach the provider. Check your connection."
        else -> error.message ?: "Something went wrong."
    }

    private fun appendEntry(entry: ChatEntry) {
        _state.update { it.copy(entries = it.entries + entry) }
    }

    private fun updateEntry(id: String, transform: (ChatEntry) -> ChatEntry) {
        _state.update { state ->
            state.copy(
                entries = state.entries.map { if (it.id == id) transform(it) else it },
            )
        }
    }

    private fun newId() = UUID.randomUUID().toString()

    override fun onCleared() {
        runJob?.cancel()
        super.onCleared()
    }
}
