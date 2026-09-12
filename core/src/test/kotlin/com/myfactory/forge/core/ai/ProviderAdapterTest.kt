package com.myfactory.forge.core.ai

import com.myfactory.forge.core.ai.providers.AnthropicProvider
import com.myfactory.forge.core.ai.providers.GeminiProvider
import com.myfactory.forge.core.ai.providers.OpenAiCompatibleProvider
import com.myfactory.forge.core.ai.providers.OpenAiProvider
import com.myfactory.forge.core.ai.providers.OpenRouterProvider
import com.myfactory.forge.core.util.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each adapter is checked on two axes: the request it builds, and the
 * normalised events it produces from a recorded stream. Together those are
 * the whole contract the agent loop depends on.
 */
class ProviderAdapterTest {

    private val schema = JsonSchemas.obj(
        properties = mapOf("path" to JsonSchemas.string("A path")),
        required = listOf("path"),
    )
    private val tool = ToolSpec("read_file", "Read a file", schema)

    private fun config(provider: ProviderId, base: String, model: String) = ProviderConfig(
        id = "cfg",
        providerId = provider,
        displayName = "Test",
        baseUrl = base,
        model = model,
        maxOutputTokens = 1024,
    )

    private fun request(config: ProviderConfig, tools: List<ToolSpec> = listOf(tool)) = ChatRequest(
        config = config,
        apiKey = "secret-key",
        system = "You are a coding assistant.",
        messages = listOf(
            ChatMessage.user("Read build.gradle"),
            ChatMessage.assistant(
                "Sure.",
                listOf(ToolCall("call_1", "read_file", "{\"path\":\"build.gradle\"}")),
            ),
            ChatMessage.toolResults(
                listOf(ToolResult("call_1", "read_file", "plugins { }")),
            ),
        ),
        tools = tools,
    )

    // ---------------------------------------------------------------- Anthropic

