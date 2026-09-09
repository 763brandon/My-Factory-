package com.myfactory.forge.core.ai.providers

import com.myfactory.forge.core.ai.AiError
import com.myfactory.forge.core.ai.AiProvider
import com.myfactory.forge.core.ai.ChatMessage
import com.myfactory.forge.core.ai.ChatRequest
import com.myfactory.forge.core.ai.ModelInfo
import com.myfactory.forge.core.ai.ProviderConfig
import com.myfactory.forge.core.ai.ProviderId
import com.myfactory.forge.core.ai.Role
import com.myfactory.forge.core.ai.StopReason
import com.myfactory.forge.core.ai.StreamEvent
import com.myfactory.forge.core.ai.TokenUsage
import com.myfactory.forge.core.ai.ToolCall
import com.myfactory.forge.core.ai.net.HttpTransport
import com.myfactory.forge.core.util.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Anthropic Messages API adapter.
 *
 * Wire notes that shape this code:
 *  - Content is an array of typed blocks, not a string.
 *  - `tool_result` blocks belong to a *user* message, not a separate role.
 *  - Tool arguments stream as `input_json_delta.partial_json` fragments that
 *    must be concatenated before they parse.
 */
class AnthropicProvider(
    private val transport: HttpTransport,
) : AiProvider {

    override val id: ProviderId = ProviderId.ANTHROPIC

    override fun defaultBaseUrl(): String = "https://api.anthropic.com"

    override fun fallbackModels(): List<ModelInfo> = listOf(
        ModelInfo("claude-opus-5", "Claude Opus 5"),
        ModelInfo("claude-sonnet-5", "Claude Sonnet 5"),
        ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5"),
    )

    override suspend fun listModels(
        config: ProviderConfig,
        apiKey: String?,
    ): Result<List<ModelInfo>> = runCatching {
        val response = transport.get("${config.baseUrl.trimEnd('/')}/v1/models", headers(apiKey, config))
        if (!response.isSuccess) {
            throw AiError.Server(response.status, "Model list failed: ${response.body.take(200)}")
        }
        Json.lenient.parseToJsonElement(response.body).jsonObject["data"]
            ?.jsonArray
            ?.mapNotNull { entry ->
                val obj = entry.jsonObject
                val modelId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ModelInfo(
                    id = modelId,
                    displayName = obj["display_name"]?.jsonPrimitive?.contentOrNull ?: modelId,
                )
            }
            .orEmpty()
            .ifEmpty { fallbackModels() }
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val url = "${request.config.baseUrl.trimEnd('/')}/v1/messages"
        val body = buildBody(request)

        // Tool arguments arrive in fragments keyed by content-block index.
        val openBlocks = mutableMapOf<Int, PartialToolCall>()
        var stopReason = StopReason.END_TURN
        var completed = false

        try {
            transport.postSse(url, headers(request.apiKey, request.config), body).collect { sse ->
                if (sse.data.isBlank()) return@collect
                val payload = runCatching { Json.lenient.parseToJsonElement(sse.data).jsonObject }
                    .getOrElse { throw AiError.Parse("Malformed event from Anthropic: ${sse.data.take(200)}", it) }

                when (sse.event ?: payload["type"]?.jsonPrimitive?.contentOrNull) {
                    "content_block_start" -> {
                        val index = payload["index"]?.jsonPrimitive?.int ?: return@collect
                        val block = payload["content_block"]?.jsonObject ?: return@collect
                        if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                            val callId = block["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val name = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            openBlocks[index] = PartialToolCall(callId, name)
                            emit(StreamEvent.ToolCallStarted(callId, name))
                        }
                    }

                    "content_block_delta" -> {
                        val index = payload["index"]?.jsonPrimitive?.int ?: return@collect
                        val delta = payload["delta"]?.jsonObject ?: return@collect
                        when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                            "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { emit(StreamEvent.TextDelta(it)) }

                            "input_json_delta" -> {
                                val fragment = delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val partial = openBlocks[index] ?: return@collect
                                partial.arguments.append(fragment)
                                if (fragment.isNotEmpty()) {
                                    emit(StreamEvent.ToolCallDelta(partial.id, fragment))
                                }
                            }
                        }
                    }

                    "content_block_stop" -> {
                        val index = payload["index"]?.jsonPrimitive?.int ?: return@collect
                        openBlocks.remove(index)?.let { emit(StreamEvent.ToolCallCompleted(it.build())) }
                    }

                    "message_delta" -> {
                        payload["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                            ?.let { stopReason = mapStopReason(it) }
                        payload["usage"]?.jsonObject?.let { usage ->
                            emit(
                                StreamEvent.Usage(
                                    TokenUsage(
                                        inputTokens = usage["input_tokens"]?.jsonPrimitive?.int ?: 0,
                                        outputTokens = usage["output_tokens"]?.jsonPrimitive?.int ?: 0,
                                    ),
                                ),
                            )
                        }
                    }

                    "error" -> {
                        val message = payload["error"]?.jsonObject?.get("message")
                            ?.jsonPrimitive?.contentOrNull ?: "Unknown provider error."
                        emit(StreamEvent.Failed(AiError.Server(200, message)))
                        completed = true
                    }

                    "message_stop" -> {
                        // Anything still open means the stream was truncated;
                        // emit what we have rather than losing the call.
                        openBlocks.values.forEach { emit(StreamEvent.ToolCallCompleted(it.build())) }
                        openBlocks.clear()
                        emit(StreamEvent.Completed(stopReason))
                        completed = true
                    }
                }
            }
            if (!completed) {
                openBlocks.values.forEach { emit(StreamEvent.ToolCallCompleted(it.build())) }
                emit(StreamEvent.Completed(stopReason))
            }
        } catch (e: AiError) {
            emit(StreamEvent.Failed(e))
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emit(StreamEvent.Failed(AiError.Network(e.message ?: "Stream failed.", e)))
        }
    }

    private fun headers(apiKey: String?, config: ProviderConfig): Map<String, String> = buildMap {
        put("content-type", "application/json")
        put("anthropic-version", ANTHROPIC_VERSION)
        if (!apiKey.isNullOrBlank()) put("x-api-key", apiKey)
        putAll(config.extraHeaders)
    }

    internal fun buildBody(request: ChatRequest): String {
        val config = request.config
        return buildJsonObject {
            put("model", config.model)
            put("max_tokens", config.maxOutputTokens)
            put("stream", true)
            config.temperature?.let { put("temperature", it) }
            request.system?.takeIf { it.isNotBlank() }?.let { put("system", it) }

            putJsonArray("messages") {
                encodeMessages(request.messages).forEach { add(it) }
            }

            if (config.toolsEnabled && request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("input_schema", tool.parametersSchema)
                            },
                        )
                    }
                }
            }
        }.toString()
    }

    /**
     * Anthropic has no `tool` role: results are user messages carrying
     * `tool_result` blocks. Consecutive tool-result messages are merged so the
     * alternation the API requires is preserved.
     */
    private fun encodeMessages(messages: List<ChatMessage>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        for (message in messages) {
            when (message.role) {
                Role.SYSTEM -> Unit // hoisted into the top-level "system" field
                Role.USER -> out += buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray { add(textBlock(message.text.orEmpty())) })
                }

                Role.ASSISTANT -> out += buildJsonObject {
                    put("role", "assistant")
                    put(
                        "content",
                        buildJsonArray {
                            message.text?.takeIf { it.isNotBlank() }?.let { add(textBlock(it)) }
                            message.toolCalls.forEach { call ->
                                add(
                                    buildJsonObject {
                                        put("type", "tool_use")
                                        put("id", call.id)
                                        put("name", call.name)
                                        put("input", parseArgumentsOrEmpty(call.argumentsJson))
                                    },
                                )
                            }
                        },
                    )
                }

                Role.TOOL -> {
                    val blocks = buildJsonArray {
                        message.toolResults.forEach { result ->
                            add(
                                buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", result.callId)
                                    put("content", result.content)
                                    if (result.isError) put("is_error", true)
                                },
                            )
                        }
                    }
                    val previous = out.lastOrNull()
                    if (previous != null && previous["role"]?.jsonPrimitive?.contentOrNull == "user" &&
                        previous["content"]?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("type")?.jsonPrimitive?.contentOrNull == "tool_result"
                    ) {
                        out[out.lastIndex] = buildJsonObject {
                            put("role", "user")
                            put("content", JsonArray(previous["content"]!!.jsonArray + blocks))
                        }
                    } else {
                        out += buildJsonObject {
                            put("role", "user")
                            put("content", blocks)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun textBlock(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun mapStopReason(raw: String): StopReason = when (raw) {
        "tool_use" -> StopReason.TOOL_USE
        "max_tokens" -> StopReason.MAX_TOKENS
        "stop_sequence" -> StopReason.STOPPED
        else -> StopReason.END_TURN
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

/** Accumulator for a tool call that is still streaming. */
internal class PartialToolCall(val id: String, val name: String) {
    val arguments = StringBuilder()
    fun build(): ToolCall = ToolCall(id, name, arguments.toString().ifBlank { "{}" })
}

/**
 * Tool arguments have to survive being echoed back to the provider even when
 * the model produced something that does not parse. An empty object is a
 * better outcome than a crashed session.
 */
internal fun parseArgumentsOrEmpty(raw: String): JsonObject =
    runCatching { Json.lenient.parseToJsonElement(raw).jsonObject }
        .getOrElse { JsonObject(emptyMap()) }

internal fun JsonObject.optDouble(key: String): Double? =
    this[key]?.jsonPrimitive?.doubleOrNull
