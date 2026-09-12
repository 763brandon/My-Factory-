package com.myfactory.forge.data

import com.myfactory.forge.core.ai.ChatMessage
import com.myfactory.forge.core.ai.Role
import com.myfactory.forge.core.ai.ToolCall
import com.myfactory.forge.core.ai.ToolResult
import com.myfactory.forge.core.session.StoredMessage
import com.myfactory.forge.core.session.StoredRole
import com.myfactory.forge.core.util.Json
import kotlinx.serialization.builtins.ListSerializer

/**
 * Translates between the provider-format conversation the agent loop works
 * in and the rows Room stores.
 *
 * There is deliberately one source of truth. Rather than persisting the
 * display transcript and the agent's history separately and hoping they stay
 * in step, the provider-format messages are stored and both views are
 * rebuilt from them. `ToolCall` and `ToolResult` are already `@Serializable`,
 * so the existing `toolCallsJson` / `toolResultsJson` columns carry them
 * without a schema change.
 */
object ChatPersistence {

    private val toolCallList = ListSerializer(ToolCall.serializer())
    private val toolResultList = ListSerializer(ToolResult.serializer())

    fun toStored(
        message: ChatMessage,
        id: String,
        sessionId: String,
        createdAtMillis: Long,
    ): StoredMessage = StoredMessage(
        id = id,
        sessionId = sessionId,
        role = when (message.role) {
            Role.USER -> StoredRole.USER
            Role.ASSISTANT -> StoredRole.ASSISTANT
            Role.TOOL -> StoredRole.TOOL
            Role.SYSTEM -> StoredRole.SYSTEM
        },
        text = message.text.orEmpty(),
        createdAtMillis = createdAtMillis,
        toolCallsJson = message.toolCalls
            .takeIf { it.isNotEmpty() }
            ?.let { Json.storage.encodeToString(toolCallList, it) },
        toolResultsJson = message.toolResults
            .takeIf { it.isNotEmpty() }
            ?.let { Json.storage.encodeToString(toolResultList, it) },
    )

    /**
     * Returns null for a row that cannot be turned back into a wire message.
     *
     * A stored SYSTEM row is one such case: the system prompt is supplied
     * fresh each turn and must not be replayed from history, or a prompt
     * change would never take effect.
     */
    fun toChatMessage(stored: StoredMessage): ChatMessage? {
        val role = when (stored.role) {
            StoredRole.USER -> Role.USER
            StoredRole.ASSISTANT -> Role.ASSISTANT
            StoredRole.TOOL -> Role.TOOL
            StoredRole.SYSTEM, StoredRole.ERROR -> return null
        }
        return ChatMessage(
            role = role,
            text = stored.text.takeIf { it.isNotBlank() },
            toolCalls = decode(stored.toolCallsJson, toolCallList),
            toolResults = decode(stored.toolResultsJson, toolResultList),
        )
    }

    /**
     * A row written by an older build, or hand-edited, must not take the
     * conversation down with it: an undecodable field reads as empty.
     */
    private fun <T> decode(
        json: String?,
        serializer: kotlinx.serialization.KSerializer<List<T>>,
    ): List<T> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { Json.storage.decodeFromString(serializer, json) }
            .getOrDefault(emptyList())
    }
}
