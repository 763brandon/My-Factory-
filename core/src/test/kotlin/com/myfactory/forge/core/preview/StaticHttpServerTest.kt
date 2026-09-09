package com.myfactory.forge.core.preview

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class StaticHttpServerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var server: StaticHttpServer

    @Before
    fun setUp() {
        root = temp.newFolder("site")
        File(root, "index.html").writeText("<h1>Home</h1>")
        File(root, "app.js").writeText("console.log(1)")
        File(root, "sub").mkdirs()
        File(root, "sub/index.html").writeText("<h1>Sub</h1>")

        server = StaticHttpServer(root)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun request(path: String, method: String = "GET"): Pair<Int, String> {
        val connection = URL(server.baseUrl().trimEnd('/') + path).openConnection()
            as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        return try {
            val status = connection.responseCode
            val body = (if (status < 400) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            status to body
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `serves the index for the root path`() {
        val (status, body) = request("/")

        assertEquals(200, status)
        assertEquals("<h1>Home</h1>", body)
    }

    @Test
    fun `serves a named file`() {
        val (status, body) = request("/app.js")

        assertEquals(200, status)
        assertEquals("console.log(1)", body)
    }

    @Test
    fun `serves a directory index`() {
        val (status, body) = request("/sub/")

        assertEquals(200, status)
        assertEquals("<h1>Sub</h1>", body)
    }

    @Test
    fun `a query string is ignored when resolving the file`() {
        val (status, _) = request("/app.js?v=123")

        assertEquals(200, status)
    }

    @Test
    fun `a missing file returns 404`() {
        assertEquals(404, request("/nope.html").first)
    }

    @Test
    fun `path traversal out of the served directory is refused`() {
        File(temp.root, "secret.txt").writeText("secret")

        assertEquals(403, request("/../secret.txt").first)
        assertEquals(403, request("/%2e%2e/secret.txt").first)
    }

    @Test
    fun `only GET and HEAD are accepted`() {
        assertEquals(405, request("/", method = "POST").first)
        assertEquals(200, request("/", method = "HEAD").first)
    }

    @Test
    fun `it binds to loopback only`() {
        assertTrue(server.baseUrl().startsWith("http://127.0.0.1:"))
        assertTrue(server.isRunning)
    }

    @Test
    fun `stopping releases the port and reports it`() {
        server.stop()

        assertTrue(!server.isRunning)
    }

    @Test
    fun `content types are correct for the common web assets`() {
        assertEquals("text/html; charset=utf-8", StaticHttpServer.contentTypeFor("a.html"))
        assertEquals("text/javascript; charset=utf-8", StaticHttpServer.contentTypeFor("a.js"))
        assertEquals("text/css; charset=utf-8", StaticHttpServer.contentTypeFor("a.css"))
        assertEquals("image/svg+xml", StaticHttpServer.contentTypeFor("a.svg"))
        assertEquals("application/wasm", StaticHttpServer.contentTypeFor("a.wasm"))
        assertEquals("application/octet-stream", StaticHttpServer.contentTypeFor("a.unknown"))
    }
}
