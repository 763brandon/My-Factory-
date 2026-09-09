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
import com.myfactory.forge.core.ai.net.HttpTransport
import com.myfactory.forge.core.util.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Adapter for the OpenAI chat-completions dialect.
 *
 * This one class covers four of the five providers this app ships, because
 * OpenRouter, Ollama, LM Studio, vLLM and most corporate gateways all speak
 * it. The differences are the base URL, the auth header and a couple of extra
 * headers, so those are constructor parameters rather than subclasses.
 *
 * Wire notes:
 *  - Tool calls stream as `delta.tool_calls[]` entries keyed by `index`; only
 *    the first fragment carries `id` and `function.name`.
 *  - The stream terminates with a literal `data: [DONE]` sentinel rather than
 *    an event type.
 */
open class OpenAiProvider(
    private val transport: HttpTransport,
    override val id: ProviderId = ProviderId.OPENAI,
    private val defaultBase: String = "https://api.openai.com/v1",
    private val staticHeaders: Map<String, String> = emptyMap(),
    private val apiKeyRequired: Boolean = true,
) : AiProvider {

    override fun defaultBaseUrl(): String = defaultBase

    override fun requiresApiKey(): Boolean = apiKeyRequired

    override fun fallbackModels(): List<ModelInfo> = when (id) {
        ProviderId.OPENROUTER -> listOf(
            ModelInfo("anthropic/claude-sonnet-4.5", "Claude Sonnet 4.5 (OpenRouter)"),
            ModelInfo("openai/gpt-4.1", "GPT-4.1 (OpenRouter)"),
            ModelInfo("google/gemini-2.5-pro", "Gemini 2.5 Pro (OpenRouter)"),
        )
        ProviderId.OPENAI_COMPATIBLE -> emptyList()
        else -> listOf(
            ModelInfo("gpt-4.1", "GPT-4.1"),
            ModelInfo("gpt-4.1-mini", "GPT-4.1 mini"),
            ModelInfo("o4-mini", "o4-mini"),
        )
    }

    override suspend fun listModels(
        config: ProviderConfig,
        apiKey: String?,
    ): Result<List<ModelInfo>> = runCatching {
        val response = transport.get("${config.baseUrl.trimEnd('/')}/models", headers(apiKey, config))
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
                    displayName = obj["name"]?.jsonPrimitive?.contentOrNull ?: modelId,
                    contextWindow = obj["context_length"]?.jsonPrimitive?.intOrNull,
                )
            }
            ?.sortedBy { it.id }
            .orEmpty()
            .ifEmpty { fallbackModels() }
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val url = "${request.config.baseUrl.trimEnd('/')}/chat/completions"
        val body = buildBody(request)

        val partials = mutableMapOf<Int, PartialToolCall>()
        var stopReason = StopReason.END_TURN
        var completed = false

        try {
            transport.postSse(url, headers(request.apiKey, request.config), body).collect { sse ->
                val data = sse.data.trim()
                if (data.isEmpty()) return@collect
                if (data == DONE) {
                    flushPartials(partials)
                    emit(StreamEvent.Completed(stopReason))
                    completed = true
                    return@collect
                }

                val payload = runCatching { Json.lenient.parseToJsonElement(data).jsonObject }
                    .getOrElse { throw AiError.Parse("Malformed chunk: ${data.take(200)}", it) }

                payload["error"]?.jsonObject?.let { error ->
                    val message = error["message"]?.jsonPrimitive?.contentOrNull
                        ?: "The endpoint reported an error mid-stream."
                    emit(StreamEvent.Failed(AiError.Server(200, message)))
                    completed = true
                    return@collect
                }

                payload["usage"]?.jsonObject?.let { usage ->
                    emit(
                        StreamEvent.Usage(
                            TokenUsage(
                                inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                                outputTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                            ),
                        ),
                    )
                }

                val choice = payload["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@collect
                choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                    ?.let { stopReason = mapStopReason(it) }

                val delta = choice["delta"]?.jsonObject ?: return@collect

                delta["content"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emit(StreamEvent.TextDelta(it)) }

                delta["tool_calls"]?.jsonArray?.forEach { entry ->
                    val call = entry.jsonObject
                    val index = call["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val function = call["function"]?.jsonObject
                    val existing = partials[index]

                    if (existing == null) {
                        val callId = call["id"]?.jsonPrimitive?.contentOrNull
                            ?: "call_${index}_${System.nanoTime()}"
                        val name = function?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val fresh = PartialToolCall(callId, name)
                        partials[index] = fresh
                        emit(StreamEvent.ToolCallStarted(callId, name))
                        function?.get("arguments")?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }
                            ?.let {
                                fresh.arguments.append(it)
                                emit(StreamEvent.ToolCallDelta(fresh.id, it))
                            }
                    } else {
                        function?.get("arguments")?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }
                            ?.let {
                                existing.arguments.append(it)
                                emit(StreamEvent.ToolCallDelta(existing.id, it))
                            }
                    }
                }
            }
            if (!completed) {
                flushPartials(partials)
                emit(StreamEvent.Completed(stopReason))
            }
        } catch (e: AiError) {
            emit(StreamEvent.Failed(e))
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emit(StreamEvent.Failed(AiError.Network(e.message ?: "Stream failed.", e)))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamEvent>.flushPartials(
        partials: MutableMap<Int, PartialToolCall>,
    ) {
        partials.toSortedMap().values.forEach { emit(StreamEvent.ToolCallCompleted(it.build())) }
        partials.clear()
    }

    protected open fun headers(apiKey: String?, config: ProviderConfig): Map<String, String> =
        buildMap {
            put("content-type", "application/json")
            if (!apiKey.isNullOrBlank()) put("Authorization", "Bearer $apiKey")
            putAll(staticHeaders)
            putAll(config.extraHeaders)
        }

    internal fun buildBody(request: ChatRequest): String {
        val config = request.config
        return buildJsonObject {
            put("model", config.model)
            put("stream", true)
            put("max_tokens", config.maxOutputTokens)
            config.temperature?.let { put("temperature", it) }

            // Ask for a usage block on the final chunk. Servers that do not
            // know the option ignore it.
            putJsonObject("stream_options") { put("include_usage", true) }

            putJsonArray("messages") {
                request.system?.takeIf { it.isNotBlank() }?.let { system ->
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", system)
                        },
                    )
                }
                encodeMessages(request.messages).forEach { add(it) }
            }

            if (config.toolsEnabled && request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.parametersSchema)
                                }
                            },
                        )
                    }
                }
                put("tool_choice", "auto")
            }
        }.toString()
    }

    private fun encodeMessages(messages: List<ChatMessage>): List<JsonObject> = buildList {
        for (message in messages) {
            when (message.role) {
                Role.SYSTEM -> add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", message.text.orEmpty())
                    },
                )

                Role.USER -> add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", message.text.orEmpty())
                    },
                )

                Role.ASSISTANT -> add(
                    buildJsonObject {
                        put("role", "assistant")
                        // A null content with tool calls is the documented
                        // shape; some gateways reject an empty string here.
                        if (!message.text.isNullOrBlank()) put("content", message.text)
                        if (message.toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                message.toolCalls.forEach { call ->
                                    add(
                                        buildJsonObject {
                                            put("id", call.id)
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", call.name)
                                                put("arguments", call.argumentsJson.ifBlank { "{}" })
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )

                // Each result is its own message, unlike Anthropic's batching.
                Role.TOOL -> message.toolResults.forEach { result ->
                    add(
                        buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", result.callId)
                            put("name", result.name)
                            put("content", result.content)
                        },
                    )
                }
            }
        }
    }

    private fun mapStopReason(raw: String): StopReason = when (raw) {
        "tool_calls", "function_call" -> StopReason.TOOL_USE
        "length" -> StopReason.MAX_TOKENS
        "content_filter" -> StopReason.STOPPED
        else -> StopReason.END_TURN
    }

    private companion object {
        const val DONE = "[DONE]"
    }
}
