package com.myfactory.forge.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** The provider families this app knows how to talk to. */
enum class ProviderId(val displayName: String) {
    ANTHROPIC("Anthropic"),
    OPENAI("OpenAI"),
    GEMINI("Google Gemini"),
    OPENROUTER("OpenRouter"),

    /**
     * Any server that speaks the OpenAI chat-completions dialect: Ollama,
     * LM Studio, llama.cpp, vLLM, a corporate gateway. The user supplies the
     * base URL; the API key is optional.
     */
    OPENAI_COMPATIBLE("OpenAI-compatible endpoint"),
    ;

    companion object {
        fun fromNameOrNull(raw: String): ProviderId? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * A configured endpoint. Note what is *not* here: the API key. Only
 * [apiKeyAlias] is persisted; the secret itself lives in the platform keystore
 * and is fetched at call time.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val providerId: ProviderId,
    val displayName: String,
    val baseUrl: String,
    val model: String,
    val apiKeyAlias: String? = null,
    val maxOutputTokens: Int = 4096,
    val temperature: Double? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Turns off tool use for models that do not support it. */
    val toolsEnabled: Boolean = true,
) {
    /** Shown in the UI so the user always knows where their code is going. */
    val destinationHost: String
        get() = runCatching {
            baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')
        }.getOrDefault(baseUrl)
}

@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String = id,
    val contextWindow: Int? = null,
    val supportsTools: Boolean = true,
)

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/** A single function-call request emitted by a model. */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    /** Raw JSON object text. Kept as a string because it arrives in fragments. */
    val argumentsJson: String,
)

/** The answer we hand back for a [ToolCall]. */
@Serializable
data class ToolResult(
    val callId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false,
)

/**
 * One entry in the conversation, normalised across every provider. Adapters
 * translate this into and out of each vendor's own shape.
 */
@Serializable
data class ChatMessage(
    val role: Role,
    val text: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResults: List<ToolResult> = emptyList(),
) {
    companion object {
        fun user(text: String) = ChatMessage(Role.USER, text)
        fun assistant(text: String?, calls: List<ToolCall> = emptyList()) =
            ChatMessage(Role.ASSISTANT, text, toolCalls = calls)
        fun toolResults(results: List<ToolResult>) =
            ChatMessage(Role.TOOL, toolResults = results)
    }
}

/** A tool as advertised to the model. [parametersSchema] is JSON Schema. */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: JsonObject,
)

data class ChatRequest(
    val config: ProviderConfig,
    val apiKey: String?,
    val system: String?,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
)

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, STOPPED, ERROR }

data class TokenUsage(val inputTokens: Int = 0, val outputTokens: Int = 0)

/**
 * The normalised stream. Every adapter emits this same sequence regardless of
 * whether the wire format delivered tool arguments in fragments (OpenAI,
 * Anthropic) or all at once (Gemini).
 */
sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ToolCallStarted(val id: String, val name: String) : StreamEvent
    data class ToolCallDelta(val id: String, val argumentsDelta: String) : StreamEvent
    data class ToolCallCompleted(val call: ToolCall) : StreamEvent
    data class Usage(val usage: TokenUsage) : StreamEvent
    data class Completed(val stopReason: StopReason) : StreamEvent
    data class Failed(val error: AiError) : StreamEvent
}

/**
 * Errors are a closed set so the UI can react correctly - offer a retry for
 * [RateLimited], send the user to Settings for [Auth], and so on.
 */
sealed class AiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(message: String, cause: Throwable? = null) : AiError(message, cause)
    class Auth(message: String) : AiError(message)
    class RateLimited(message: String, val retryAfterMillis: Long?) : AiError(message)
    class Server(val status: Int, message: String) : AiError(message)
    class BadRequest(val status: Int, message: String) : AiError(message)
    class Parse(message: String, cause: Throwable? = null) : AiError(message, cause)
    class Unsupported(message: String) : AiError(message)
    class Cancelled : AiError("Request cancelled")

    /** Whether a plain retry has any chance of behaving differently. */
    val isRetryable: Boolean
        get() = this is Network || this is RateLimited || (this is Server && status >= 500)
}
