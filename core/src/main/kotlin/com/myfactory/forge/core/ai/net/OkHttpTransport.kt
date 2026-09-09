package com.myfactory.forge.core.ai.net

import com.myfactory.forge.core.ai.AiError
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The production [HttpTransport].
 *
 * OkHttp on API 24 uses the platform TLS stack, which speaks TLS 1.2 on
 * Android 7 and up. That is sufficient for every provider here, so no crypto
 * provider is bundled. See docs/SECURITY.md for the Conscrypt note.
 */
class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.get().build()
        return client.newCall(request).await()
    }

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): HttpResponse {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.post(body.toRequestBody(JSON)).build()
        return client.newCall(request).await()
    }

    override fun postSse(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): Flow<SseEvent> = callbackFlow {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("Accept", "text/event-stream")
            header("Cache-Control", "no-store")
        }.post(body.toRequestBody(JSON)).build()

        val call = streamingClient().newCall(request)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    throw errorForStatus(response.code, text, response.header("Retry-After"))
                }
                val source = response.body?.source()
                    ?: throw AiError.Network("The server accepted the request but sent no body.")

                val parser = SseParser()
                val buffer = ByteArray(READ_BUFFER)
                while (!source.exhausted()) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    val chunk = String(buffer, 0, read, Charsets.UTF_8)
                    parser.feed(chunk).forEach { trySend(it) }
                }
                parser.close().forEach { trySend(it) }
            }
        } catch (e: InterruptedIOException) {
            // Raised when the collector cancels; not an application failure.
            throw AiError.Cancelled()
        } catch (e: IOException) {
            throw AiError.Network(e.message ?: "Network failure while streaming.", e)
        }

        awaitClose { runCatching { call.cancel() } }
    }.flowOn(Dispatchers.IO)

    /**
     * A clone of the shared client with the read timeout removed. A long model
     * turn can idle for minutes between tokens and must not be torn down.
     */
    private fun streamingClient(): OkHttpClient =
        client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val READ_BUFFER = 8 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun errorForStatus(status: Int, body: String, retryAfter: String?): AiError = when (status) {
            401, 403 -> AiError.Auth(
                "The endpoint rejected the API key (HTTP $status). ${summarise(body)}",
            )
            429 -> AiError.RateLimited(
                "Rate limited by the provider. ${summarise(body)}",
                retryAfter?.toLongOrNull()?.times(1000L),
            )
            in 400..499 -> AiError.BadRequest(status, "HTTP $status. ${summarise(body)}")
            else -> AiError.Server(status, "HTTP $status. ${summarise(body)}")
        }

        /** Keeps an error readable when a gateway returns a page of HTML. */
        private fun summarise(body: String): String {
            val trimmed = body.trim().replace(Regex("\\s+"), " ")
            return if (trimmed.length <= 400) trimmed else trimmed.take(400) + "..."
        }
    }
}

private suspend fun Call.await(): HttpResponse = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeSafely(AiError.Network(e.message ?: "Network failure.", e))
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                val text = runCatching { it.body?.string() }.getOrNull().orEmpty()
                val headers = it.headers.names().associateWith { name -> it.header(name).orEmpty() }
                cont.resume(HttpResponse(it.code, text, headers))
            }
        }
    })
}

private fun CancellableContinuation<HttpResponse>.resumeSafely(error: Throwable) {
    if (isActive) resumeWithException(error)
}
