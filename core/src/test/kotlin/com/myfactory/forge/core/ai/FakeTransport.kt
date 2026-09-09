package com.myfactory.forge.core.ai

import com.myfactory.forge.core.ai.net.HttpResponse
import com.myfactory.forge.core.ai.net.HttpTransport
import com.myfactory.forge.core.ai.net.SseEvent
import com.myfactory.forge.core.ai.net.SseParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Replays a recorded SSE body, and records the request that was sent.
 *
 * [chunkSize] lets a test split the response at arbitrary byte boundaries,
 * which is how the split-token cases get covered without a network.
 */
class FakeTransport(
    private val sseBody: String = "",
    private val getResponse: HttpResponse = HttpResponse(200, "{}"),
    private val postResponse: HttpResponse = HttpResponse(200, "{}"),
    private val chunkSize: Int = 64,
    private val failWith: Throwable? = null,
) : HttpTransport {

    var lastUrl: String? = null
        private set
    var lastBody: String? = null
        private set
    var lastHeaders: Map<String, String> = emptyMap()
        private set

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        lastUrl = url
        lastHeaders = headers
        return getResponse
    }

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): HttpResponse {
        lastUrl = url
        lastHeaders = headers
        lastBody = body
        return postResponse
    }

    override fun postSse(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): Flow<SseEvent> = flow {
        lastUrl = url
        lastHeaders = headers
        lastBody = body
        failWith?.let { throw it }

        val parser = SseParser()
        var index = 0
        while (index < sseBody.length) {
            val end = minOf(index + chunkSize, sseBody.length)
            parser.feed(sseBody.substring(index, end)).forEach { emit(it) }
            index = end
        }
        parser.close().forEach { emit(it) }
    }
}

/** Assembles an SSE body from `event:`/`data:` pairs. */
object Sse {
    private val nl: String = 10.toChar().toString()

    fun body(vararg frames: Pair<String?, String>): String = buildString {
        for ((event, data) in frames) {
            if (event != null) append("event: ").append(event).append(nl)
            append("data: ").append(data).append(nl).append(nl)
        }
    }

    fun dataOnly(vararg payloads: String): String = buildString {
        for (payload in payloads) {
            append("data: ").append(payload).append(nl).append(nl)
        }
    }
}
