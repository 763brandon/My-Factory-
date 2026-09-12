package com.myfactory.forge.core.diff

import com.myfactory.forge.core.checkpoint.CheckpointManager
import com.myfactory.forge.core.files.WorkspaceFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Reverting individual hunks is the operation most able to corrupt a file, so
 * it gets the most direct tests.
 */
class WorkspaceDiffTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val nl: String = 10.toChar().toString()

    private lateinit var fs: WorkspaceFs
    private lateinit var checkpoints: CheckpointManager
    private lateinit var storage: File
    private var counter = 0

    @Before
    fun setUp() {
        fs = WorkspaceFs(temp.newFolder("project"))
        storage = temp.newFolder("snapshots")
        checkpoints = CheckpointManager(
            workspace = fs,
            storageDir = storage,
            idGenerator = { "cp-${counter++}" },
        )
    }

    private fun snapshot(): File {
        val checkpoint = checkpoints.create("p1", "snapshot")
        return File(storage, "${checkpoint.id}.zip")
    }

    private fun lines(vararg text: String) = text.joinToString(nl) + nl

    @Test
    fun `an unchanged workspace produces no patches`() {
        fs.write("a.txt", lines("one", "two"))
        val archive = snapshot()

        assertTrue(WorkspaceDiff.against(fs, archive).isEmpty())
    }

    @Test
    fun `a modified file is reported`() {
        fs.write("a.txt", lines("one", "two", "three"))
        val archive = snapshot()
        fs.write("a.txt", lines("one", "CHANGED", "three"))

        val patches = WorkspaceDiff.against(fs, archive)

        assertEquals(1, patches.size)
        assertEquals("a.txt", patches.single().displayPath)
        assertEquals(1, patches.single().addedCount)
        assertEquals(1, patches.single().removedCount)
    }

    @Test
    fun `a new file is reported as created`() {
        fs.write("existing.txt", "x")
        val archive = snapshot()
        fs.write("added.txt", lines("brand new"))

        val patch = WorkspaceDiff.against(fs, archive).single()

        assertTrue(patch.isNewFile)
        assertEquals("added.txt", patch.displayPath)
    }

    @Test
    fun `a deleted file is reported as deleted`() {
        fs.write("gone.txt", lines("content"))
        val archive = snapshot()
        fs.delete("gone.txt")

        val patch = WorkspaceDiff.against(fs, archive).single()

        assertTrue(patch.isDeletedFile)
        assertEquals(1, patch.removedCount)
    }

    @Test
    fun `reversing a patch swaps the sides`() {
        val forward = UnifiedDiff.create(lines("a"), lines("b"), "f.txt")

        val reversed = WorkspaceDiff.reverse(forward)

        assertEquals(forward.addedCount, reversed.removedCount)
        assertEquals(forward.removedCount, reversed.addedCount)
    }

    @Test
    fun `applying a reversed patch restores the original exactly`() {
        val before = lines("one", "two", "three", "four")
        val after = lines("one", "TWO", "three", "FOUR")
        val patch = UnifiedDiff.create(before, after, "f.txt")

        val result = PatchApplier.applyAll(after, WorkspaceDiff.reverse(patch))

        assertTrue(result is PatchApplier.Result.Success)
        assertEquals(before, (result as PatchApplier.Result.Success).text)
    }

    @Test
    fun `rejecting one hunk reverts only that hunk`() {
        // The core promise of per-hunk review: keep one agent change, undo the
        // other, and leave everything else alone.
        val original = (1..40).joinToString(nl) { "line $it" } + nl
        fs.write("a.txt", original)
        val archive = snapshot()

        val edited = (1..40).joinToString(nl) { index ->
            when (index) {
                3 -> "AGENT FIRST"
                30 -> "AGENT SECOND"
                else -> "line $index"
            }
        } + nl
        fs.write("a.txt", edited)

        val patches = WorkspaceDiff.against(fs, archive)
        assertEquals(2, patches.single().hunks.size)

        // Reject the first change, keep the second.
        WorkspaceDiff.revert(fs, patches, mapOf("a.txt" to setOf(0)))

        val result = fs.read("a.txt").text
        assertTrue("the rejected change should be gone", result.contains("line 3" + nl))
        assertFalse(result.contains("AGENT FIRST"))
        assertTrue("the accepted change should remain", result.contains("AGENT SECOND"))
    }

    @Test
    fun `rejecting every hunk restores the snapshot exactly`() {
        val original = lines("alpha", "beta", "gamma")
        fs.write("a.txt", original)
        val archive = snapshot()
        fs.write("a.txt", lines("alpha", "BETA", "GAMMA"))

        val patches = WorkspaceDiff.against(fs, archive)
        WorkspaceDiff.revert(
            fs,
            patches,
            patches.associate { it.displayPath to it.hunks.indices.toSet() },
        )

        assertEquals(original, fs.read("a.txt").text)
    }

    @Test
    fun `rejecting nothing leaves the workspace untouched`() {
        fs.write("a.txt", lines("one"))
        val archive = snapshot()
        fs.write("a.txt", lines("two"))

        val patches = WorkspaceDiff.against(fs, archive)
        val changed = WorkspaceDiff.revert(fs, patches, emptyMap())

        assertTrue(changed.isEmpty())
        assertEquals(lines("two"), fs.read("a.txt").text)
    }

    @Test
    fun `rejecting the creation of a new file removes it`() {
        fs.write("keep.txt", "keep")
        val archive = snapshot()
        fs.write("unwanted.txt", lines("agent made this"))

        val patches = WorkspaceDiff.against(fs, archive)
        WorkspaceDiff.revert(fs, patches, mapOf("unwanted.txt" to setOf(0)))

        assertFalse(fs.exists("unwanted.txt"))
        assertTrue(fs.exists("keep.txt"))
    }

    @Test
    fun `binary files are skipped rather than diffed as text`() {
        fs.resolve("image.bin").writeBytes(byteArrayOf(1, 2, 0, 4))
        val archive = snapshot()
        fs.resolve("image.bin").writeBytes(byteArrayOf(9, 8, 0, 7))

        assertTrue(WorkspaceDiff.against(fs, archive).isEmpty())
    }

    @Test
    fun `a hunk that no longer applies is skipped rather than corrupting the file`() {
        fs.write("a.txt", lines("one", "two", "three"))
        val archive = snapshot()
        fs.write("a.txt", lines("one", "CHANGED", "three"))
        val patches = WorkspaceDiff.against(fs, archive)

        // The file moves on again after the diff was computed.
        fs.write("a.txt", lines("something", "completely", "different"))
        WorkspaceDiff.revert(fs, patches, mapOf("a.txt" to setOf(0)))

        assertEquals(lines("something", "completely", "different"), fs.read("a.txt").text)
    }
}
