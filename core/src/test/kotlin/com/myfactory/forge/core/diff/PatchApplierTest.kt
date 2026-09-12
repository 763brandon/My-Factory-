package com.myfactory.forge.core.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchApplierTest {

    private val nl: String = 10.toChar().toString()

    private fun text(vararg lines: String) = lines.joinToString(nl) + nl

    private fun success(result: PatchApplier.Result): PatchApplier.Result.Success {
        assertTrue("expected success, got $result", result is PatchApplier.Result.Success)
        return result as PatchApplier.Result.Success
    }

    @Test
    fun `applies a patch it produced itself`() {
        val old = text("one", "two", "three")
        val new = text("one", "TWO", "three")

        val result = success(PatchApplier.applyAll(old, UnifiedDiff.create(old, new, "a.txt")))

        assertEquals(new, result.text)
        assertEquals(listOf(0), result.appliedHunks)
    }

    /** 40 numbered lines, with line 3 and line 30 changed. Two distant hunks. */
    private val twoHunkOld = (1..40).joinToString(nl) { "line $it" } + nl
    private val twoHunkNew = (1..40).joinToString(nl) { index ->
        when (index) {
            3 -> "FIRST"
            30 -> "SECOND"
            else -> "line $index"
        }
    } + nl

    @Test
    fun `selecting one hunk applies it and leaves the other alone`() {
        // This is the per-hunk review flow: accept the first change, reject
        // the second, and the file must reflect exactly that.
        val patch = UnifiedDiff.create(twoHunkOld, twoHunkNew, "a.txt")
        assertEquals(2, patch.hunks.size)

        val result = success(PatchApplier.apply(twoHunkOld, patch, selectedHunks = setOf(0)))

        assertTrue(result.text.contains("FIRST"))
        assertTrue(result.text.contains("line 30"))
        assertTrue(!result.text.contains("SECOND"))
        assertEquals(listOf(0), result.appliedHunks)
        assertEquals(listOf(1), result.skippedHunks)
    }

    @Test
    fun `selecting the second hunk uses the right offsets`() {
        val patch = UnifiedDiff.create(twoHunkOld, twoHunkNew, "a.txt")

        val result = success(PatchApplier.apply(twoHunkOld, patch, selectedHunks = setOf(1)))

        assertTrue(result.text.contains("line 3" + nl))
        assertTrue(result.text.contains("SECOND"))
        assertTrue(!result.text.contains("FIRST"))
    }

    @Test
    fun `applying both hunks equals the full new text`() {
        val patch = UnifiedDiff.create(twoHunkOld, twoHunkNew, "a.txt")

        val result = success(PatchApplier.apply(twoHunkOld, patch, selectedHunks = setOf(0, 1)))

        assertEquals(twoHunkNew, result.text)
    }

    @Test
    fun `a hunk still applies when the file shifted underneath it`() {
        // The model wrote line numbers for a file that has since gained a
        // header. The context lines are the real anchor.
        val original = text("alpha", "beta", "gamma")
        val patch = UnifiedDiff.create(original, text("alpha", "BETA", "gamma"), "a.txt")
        val shifted = text("new header", "another", "alpha", "beta", "gamma")

        val result = success(PatchApplier.applyAll(shifted, patch))

        assertEquals(text("new header", "another", "alpha", "BETA", "gamma"), result.text)
    }

    @Test
    fun `a hunk whose context is gone fails instead of guessing`() {
        val patch = UnifiedDiff.create(
            text("alpha", "beta", "gamma"),
            text("alpha", "BETA", "gamma"),
            "a.txt",
        )

        val result = PatchApplier.applyAll(text("completely", "different", "file"), patch)

        assertTrue(result is PatchApplier.Result.Failure)
        assertTrue((result as PatchApplier.Result.Failure).message.contains("does not match"))
    }

    @Test
    fun `a file with no trailing newline keeps not having one`() {
        val old = "one" + nl + "two"
        val new = "one" + nl + "TWO"

        val result = success(PatchApplier.applyAll(old, UnifiedDiff.create(old, new, "a.txt")))

        assertEquals(new, result.text)
        assertTrue(!result.text.endsWith(nl))
    }

    @Test
    fun `creating content in an empty file works`() {
        val patch = UnifiedDiff.create("", text("hello"), "new.txt", isNewFile = true)

        val result = success(PatchApplier.applyAll("", patch))

        assertEquals(text("hello"), result.text)
    }

    @Test
    fun `deleting every line yields an empty file`() {
        val old = text("a", "b")

        val result = success(PatchApplier.applyAll(old, UnifiedDiff.create(old, "", "a.txt")))

        assertEquals("", result.text)
    }

    @Test
    fun `applying a parsed hand-written patch works`() {
        val original = text("fun main() {", "    println(\"old\")", "}")
        val handWritten = listOf(
            "--- a/Main.kt",
            "+++ b/Main.kt",
            "@@ -1,3 +1,3 @@",
            " fun main() {",
            "-    println(\"old\")",
            "+    println(\"new\")",
            " }",
        ).joinToString(nl) + nl

        val patch = UnifiedDiffParser.parse(handWritten).single()
        val result = success(PatchApplier.applyAll(original, patch))

        assertEquals(text("fun main() {", "    println(\"new\")", "}"), result.text)
    }
}
