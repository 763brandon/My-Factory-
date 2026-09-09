package com.myfactory.forge.core.checkpoint

import com.myfactory.forge.core.files.WorkspaceFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CheckpointManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var fs: WorkspaceFs
    private lateinit var manager: CheckpointManager
    private var nextId = 0

    @Before
    fun setUp() {
        fs = WorkspaceFs(temp.newFolder("project"))
        manager = CheckpointManager(
            workspace = fs,
            storageDir = temp.newFolder("snapshots"),
            clock = { 1_700_000_000_000L },
            idGenerator = { "checkpoint-${nextId++}" },
        )
    }

    @Test
    fun `a snapshot restores files that were later modified`() {
        fs.write("src/App.kt", "original")
        val checkpoint = manager.create("p1", "before the agent ran")

        fs.write("src/App.kt", "the agent broke it")
        manager.restore(checkpoint)

        assertEquals("original", fs.read("src/App.kt").text)
    }

    @Test
    fun `restoring removes files created after the snapshot`() {
        fs.write("keep.txt", "keep")
        val checkpoint = manager.create("p1", "clean")

        fs.write("added-later.txt", "junk")
        manager.restore(checkpoint)

        assertTrue(fs.exists("keep.txt"))
        assertFalse("a restore must match the snapshot exactly", fs.exists("added-later.txt"))
    }

    @Test
    fun `restoring brings back a deleted file`() {
        fs.write("deleted.txt", "content")
        val checkpoint = manager.create("p1", "has the file")

        fs.delete("deleted.txt")
        manager.restore(checkpoint)

        assertEquals("content", fs.read("deleted.txt").text)
    }

    @Test
    fun `nested directories survive the round trip`() {
        fs.write("a/b/c/deep.txt", "deep")
        fs.write("a/other.txt", "other")
        val checkpoint = manager.create("p1", "nested")

        fs.delete("a/b/c/deep.txt")
        manager.restore(checkpoint)

        assertEquals("deep", fs.read("a/b/c/deep.txt").text)
        assertEquals("other", fs.read("a/other.txt").text)
    }

    @Test
    fun `metadata reports what was captured`() {
        fs.write("one.txt", "1")
        fs.write("two.txt", "22")

        val checkpoint = manager.create("p1", "two files", automatic = true, sessionId = "s1")

        assertEquals(2, checkpoint.fileCount)
        assertEquals("two files", checkpoint.label)
        assertEquals(1_700_000_000_000L, checkpoint.createdAtMillis)
        assertTrue(checkpoint.automatic)
        assertEquals("s1", checkpoint.sessionId)
        assertTrue(checkpoint.archiveBytes > 0)
    }

    @Test
    fun `dependency directories are excluded from the snapshot`() {
        fs.write("src/App.kt", "code")
        fs.write("node_modules/big/index.js", "junk")

        val checkpoint = manager.create("p1", "excludes node_modules")

        assertEquals(1, checkpoint.fileCount)
    }

    @Test
    fun `a project over the size limit is refused rather than truncated`() {
        fs.write("big.txt", "x".repeat(5000))

        val thrown = runCatching { manager.create("p1", "too big", maxBytes = 1000) }
            .exceptionOrNull()

        assertTrue(thrown is CheckpointManager.CheckpointException)
        assertTrue(thrown!!.message!!.contains("checkpoint limit"))
    }

    @Test
    fun `binary content survives the round trip byte for byte`() {
        val bytes = ByteArray(256) { it.toByte() }
        fs.resolve("image.bin").writeBytes(bytes)
        val checkpoint = manager.create("p1", "with binary")

        fs.resolve("image.bin").writeBytes(ByteArray(10))
        manager.restore(checkpoint)

        assertTrue(bytes.contentEquals(fs.resolve("image.bin").readBytes()))
    }

    @Test
    fun `deleting a checkpoint removes its archive`() {
        fs.write("a.txt", "x")
        val checkpoint = manager.create("p1", "temp")
        assertTrue(manager.archiveExists(checkpoint))

        manager.delete(checkpoint)

        assertFalse(manager.archiveExists(checkpoint))
    }

    @Test
    fun `restoring a checkpoint whose archive is gone fails clearly`() {
        fs.write("a.txt", "x")
        val checkpoint = manager.create("p1", "temp")
        manager.delete(checkpoint)

        val thrown = runCatching { manager.restore(checkpoint) }.exceptionOrNull()

        assertTrue(thrown is CheckpointManager.CheckpointException)
        assertTrue(thrown!!.message!!.contains("missing from storage"))
    }

    @Test
    fun `an empty project snapshots and restores without error`() {
        val checkpoint = manager.create("p1", "empty")
        fs.write("added.txt", "x")

        manager.restore(checkpoint)

        assertEquals(0, checkpoint.fileCount)
        assertFalse(fs.exists("added.txt"))
    }

    @Test
    fun `total storage reflects every archive`() {
        fs.write("a.txt", "content")
        manager.create("p1", "one")
        manager.create("p1", "two")

        assertTrue(manager.totalStorageBytes() > 0)
    }
}
