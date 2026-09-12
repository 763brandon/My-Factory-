package com.myfactory.forge.data

import com.myfactory.forge.core.ai.ChatMessage
import com.myfactory.forge.core.ai.Role
import com.myfactory.forge.core.ai.ToolCall
import com.myfactory.forge.core.ai.ToolResult
import com.myfactory.forge.core.session.StoredMessage
import com.myfactory.forge.core.session.StoredRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reloading a conversation has to reproduce it exactly, tool calls included.
 * Losing a tool_use block on restore would leave the next request with a
 * tool result that answers nothing, which every provider rejects.
 */
class ChatPersistenceTest {

    private fun roundTrip(message: ChatMessage): ChatMessage? {
        val stored = ChatPersistence.toStored(message, "m1", "s1", 1_700_000_000_000L)
        return ChatPersistence.toChatMessage(stored)
    }

    @Test
    fun `a plain user message survives`() {
        val original = ChatMessage.user("Fix the login bug")

        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `an assistant message with tool calls survives intact`() {
        val original = ChatMessage.assistant(
            "Let me look at that file.",
            listOf(
                ToolCall("call_1", "read_file", "{\"path\":\"src/App.kt\"}"),
                ToolCall("call_2", "search", "{\"pattern\":\"login\"}"),
            ),
        )

        val restored = roundTrip(original)!!

        assertEquals(original.text, restored.text)
        assertEquals(2, restored.toolCalls.size)
        assertEquals("call_1", restored.toolCalls[0].id)
        assertEquals("read_file", restored.toolCalls[0].name)
        // The arguments must come back byte-identical: the provider echoes
        // them back and a changed shape breaks the tool_use pairing.
        assertEquals("{\"path\":\"src/App.kt\"}", restored.toolCalls[0].argumentsJson)
    }

    @Test
    fun `tool results survive including the error flag`() {
        val original = ChatMessage.toolResults(
            listOf(
                ToolResult("call_1", "read_file", "file contents"),
                ToolResult("call_2", "run_command", "not permitted", isError = true),
            ),
        )

        val restored = roundTrip(original)!!

        assertEquals(Role.TOOL, restored.role)
        assertEquals(2, restored.toolResults.size)
        assertTrue(restored.toolResults[1].isError)
        assertEquals("not permitted", restored.toolResults[1].content)
    }

    @Test
    fun `an assistant turn that is only a tool call keeps a null text`() {
        val original = ChatMessage.assistant(null, listOf(ToolCall("c", "list_directory", "{}")))

        val restored = roundTrip(original)!!

        // Empty string and null are different on the wire: some providers
        // reject an assistant message whose content is an empty string.
        assertNull(restored.text)
        assertEquals(1, restored.toolCalls.size)
    }

    @Test
    fun `a stored system row is not replayed into the conversation`() {
        // The system prompt is supplied fresh each turn. Replaying an old one
        // would pin the conversation to a prompt the app no longer uses.
        val stored = StoredMessage(
            id = "m1",
            sessionId = "s1",
            role = StoredRole.SYSTEM,
            text = "You are a coding assistant.",
            createdAtMillis = 0,
        )

        assertNull(ChatPersistence.toChatMessage(stored))
    }

    @Test
    fun `an error row is not replayed either`() {
        val stored = StoredMessage(
            id = "m1",
            sessionId = "s1",
            role = StoredRole.ERROR,
            text = "Rate limited",
            createdAtMillis = 0,
        )

        assertNull(ChatPersistence.toChatMessage(stored))
    }

    @Test
    fun `a corrupt tool call column degrades to empty rather than losing the turn`() {
        val stored = StoredMessage(
            id = "m1",
            sessionId = "s1",
            role = StoredRole.ASSISTANT,
            text = "Some reply",
            createdAtMillis = 0,
            toolCallsJson = "{ this is not valid json",
        )

        val restored = ChatPersistence.toChatMessage(stored)!!

        assertEquals("Some reply", restored.text)
        assertTrue("the message text must survive a bad column", restored.toolCalls.isEmpty())
    }

    @Test
    fun `messages without tool data store null rather than an empty array`() {
        val stored = ChatPersistence.toStored(ChatMessage.user("hi"), "m1", "s1", 0)

        assertNull(stored.toolCallsJson)
        assertNull(stored.toolResultsJson)
    }

    @Test
    fun `a whole conversation round-trips in order`() {
        val conversation = listOf(
            ChatMessage.user("Read build.gradle"),
            ChatMessage.assistant("Sure.", listOf(ToolCall("c1", "read_file", "{}"))),
            ChatMessage.toolResults(listOf(ToolResult("c1", "read_file", "plugins { }"))),
            ChatMessage.assistant("It applies the Kotlin plugin."),
        )

        val restored = conversation
            .mapIndexed { index, m -> ChatPersistence.toStored(m, "m$index", "s1", index.toLong()) }
            .mapNotNull(ChatPersistence::toChatMessage)

        assertEquals(conversation.size, restored.size)
        assertEquals(conversation.map { it.role }, restored.map { it.role })
        assertEquals(conversation, restored)
    }
}
