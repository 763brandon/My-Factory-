package com.myfactory.forge.core.preview

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A loopback-only static file server for the preview pane.
 *
 * Its purpose is the common case where the user has written HTML, CSS and
 * JavaScript and wants to look at it, without needing Node or a rootfs. When
 * the user does run a real dev server the preview points at that instead and
 * this stays off.
 *
 * Deliberately small and deliberately restricted:
 *  - Binds to 127.0.0.1 only, so nothing on the local network can reach it.
 *  - Serves GET and HEAD; there is nothing to POST to.
 *  - Refuses any path that escapes the served directory.
 */
class StaticHttpServer(
    private val rootDir: File,
    private val requestedPort: Int = 0,
    private val threadCount: Int = 4,
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    /**
     * Recreated on every [start]. An executor cannot be restarted after
     * shutdownNow, so holding one for the life of the object would make a
     * stop-then-start cycle bind a socket and then silently drop every
     * request, which looks to the user like the preview hanging.
     */
    private var pool: ExecutorService? = null

    /** The bound port. Valid only while running. */
    var port: Int = -1
        private set

    val isRunning: Boolean get() = running.get()

    fun baseUrl(): String = "http://127.0.0.1:$port/"

    @Synchronized
    fun start(): Int {
        if (running.get()) return port
        val loopback = InetAddress.getByName("127.0.0.1")
        val socket = ServerSocket(requestedPort, BACKLOG, loopback)
        serverSocket = socket
        port = socket.localPort
        val workers = newPool()
        pool = workers
        running.set(true)

        Thread({
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break // stop() closed the socket
                } catch (e: IOException) {
                    continue
                }
                // The pool can be gone if stop() raced this accept.
                runCatching { workers.execute { handle(client) } }
                    .onFailure { runCatching { client.close() } }
            }
        }, "forge-preview-accept").apply { isDaemon = true }.start()

        return port
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = -1
        pool?.shutdownNow()
        pool = null
    }

    private fun newPool(): ExecutorService = Executors.newFixedThreadPool(
        threadCount,
        ThreadFactory { runnable ->
            Thread(runnable, "forge-preview").apply { isDaemon = true }
        },
    )

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = try {
                input.readLine()
            } catch (e: IOException) {
                null
            } ?: return

            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respond(output, 400, "text/plain", "Bad request".toByteArray(), headOnly = false)
                return
            }
            val method = parts[0].uppercase()
            if (method != "GET" && method != "HEAD") {
                respond(output, 405, "text/plain", "Method not allowed".toByteArray(), false)
                return
            }

            // Drain the headers so the client does not see a reset.
            while (true) {
                val line = try {
                    input.readLine()
                } catch (e: IOException) {
                    null
                }
                if (line.isNullOrEmpty()) break
            }

            val target = parts[1].substringBefore('?').substringBefore('#')
            val file = resolve(target)
            val headOnly = method == "HEAD"

            if (file == null) {
                respond(output, 403, "text/plain", "Forbidden".toByteArray(), headOnly)
                return
            }
            if (!file.isFile) {
                respond(output, 404, "text/html", NOT_FOUND_BODY.toByteArray(), headOnly)
                return
            }
            respond(output, 200, contentTypeFor(file.name), file.readBytes(), headOnly)
        }
    }

    /** Returns null for anything outside [rootDir]. */
    private fun resolve(rawTarget: String): File? {
        val decoded = decode(rawTarget).removePrefix("/")
        val candidate = if (decoded.isEmpty()) File(rootDir, "index.html") else File(rootDir, decoded)
        val canonical = try {
            candidate.canonicalFile
        } catch (e: IOException) {
            return null
        }
        val root = rootDir.canonicalFile
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            return null
        }
        // A directory request serves its index, matching every dev server.
        return if (canonical.isDirectory) File(canonical, "index.html") else canonical
    }

    private fun decode(raw: String): String = try {
        java.net.URLDecoder.decode(raw, "UTF-8")
    } catch (e: Exception) {
        raw
    }

    private fun respond(
        output: BufferedOutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean,
    ) {
        val crlf = "" + 13.toChar() + 10.toChar()
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append(crlf)
            append("Content-Type: ").append(contentType).append(crlf)
            append("Content-Length: ").append(body.size).append(crlf)
            append("Connection: close").append(crlf)
            // A preview must never serve a stale build.
            append("Cache-Control: no-store, must-revalidate").append(crlf)
            append("X-Content-Type-Options: nosniff").append(crlf)
            append(crlf)
        }
        runCatching {
            output.write(header.toByteArray(Charsets.UTF_8))
            if (!headOnly) output.write(body)
            output.flush()
        }
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Error"
    }

    companion object {
        private const val BACKLOG = 16
        private const val SOCKET_TIMEOUT_MS = 15_000

        private const val NOT_FOUND_BODY =
            "<!doctype html><meta charset=utf-8><title>Not found</title>" +
                "<body style=\"font-family:system-ui;padding:2rem\">" +
                "<h1>404</h1><p>No such file in this project.</p>"

        private val CONTENT_TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "htm" to "text/html; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "mjs" to "text/javascript; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "map" to "application/json; charset=utf-8",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "ico" to "image/x-icon",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "txt" to "text/plain; charset=utf-8",
            "md" to "text/plain; charset=utf-8",
            "wasm" to "application/wasm",
        )

        fun contentTypeFor(fileName: String): String =
            CONTENT_TYPES[fileName.substringAfterLast('.', "").lowercase()]
                ?: "application/octet-stream"
    }
}
