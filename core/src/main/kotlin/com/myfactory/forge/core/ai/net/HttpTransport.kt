package com.myfactory.forge.core.ai.net

import kotlinx.coroutines.flow.Flow

data class HttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * The seam between the provider adapters and the network.
 *
 * Adapters depend on this rather than on OkHttp so that every adapter can be
 * unit-tested against a recorded byte stream, including the awkward cases:
 * a stream that stops mid-token, a 429 with Retry-After, or a proxy that
 * injects an HTML error page where JSON was promised.
 */
interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse

    suspend fun postJson(url: String, headers: Map<String, String>, body: String): HttpResponse

    /**
     * Opens a server-sent-events stream. The flow must be cold, must emit on a
     * background dispatcher, and must close the underlying connection when the
     * collector goes away.
     */
    fun postSse(url: String, headers: Map<String, String>, body: String): Flow<SseEvent>
}

/** One dispatched SSE message. */
data class SseEvent(
    val event: String?,
    val data: String,
    val id: String? = null,
)
