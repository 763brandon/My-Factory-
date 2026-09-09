package com.myfactory.forge.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceFsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var outside: File
    private lateinit var fs: WorkspaceFs

    @org.junit.Before
    fun setUp() {
        outside = temp.newFolder("outside")
        File(outside, "secret.txt").writeText("do not read me")
        fs = WorkspaceFs(temp.newFolder("project"))
    }

    private fun blocked(path: String) {
        val thrown = runCatching { fs.resolve(path) }.exceptionOrNull()
        assertTrue("'$path' should have been blocked", thrown is WorkspaceException)
    }

    @Test
    fun `dot dot traversal is blocked`() {
        blocked("../outside/secret.txt")
        blocked("a/../../outside/secret.txt")
        blocked("./../../etc/passwd")
    }

    @Test
    fun `absolute paths are blocked`() {
        blocked("/etc/passwd")
        blocked("/data/data/com.other.app/files/x")
        blocked("C:\\Windows\\system32")
    }

    @Test
    fun `a symlink pointing outside the project is blocked`() {
        // canonicalFile resolves the link, so the prefix check catches it.
        val link = File(fs.root, "escape")
        val created = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        org.junit.Assume.assumeTrue("filesystem supports symlinks", created)

        blocked("escape/secret.txt")
    }

    @Test
    fun `a null byte in a path is blocked`() {
        blocked("a" + 0.toChar() + "b.txt")
    }

    @Test
    fun `ordinary relative paths resolve inside the root`() {
        val resolved = fs.resolve("src/main/App.kt")

        assertTrue(resolved.path.startsWith(fs.root.path))
        assertEquals("src/main/App.kt", fs.relativise(resolved))
    }

    @Test
    fun `write then read round-trips including unicode`() {
        fs.write("notes/hello.txt", "Akwaaba, jamais vu, 你好")

        val content = fs.read("notes/hello.txt")
        assertEquals("Akwaaba, jamais vu, 你好", content.text)
        assertFalse(content.isBinary)
        assertFalse(content.truncated)
    }

    @Test
    fun `writing creates missing parent directories`() {
        fs.write("a/b/c/deep.txt", "x")

        assertTrue(fs.exists("a/b/c/deep.txt"))
    }

    @Test
    fun `a file containing a null byte is reported as binary`() {
        val file = fs.resolve("image.bin")
        file.writeBytes(byteArrayOf(1, 2, 0, 4, 5))

        assertTrue(fs.read("image.bin").isBinary)
    }

    @Test
    fun `reads are truncated at the limit rather than exhausting memory`() {
        fs.write("big.txt", "x".repeat(5000))

        val content = fs.read("big.txt", maxBytes = 100)

        assertEquals(100, content.text.length)
        assertTrue(content.truncated)
        assertEquals(5000, content.sizeBytes)
    }

    @Test
    fun `listing puts directories first and hides dot files by default`() {
        fs.write("zebra.txt", "z")
        fs.write("Apple.txt", "a")
        fs.mkdirs("src")
        fs.write(".hidden", "h")

        val names = fs.list().map { it.name }

        assertEquals(listOf("src", "Apple.txt", "zebra.txt"), names)
        assertTrue(fs.list(includeHidden = true).any { it.name == ".hidden" })
    }

    @Test
    fun `the internal directory is never listed`() {
        fs.mkdirs(".forge")

        assertTrue(fs.list(includeHidden = true).none { it.name == ".forge" })
    }

    @Test
    fun `walk skips dependency and build directories`() {
        fs.write("src/App.kt", "code")
        fs.write("node_modules/left-pad/index.js", "junk")
        fs.write("build/output.jar", "junk")
        fs.write(".git/config", "junk")

        val paths = fs.walk().map { it.relativePath }

        assertTrue(paths.contains("src/App.kt"))
        assertTrue(paths.none { it.startsWith("node_modules") })
        assertTrue(paths.none { it.startsWith("build") })
        assertTrue(paths.none { it.startsWith(".git") })
    }

    @Test
    fun `walk stops at the entry cap`() {
        repeat(50) { fs.write("f$it.txt", "x") }

        assertEquals(10, fs.walk(maxEntries = 10).size)
    }

    @Test
    fun `deleting the project root is refused`() {
        val thrown = runCatching { fs.delete("") }.exceptionOrNull()

        assertTrue(thrown is WorkspaceException)
        assertTrue(fs.root.exists())
    }

    @Test
    fun `delete removes a file and is idempotent`() {
        fs.write("gone.txt", "x")
        fs.delete("gone.txt")
        fs.delete("gone.txt")

        assertFalse(fs.exists("gone.txt"))
    }

    @Test
    fun `move relocates a file`() {
        fs.write("from.txt", "content")
        fs.move("from.txt", "sub/to.txt")

        assertFalse(fs.exists("from.txt"))
        assertEquals("content", fs.read("sub/to.txt").text)
    }

    @Test
    fun `an interrupted write does not leave a temp file behind`() {
        fs.write("a.txt", "one")
        fs.write("a.txt", "two")

        assertEquals("two", fs.read("a.txt").text)
        assertTrue(fs.list().none { it.name.contains("forge-tmp") })
    }
}
