package com.myfactory.forge.core.ai.providers

import com.myfactory.forge.core.ai.AiError
import com.myfactory.forge.core.ai.AiProvider
import com.myfactory.forge.core.ai.ChatMessage
import com.myfactory.forge.core.ai.ChatRequest
import com.myfactory.forge.core.ai.JsonSchemas
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Google Gemini adapter, using `streamGenerateContent` with `alt=sse` so the
 * shared [com.myfactory.forge.core.ai.net.SseParser] can read it.
 *
 * Gemini differs from the other two dialects in three ways that matter here:
 *  - The assistant role is called "model".
 *  - Function calls arrive complete in one part, never in fragments, so a
 *    started/delta/completed triple is synthesised to keep the normalised
 *    stream uniform for the agent loop.
 *  - Function results are matched by function *name*, not by a call id, so the
 *    adapter keeps its own id map.
 */
class GeminiProvider(
    private val transport: HttpTransport,
) : AiProvider {

    override val id: ProviderId = ProviderId.GEMINI

    override fun defaultBaseUrl(): String = "https://generativelanguage.googleapis.com/v1beta"

    override fun fallbackModels(): List<ModelInfo> = listOf(
        ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro"),
        ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash"),
        ModelInfo("gemini-2.0-flash", "Gemini 2.0 Flash"),
    )

    override suspend fun listModels(
        config: ProviderConfig,
        apiKey: String?,
    ): Result<List<ModelInfo>> = runCatching {
        val response = transport.get(
            "${config.baseUrl.trimEnd('/')}/models",
            headers(apiKey, config),
        )
        if (!response.isSuccess) {
            throw AiError.Server(response.status, "Model list failed: ${response.body.take(200)}")
        }
        Json.lenient.parseToJsonElement(response.body).jsonObject["models"]
            ?.jsonArray
            ?.mapNotNull { entry ->
                val obj = entry.jsonObject
                // Names come back as "models/gemini-2.5-pro".
                val raw = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val supported = obj["supportedGenerationMethods"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()
                if (supported.isNotEmpty() && "generateContent" !in supported) return@mapNotNull null
                ModelInfo(
                    id = raw.removePrefix("models/"),
                    displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull
                        ?: raw.removePrefix("models/"),
                    contextWindow = obj["inputTokenLimit"]?.jsonPrimitive?.intOrNull,
                )
            }
            .orEmpty()
            .ifEmpty { fallbackModels() }
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val base = request.config.baseUrl.trimEnd('/')
        val url = "$base/models/${request.config.model}:streamGenerateContent?alt=sse"
        val body = buildBody(request)

        var stopReason = StopReason.END_TURN
        var sawFunctionCall = false
        var callCounter = 0

        try {
            transport.postSse(url, headers(request.apiKey, request.config), body).collect { sse ->
                val data = sse.data.trim()
                if (data.isEmpty()) return@collect

                val payload = runCatching { Json.lenient.parseToJsonElement(data).jsonObject }
                    .getOrElse { throw AiError.Parse("Malformed chunk from Gemini: ${data.take(200)}", it) }

                payload["error"]?.jsonObject?.let { error ->
                    val message = error["message"]?.jsonPrimitive?.contentOrNull
                        ?: "Gemini reported an error mid-stream."
                    emit(StreamEvent.Failed(AiError.Server(200, message)))
                    return@collect
                }

                payload["usageMetadata"]?.jsonObject?.let { usage ->
                    emit(
                        StreamEvent.Usage(
                            TokenUsage(
                                inputTokens = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                                outputTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                            ),
                        ),
                    )
                }

                val candidate = payload["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return@collect

                candidate["finishReason"]?.jsonPrimitive?.contentOrNull
                    ?.let { stopReason = mapStopReason(it) }

                val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: return@collect
                for (part in parts) {
                    val obj = part.jsonObject
                    obj["text"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { emit(StreamEvent.TextDelta(it)) }

                    obj["functionCall"]?.jsonObject?.let { fn ->
                        val name = fn["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val args = (fn["args"] as? JsonObject)?.toString() ?: "{}"
                        val callId = "gemini_call_${callCounter++}_$name"
                        sawFunctionCall = true
                        // Synthesised triple: Gemini sends the call whole.
                        emit(StreamEvent.ToolCallStarted(callId, name))
                        emit(StreamEvent.ToolCallDelta(callId, args))
                        emit(StreamEvent.ToolCallCompleted(ToolCall(callId, name, args)))
                    }
                }
            }
            emit(StreamEvent.Completed(if (sawFunctionCall) StopReason.TOOL_USE else stopReason))
        } catch (e: AiError) {
            emit(StreamEvent.Failed(e))
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emit(StreamEvent.Failed(AiError.Network(e.message ?: "Stream failed.", e)))
        }
    }

    private fun headers(apiKey: String?, config: ProviderConfig): Map<String, String> = buildMap {
        put("content-type", "application/json")
        // The header form keeps the key out of the URL, and so out of any
        // proxy access log the request passes through.
        if (!apiKey.isNullOrBlank()) put("x-goog-api-key", apiKey)
        putAll(config.extraHeaders)
    }

    internal fun buildBody(request: ChatRequest): String {
        val config = request.config
        return buildJsonObject {
            putJsonArray("contents") {
                encodeContents(request.messages).forEach { add(it) }
            }

            request.system?.takeIf { it.isNotBlank() }?.let { system ->
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", system) })
                    }
                }
            }

            putJsonObject("generationConfig") {
                put("maxOutputTokens", config.maxOutputTokens)
                config.temperature?.let { put("temperature", it) }
            }

            if (config.toolsEnabled && request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    add(
                        buildJsonObject {
                            putJsonArray("functionDeclarations") {
                                request.tools.forEach { tool ->
                                    add(
                                        buildJsonObject {
                                            put("name", tool.name)
                                            put("description", tool.description)
                                            put(
                                                "parameters",
                                                JsonSchemas.sanitiseForGemini(tool.parametersSchema),
                                            )
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }.toString()
    }

    private fun encodeContents(messages: List<ChatMessage>): List<JsonObject> = buildList {
        for (message in messages) {
            when (message.role) {
                Role.SYSTEM -> Unit // hoisted into systemInstruction

                Role.USER -> add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", message.text.orEmpty()) })
                        }
                    },
                )

                Role.ASSISTANT -> add(
                    buildJsonObject {
                        put("role", "model")
                        putJsonArray("parts") {
                            message.text?.takeIf { it.isNotBlank() }?.let {
                                add(buildJsonObject { put("text", it) })
                            }
                            message.toolCalls.forEach { call ->
                                add(
                                    buildJsonObject {
                                        putJsonObject("functionCall") {
                                            put("name", call.name)
                                            put("args", parseArgumentsOrEmpty(call.argumentsJson))
                                        }
                                    },
                                )
                            }
                        }
                    },
                )

                // Gemini expects function results as a user turn, correlated
                // by function name rather than by call id.
                Role.TOOL -> add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            message.toolResults.forEach { result ->
                                add(
                                    buildJsonObject {
                                        putJsonObject("functionResponse") {
                                            put("name", result.name)
                                            putJsonObject("response") {
                                                if (result.isError) {
                                                    put("error", result.content)
                                                } else {
                                                    put("result", result.content)
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    private fun mapStopReason(raw: String): StopReason = when (raw) {
        "MAX_TOKENS" -> StopReason.MAX_TOKENS
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> StopReason.STOPPED
        else -> StopReason.END_TURN
    }
}
