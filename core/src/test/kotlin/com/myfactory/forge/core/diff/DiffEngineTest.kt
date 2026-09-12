package com.myfactory.forge.core.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DiffEngineTest {

    private val nl: String = 10.toChar().toString()

    private fun rebuild(ops: List<DiffOp>): Pair<List<String>, List<String>> {
        val old = ops.filterNot { it is DiffOp.Insert }.map { it.text }
        val new = ops.filterNot { it is DiffOp.Delete }.map { it.text }
        return old to new
    }

    @Test
    fun `identical input produces only keeps`() {
        val ops = DiffEngine.diff("a${nl}b${nl}c", "a${nl}b${nl}c")

        assertTrue(ops.all { it is DiffOp.Keep })
        assertEquals(3, ops.size)
    }

    @Test
    fun `single line change is a delete plus an insert`() {
        val ops = DiffEngine.diff("a${nl}b${nl}c", "a${nl}B${nl}c")

        assertEquals(1, ops.count { it is DiffOp.Delete })
        assertEquals(1, ops.count { it is DiffOp.Insert })
        assertEquals(2, ops.count { it is DiffOp.Keep })
    }

    @Test
    fun `diff of empty against content is all inserts`() {
        val ops = DiffEngine.diff("", "one${nl}two")

        assertEquals(2, ops.size)
        assertTrue(ops.all { it is DiffOp.Insert })
    }

    @Test
    fun `diff of content against empty is all deletes`() {
        val ops = DiffEngine.diff("one${nl}two", "")

        assertEquals(2, ops.size)
        assertTrue(ops.all { it is DiffOp.Delete })
    }

    @Test
    fun `the edit script is minimal for a pure insertion`() {
        val ops = DiffEngine.diffLines(
            listOf("a", "b", "c"),
            listOf("a", "x", "b", "c"),
        )

        assertEquals(1, ops.count { it !is DiffOp.Keep })
        assertEquals("x", ops.first { it is DiffOp.Insert }.text)
    }

    @Test
    fun `moving a line is expressed as one delete and one insert`() {
        val ops = DiffEngine.diffLines(
            listOf("a", "b", "c", "d"),
            listOf("b", "c", "d", "a"),
        )

        assertEquals(1, ops.count { it is DiffOp.Delete })
        assertEquals(1, ops.count { it is DiffOp.Insert })
    }

    @Test
    fun `random inputs always reconstruct both sides exactly`() {
        // The property that matters: replaying the script must reproduce the
        // originals. A subtly wrong backtrack passes hand-written cases and
        // fails here.
        val random = Random(20260909)
        repeat(300) {
            val old = List(random.nextInt(0, 40)) { random.nextInt(0, 8).toString() }
            val new = List(random.nextInt(0, 40)) { random.nextInt(0, 8).toString() }

            val ops = DiffEngine.diffLines(old, new)
            val (rebuiltOld, rebuiltNew) = rebuild(ops)

            assertEquals("old side mismatch for $old -> $new", old, rebuiltOld)
            assertEquals("new side mismatch for $old -> $new", new, rebuiltNew)
        }
    }

    @Test
    fun `a large file with one changed line stays cheap`() {
        val old = (1..4000).map { "line $it" }
        val new = old.toMutableList().apply { this[2000] = "changed" }

        val started = System.nanoTime()
        val ops = DiffEngine.diffLines(old, new)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(2, ops.count { it !is DiffOp.Keep })
        // Prefix and suffix trimming should make this effectively instant. The
        // bound is loose enough not to be flaky on a slow CI runner.
        assertTrue("took ${elapsedMillis}ms", elapsedMillis < 1500)
    }

    @Test
    fun `pathological input degrades instead of hanging`() {
        // Two long sequences with nothing in common exceed the edit-distance
        // cap and must fall back to replace-everything.
        val old = (1..9000).map { "old $it" }
        val new = (1..9000).map { "new $it" }

        val ops = DiffEngine.diffLines(old, new)
        val (rebuiltOld, rebuiltNew) = rebuild(ops)

        assertEquals(old, rebuiltOld)
        assertEquals(new, rebuiltNew)
    }

    @Test
    fun `line splitting handles all three terminators`() {
        val cr = 13.toChar().toString()
        assertEquals(listOf("a", "b"), DiffEngine.splitLines("a${nl}b"))
        assertEquals(listOf("a", "b"), DiffEngine.splitLines("a$cr${nl}b"))
        assertEquals(listOf("a", "b"), DiffEngine.splitLines("a${cr}b"))
        assertEquals(listOf("a"), DiffEngine.splitLines("a$nl"))
        assertEquals(emptyList<String>(), DiffEngine.splitLines(""))
        // A blank line in the middle is a real line.
        assertEquals(listOf("a", "", "b"), DiffEngine.splitLines("a$nl$nl" + "b"))
    }

    @Test
    fun `trailing newline is detected so it can be preserved on write`() {
        assertTrue(DiffEngine.endsWithNewline("a$nl"))
        assertTrue(!DiffEngine.endsWithNewline("a"))
        assertTrue(!DiffEngine.endsWithNewline(""))
    }
}