    @Test
    fun `anthropic request uses typed content blocks and the version header`() {
        val transport = FakeTransport()
        val provider = AnthropicProvider(transport)
        val config = config(ProviderId.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-5")

        val body = Json.lenient.parseToJsonElement(provider.buildBody(request(config))).jsonObject

        assertEquals("claude-sonnet-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(1024, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["stream"]!!.jsonPrimitive.content.toBoolean())
        // The system prompt is hoisted, not sent as a message.
        assertEquals("You are a coding assistant.", body["system"]!!.jsonPrimitive.content)

        val messages = body["messages"]!!.jsonArray
        assertEquals(3, messages.size)

        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val blocks = assistant["content"]!!.jsonArray
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tool_use", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)

        // Tool results are a user message, which is the shape the API requires.
        val results = messages[2].jsonObject
        assertEquals("user", results["role"]!!.jsonPrimitive.content)
        assertEquals(
            "tool_result",
            results["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content,
        )

        val tools = body["tools"]!!.jsonArray
        assertEquals("read_file", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertNotNull(tools[0].jsonObject["input_schema"])
    }

    @Test
    fun `anthropic stream assembles fragmented tool arguments`() = runTest {
        val body = Sse.body(
            "message_start" to "{\"type\":\"message_start\"}",
            "content_block_start" to
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\"}}",
            "content_block_delta" to
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Reading\"}}",
            "content_block_stop" to "{\"type\":\"content_block_stop\",\"index\":0}",
            "content_block_start" to
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":" +
                "{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"read_file\"}}",
            "content_block_delta" to
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":" +
                "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\"}}",
            "content_block_delta" to
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":" +
                "{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"a.kt\\\"}\"}}",
            "content_block_stop" to "{\"type\":\"content_block_stop\",\"index\":1}",
            "message_delta" to
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}," +
                "\"usage\":{\"input_tokens\":12,\"output_tokens\":34}}",
            "message_stop" to "{\"type\":\"message_stop\"}",
        )
        val provider = AnthropicProvider(FakeTransport(sseBody = body, chunkSize = 13))
        val config = config(ProviderId.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-5")

        val events = provider.stream(request(config)).toList()

        assertEquals("Reading", events.filterIsInstance<StreamEvent.TextDelta>()
            .joinToString("") { it.text })

        val call = events.filterIsInstance<StreamEvent.ToolCallCompleted>().single().call
        assertEquals("toolu_9", call.id)
        assertEquals("read_file", call.name)
        assertEquals("{\"path\":\"a.kt\"}", call.argumentsJson)

        val usage = events.filterIsInstance<StreamEvent.Usage>().single().usage
        assertEquals(12, usage.inputTokens)
        assertEquals(34, usage.outputTokens)

        assertEquals(
            StopReason.TOOL_USE,
            events.filterIsInstance<StreamEvent.Completed>().single().stopReason,
        )
    }

    @Test
    fun `anthropic error event becomes a failed event rather than an exception`() = runTest {
        val body = Sse.body(
            "error" to "{\"type\":\"error\",\"error\":{\"message\":\"overloaded\"}}",
        )
        val provider = AnthropicProvider(FakeTransport(sseBody = body))

        val events = provider.stream(
            request(config(ProviderId.ANTHROPIC, "https://api.anthropic.com", "m")),
        ).toList()

        val failure = events.filterIsInstance<StreamEvent.Failed>().single()
        assertTrue(failure.error.message!!.contains("overloaded"))
    }

    // ------------------------------------------------------------------- OpenAI

    @Test
    fun `openai request puts the system prompt first and wraps tools in function`() {
        val provider = OpenAiProvider(FakeTransport())
        val config = config(ProviderId.OPENAI, "https://api.openai.com/v1", "gpt-4.1")

        val body = Json.lenient.parseToJsonElement(provider.buildBody(request(config))).jsonObject
        val messages = body["messages"]!!.jsonArray

        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)

        val assistant = messages[2].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val toolCalls = assistant["tool_calls"]!!.jsonArray
        assertEquals("function", toolCalls[0].jsonObject["type"]!!.jsonPrimitive.content)

        // Each result is its own message, unlike Anthropic's batching.
        val result = messages[3].jsonObject
        assertEquals("tool", result["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", result["tool_call_id"]!!.jsonPrimitive.content)

        val tools = body["tools"]!!.jsonArray
        assertEquals("function", tools[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "read_file",
            tools[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `openai stream merges tool call fragments by index`() = runTest {
        val body = Sse.dataOnly(
            "{\"choices\":[{\"delta\":{\"content\":\"Let me look.\"}}]}",
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\"," +
                "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"pa\"}}]}}]}",
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0," +
                "\"function\":{\"arguments\":\"th\\\":\\\"x.kt\\\"}\"}}]}}]}",
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]," +
                "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":7}}",
            "[DONE]",
        )
        val provider = OpenAiProvider(FakeTransport(sseBody = body, chunkSize = 17))

        val events = provider.stream(
            request(config(ProviderId.OPENAI, "https://api.openai.com/v1", "gpt-4.1")),
        ).toList()

        val call = events.filterIsInstance<StreamEvent.ToolCallCompleted>().single().call
        assertEquals("call_a", call.id)
        assertEquals("{\"path\":\"x.kt\"}", call.argumentsJson)
        assertEquals(
            StopReason.TOOL_USE,
            events.filterIsInstance<StreamEvent.Completed>().single().stopReason,
        )
        assertEquals(5, events.filterIsInstance<StreamEvent.Usage>().single().usage.inputTokens)
    }

    @Test
    fun `two parallel tool calls come back in index order`() = runTest {
        val body = Sse.dataOnly(
            "{\"choices\":[{\"delta\":{\"tool_calls\":[" +
                "{\"index\":1,\"id\":\"b\",\"function\":{\"name\":\"t2\",\"arguments\":\"{}\"}}," +
                "{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"t1\",\"arguments\":\"{}\"}}]}}]}",
            "[DONE]",
        )
        val provider = OpenAiProvider(FakeTransport(sseBody = body))

        val calls = provider.stream(
            request(config(ProviderId.OPENAI, "https://api.openai.com/v1", "m")),
        ).toList().filterIsInstance<StreamEvent.ToolCallCompleted>().map { it.call.id }

        assertEquals(listOf("a", "b"), calls)
    }

    @Test
    fun `a stream that ends without DONE still completes`() = runTest {
        // Some self-hosted gateways just close the socket.
        val body = Sse.dataOnly("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}")
        val provider = OpenAiCompatibleProvider(FakeTransport(sseBody = body))

        val events = provider.stream(
            request(config(ProviderId.OPENAI_COMPATIBLE, "http://127.0.0.1:11434/v1", "llama3")),
        ).toList()

        assertEquals(1, events.filterIsInstance<StreamEvent.Completed>().size)
        assertEquals("hi", events.filterIsInstance<StreamEvent.TextDelta>().single().text)
    }

    @Test
    fun `a self-hosted endpoint does not require an api key`() {
        val provider = OpenAiCompatibleProvider(FakeTransport())

        assertTrue(!provider.requiresApiKey())
        assertEquals("http://127.0.0.1:11434/v1", provider.defaultBaseUrl())
    }

    @Test
    fun `openrouter keeps the openai dialect but its own base url`() {
        val provider = OpenRouterProvider(FakeTransport())

        assertEquals(ProviderId.OPENROUTER, provider.id)
        assertEquals("https://openrouter.ai/api/v1", provider.defaultBaseUrl())
    }

    // ------------------------------------------------------------------- Gemini

    @Test
    fun `gemini request renames the assistant role and hoists the system prompt`() {
        val provider = GeminiProvider(FakeTransport())
        val config = config(
            ProviderId.GEMINI,
            "https://generativelanguage.googleapis.com/v1beta",
            "gemini-2.5-pro",
        )

        val body = Json.lenient.parseToJsonElement(provider.buildBody(request(config))).jsonObject
        val contents = body["contents"]!!.jsonArray

        assertEquals("user", contents[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("model", contents[1].jsonObject["role"]!!.jsonPrimitive.content)
        // Function results go back as a user turn carrying functionResponse.
        assertNotNull(
            contents[2].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"],
        )
        assertNotNull(body["systemInstruction"])

        val declarations = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals("read_file", declarations[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini schema sanitising removes keys the api rejects`() {
        val messy = Json.lenient.parseToJsonElement(
            "{\"type\":\"object\",\"additionalProperties\":false," +
                "\"properties\":{\"p\":{\"type\":\"string\",\"pattern\":\"^a\"}}}",
        ).jsonObject

        val clean = JsonSchemas.sanitiseForGemini(messy)

        assertNull(clean["additionalProperties"])
        assertNull(clean["properties"]!!.jsonObject["p"]!!.jsonObject["pattern"])
        assertEquals("object", clean["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini function calls are normalised into the same event triple`() = runTest {
        val body = Sse.dataOnly(
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Looking\"}]}}]}",
            "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":" +
                "{\"name\":\"read_file\",\"args\":{\"path\":\"a.kt\"}}}]}}]," +
                "\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":4}}",
        )
        val provider = GeminiProvider(FakeTransport(sseBody = body))

        val events = provider.stream(
            request(
                config(
                    ProviderId.GEMINI,
                    "https://generativelanguage.googleapis.com/v1beta",
                    "gemini-2.5-pro",
                ),
            ),
        ).toList()

        assertEquals(1, events.filterIsInstance<StreamEvent.ToolCallStarted>().size)
        val call = events.filterIsInstance<StreamEvent.ToolCallCompleted>().single().call
        assertEquals("read_file", call.name)
        assertTrue(call.argumentsJson.contains("a.kt"))
        assertEquals(
            StopReason.TOOL_USE,
            events.filterIsInstance<StreamEvent.Completed>().single().stopReason,
        )
    }

    @Test
    fun `gemini streams to the sse endpoint with the key in a header`() = runTest {
        val transport = FakeTransport(sseBody = Sse.dataOnly("{\"candidates\":[]}"))
        val provider = GeminiProvider(transport)

        provider.stream(
            request(
                config(
                    ProviderId.GEMINI,
                    "https://generativelanguage.googleapis.com/v1beta",
                    "gemini-2.5-flash",
                ),
            ),
        ).toList()

        assertTrue(transport.lastUrl!!.endsWith(":streamGenerateContent?alt=sse"))
        assertTrue(transport.lastUrl!!.contains("gemini-2.5-flash"))
        // The key must not land in the URL, where proxies log it.
        assertTrue(!transport.lastUrl!!.contains("secret-key"))
        assertEquals("secret-key", transport.lastHeaders["x-goog-api-key"])
    }

    // ------------------------------------------------------------------ Shared

    @Test
    fun `a malformed chunk fails the stream instead of crashing the session`() = runTest {
        val provider = OpenAiProvider(FakeTransport(sseBody = Sse.dataOnly("{not json")))

        val events = provider.stream(
            request(config(ProviderId.OPENAI, "https://api.openai.com/v1", "m")),
        ).toList()

        assertEquals(1, events.filterIsInstance<StreamEvent.Failed>().size)
    }

    @Test
    fun `disabling tools removes them from every dialect`() {
        val disabled = config(ProviderId.OPENAI, "https://api.openai.com/v1", "m")
            .copy(toolsEnabled = false)

        val openAiBody = Json.lenient
            .parseToJsonElement(OpenAiProvider(FakeTransport()).buildBody(request(disabled)))
            .jsonObject
        val anthropicBody = Json.lenient
            .parseToJsonElement(AnthropicProvider(FakeTransport()).buildBody(request(disabled)))
            .jsonObject

        assertNull(openAiBody["tools"])
        assertNull(anthropicBody["tools"])
    }

    @Test
    fun `the destination host is derivable for the settings screen`() {
        val config = config(ProviderId.ANTHROPIC, "https://api.anthropic.com", "m")

        assertEquals("api.anthropic.com", config.destinationHost)
        assertEquals(
            "127.0.0.1",
            config.copy(baseUrl = "http://127.0.0.1:11434/v1").destinationHost,
        )
    }
}
