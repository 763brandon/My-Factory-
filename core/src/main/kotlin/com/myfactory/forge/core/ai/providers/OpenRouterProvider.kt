package com.myfactory.forge.core.ai.providers

import com.myfactory.forge.core.ai.ProviderId
import com.myfactory.forge.core.ai.net.HttpTransport

/**
 * OpenRouter speaks the OpenAI dialect, so this only pins the base URL and the
 * two attribution headers OpenRouter asks integrators to send. They identify
 * the client, not the user.
 */
class OpenRouterProvider(transport: HttpTransport) : OpenAiProvider(
    transport = transport,
    id = ProviderId.OPENROUTER,
    defaultBase = "https://openrouter.ai/api/v1",
    staticHeaders = mapOf(
        "HTTP-Referer" to "https://github.com/763brandon/My-Factory-",
        "X-Title" to "Forge",
    ),
    apiKeyRequired = true,
)

/**
 * Any server exposing `/chat/completions`: Ollama, LM Studio, llama.cpp,
 * vLLM, or a self-hosted gateway. The base URL comes from the user and the key
 * is optional, because a local model does not have one.
 */
class OpenAiCompatibleProvider(transport: HttpTransport) : OpenAiProvider(
    transport = transport,
    id = ProviderId.OPENAI_COMPATIBLE,
    defaultBase = "http://127.0.0.1:11434/v1",
    apiKeyRequired = false,
)
