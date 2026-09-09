package com.myfactory.forge.core.agent

import com.myfactory.forge.core.ai.ToolSpec
import com.myfactory.forge.core.checkpoint.CheckpointManager
import com.myfactory.forge.core.files.WorkspaceFs
import kotlinx.serialization.json.JsonObject

/** What a tool did. [display] is what the user sees; [content] goes to the model. */
data class ToolOutcome(
    val content: String,
    val display: String = content,
    val isError: Boolean = false,
    /** Set when the tool changed the workspace, so the UI can refresh. */
    val mutatedPaths: List<String> = emptyList(),
) {
    companion object {
        fun error(message: String) = ToolOutcome(content = "Error: $message", isError = true)
    }
}

/** Everything a tool is allowed to reach. */
class ToolContext(
    val workspace: WorkspaceFs,
    val shell: ShellRunner?,
    val checkpoints: CheckpointManager?,
    /** From the capability tier; keeps a huge file from blowing the heap. */
    val maxOutputBytes: Int = 128 * 1024,
    val projectId: String = "",
    val sessionId: String = "",
)

/**
 * One capability offered to the model.
 *
 * [requiresApproval] is the security model of this app. Anything that writes
 * to the workspace or executes a command must set it, and the agent loop will
 * not run the tool until the user has said yes on screen.
 */
interface AgentTool {
    val spec: ToolSpec
    val requiresApproval: Boolean

    /** A single line describing this specific invocation, for the approval sheet. */
    fun describe(arguments: JsonObject): String

    suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome
}

/** Runs shell commands, when the device has somewhere to run them. */
interface ShellRunner {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    /** Where commands will run, shown in the approval sheet. */
    fun describeEnvironment(): String

    suspend fun run(
        command: String,
        workingDirectory: String?,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): Result
}

/** What the user decided about one tool invocation. */
sealed interface ApprovalDecision {
    data object Approve : ApprovalDecision

    /** Approve this tool for the rest of the session without asking again. */
    data class AlwaysAllowTool(val toolName: String) : ApprovalDecision

    data class Reject(val reason: String) : ApprovalDecision
}

data class ApprovalRequest(
    val callId: String,
    val toolName: String,
    val summary: String,
    val arguments: JsonObject,
    /** A unified diff when the tool writes a file, so the user sees the change. */
    val previewDiff: String? = null,
    /** The command line when the tool executes something. */
    val command: String? = null,
)

/**
 * The gate between the model and the user's device.
 *
 * The UI implementation suspends until the user taps. Tests substitute a fake
 * that answers immediately, which is what makes the loop's approval behaviour
 * testable without a screen.
 */
interface ApprovalGate {
    suspend fun request(request: ApprovalRequest): ApprovalDecision

    companion object {
        /** Never for production. Used by tests and by an explicit opt-in mode. */
        val ALLOW_ALL = object : ApprovalGate {
            override suspend fun request(request: ApprovalRequest): ApprovalDecision =
                ApprovalDecision.Approve
        }

        val DENY_ALL = object : ApprovalGate {
            override suspend fun request(request: ApprovalRequest): ApprovalDecision =
                ApprovalDecision.Reject("Approvals are unavailable.")
        }
    }
}
