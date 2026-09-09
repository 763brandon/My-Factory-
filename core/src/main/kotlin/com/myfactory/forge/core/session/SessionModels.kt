package com.myfactory.forge.core.session

import com.myfactory.forge.core.ai.ProviderId
import kotlinx.serialization.Serializable

/** A workspace the user is working in. */
@Serializable
data class Project(
    val id: String,
    val name: String,
    /** Absolute path on the device. Kept out of anything sent to a provider. */
    val rootPath: String,
    val createdAtMillis: Long,
    val lastOpenedMillis: Long,
    val gitRemote: String? = null,
)

/** One conversation with the agent, scoped to a project. */
@Serializable
data class Session(
    val id: String,
    val projectId: String,
    val title: String,
    val providerConfigId: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
)

enum class StoredRole { USER, ASSISTANT, TOOL, SYSTEM, ERROR }

/** A message as it is persisted and rendered, which is not quite the wire shape. */
@Serializable
data class StoredMessage(
    val id: String,
    val sessionId: String,
    val role: StoredRole,
    val text: String,
    val createdAtMillis: Long,
    /** Serialised tool calls, for replaying a conversation to a provider. */
    val toolCallsJson: String? = null,
    val toolResultsJson: String? = null,
    val isStreaming: Boolean = false,
)

/** What happened when a tool ran. Drives both the UI log and the audit trail. */
enum class ToolOutcomeStatus { PENDING_APPROVAL, APPROVED, REJECTED, SUCCEEDED, FAILED }

@Serializable
data class ToolInvocationRecord(
    val id: String,
    val sessionId: String,
    val messageId: String,
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val status: ToolOutcomeStatus,
    val resultPreview: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long?,
    val requiredApproval: Boolean,
)

/**
 * An append-only record of everything that touched the user's files, ran a
 * command, or left the device.
 *
 * This is a privacy feature, not a debugging one: it is the answer to "what
 * did this app actually do, and where did my code go".
 */
@Serializable
data class AuditEntry(
    val id: String,
    val timestampMillis: Long,
    val category: AuditCategory,
    val summary: String,
    val detail: String,
    val projectId: String? = null,
    val sessionId: String? = null,
)

enum class AuditCategory {
    FILE_WRITE,
    FILE_DELETE,
    COMMAND_RUN,
    NETWORK_REQUEST,
    CHECKPOINT_CREATE,
    CHECKPOINT_RESTORE,
    KEY_STORED,
    KEY_DELETED,
    PERMISSION_DECISION,
}

/** Everything needed to talk to a provider, resolved for one turn. */
data class ResolvedProvider(
    val providerId: ProviderId,
    val configId: String,
    val model: String,
    val destinationHost: String,
)
