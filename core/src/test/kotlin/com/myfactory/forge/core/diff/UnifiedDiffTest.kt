package com.myfactory.forge.core.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDiffTest {

    private val nl: String = 10.toChar().toString()

    private fun lines(vararg text: String) = text.joinToString(nl) + nl

    @Test
    fun `a one line change renders one hunk with three lines of context`() {
        val old = lines("1", "2", "3", "4", "5", "6", "7", "8", "9")
        val new = lines("1", "2", "3", "4", "CHANGED", "6", "7", "8", "9")

        val patch = UnifiedDiff.create(old, new, "notes.txt")

        assertEquals(1, patch.hunks.size)
        val hunk = patch.hunks.single()
        assertEquals(2, hunk.oldStart)
        assertEquals(7, hunk.oldCount)
        assertEquals(7, hunk.newCount)
        assertEquals(1, patch.addedCount)
        assertEquals(1, patch.removedCount)
    }

    @Test
    fun `distant changes produce separate hunks`() {
        val old = (1..40).joinToString(nl) { "line $it" } + nl
        val new = (1..40).joinToString(nl) { index ->
            if (index == 2 || index == 35) "LINE $index" else "line $index"
        } + nl

        val patch = UnifiedDiff.create(old, new, "a.txt")

        assertEquals(2, patch.hunks.size)
    }

    @Test
    fun `nearby changes are merged into one hunk`() {
        val old = (1..20).joinToString(nl) { "line $it" } + nl
        val new = (1..20).joinToString(nl) { index ->
            if (index == 8 || index == 10) "LINE $index" else "line $index"
        } + nl

        val patch = UnifiedDiff.create(old, new, "a.txt")

        assertEquals(1, patch.hunks.size)
    }

    @Test
    fun `rendered output round-trips through the parser`() {
        val old = lines("alpha", "beta", "gamma", "delta", "epsilon")
        val new = lines("alpha", "BETA", "gamma", "delta", "epsilon", "zeta")

        val rendered = UnifiedDiff.render(UnifiedDiff.create(old, new, "src/x.kt"))
        val parsed = UnifiedDiffParser.parse(rendered)

        assertEquals(1, parsed.size)
        assertEquals("src/x.kt", parsed.single().displayPath)

        val applied = PatchApplier.applyAll(old, parsed.single())
        assertTrue(applied is PatchApplier.Result.Success)
        assertEquals(new, (applied as PatchApplier.Result.Success).text)
    }

    @Test
    fun `a new file is reported with dev null as its old path`() {
        val patch = UnifiedDiff.create("", lines("hello"), "new.txt", isNewFile = true)

        assertEquals(FilePatch.DEV_NULL, patch.oldPath)
        assertTrue(patch.isNewFile)
        assertEquals(1, patch.addedCount)
        assertTrue(UnifiedDiff.render(patch).contains("--- /dev/null"))
    }

    @Test
    fun `identical content yields an empty patch`() {
        val patch = UnifiedDiff.create(lines("same"), lines("same"), "a.txt")

        assertTrue(patch.isEmpty)
        assertTrue(patch.hunks.isEmpty())
    }

    @Test
    fun `hunk header omits the count when a side has exactly one line`() {
        val hunk = Hunk(4, 1, 4, 1, listOf(HunkLine(LineType.CONTEXT, "x")))

        assertEquals("@@ -4 +4 @@", hunk.header())
    }

    @Test
    fun `hunk header includes a git style heading when present`() {
        val hunk = Hunk(1, 2, 1, 3, emptyList(), heading = "fun main()")

        assertEquals("@@ -1,2 +1,3 @@ fun main()", hunk.header())
    }
}

class UnifiedDiffParserTest {

    private val nl: String = 10.toChar().toString()

    private fun diff(vararg lines: String) = lines.joinToString(nl) + nl

    @Test
    fun `parses a plain patch`() {
        val patch = UnifiedDiffParser.parse(
            diff(
                "--- a/src/Main.kt",
                "+++ b/src/Main.kt",
                "@@ -1,3 +1,3 @@",
                " fun main() {",
                "-    println(\"old\")",
                "+    println(\"new\")",
                " }",
            ),
        ).single()

        // The a/ and b/ prefixes are stripped so paths resolve in the workspace.
        assertEquals("src/Main.kt", patch.displayPath)
        assertEquals(1, patch.hunks.size)
        assertEquals(1, patch.addedCount)
        assertEquals(1, patch.removedCount)
    }

    @Test
    fun `parses a patch a model wrapped in a markdown fence`() {
        val fenced = "```diff" + nl + diff(
            "--- a.txt",
            "+++ a.txt",
            "@@ -1 +1 @@",
            "-one",
            "+two",
        ) + "```"

        val patch = UnifiedDiffParser.parse(fenced).single()

        assertEquals("a.txt", patch.displayPath)
        assertEquals(1, patch.hunks.size)
    }

    @Test
    fun `parses several files in one patch`() {
        val patches = UnifiedDiffParser.parse(
            diff(
                "diff --git a/one.txt b/one.txt",
                "index 111..222 100644",
                "--- a/one.txt",
                "+++ b/one.txt",
                "@@ -1 +1 @@",
                "-a",
                "+b",
                "--- a/two.txt",
                "+++ b/two.txt",
                "@@ -1 +1 @@",
                "-c",
                "+d",
            ),
        )

        assertEquals(2, patches.size)
        assertEquals(listOf("one.txt", "two.txt"), patches.map { it.displayPath })
    }

    @Test
    fun `an omitted hunk count means one line`() {
        val patch = UnifiedDiffParser.parse(
            diff("--- a.txt", "+++ a.txt", "@@ -3 +3 @@", "-old", "+new"),
        ).single()

        val hunk = patch.hunks.single()
        assertEquals(1, hunk.oldCount)
        assertEquals(1, hunk.newCount)
        assertEquals(3, hunk.oldStart)
    }

    @Test
    fun `the no-newline marker is ignored rather than treated as content`() {
        val patch = UnifiedDiffParser.parse(
            diff(
                "--- a.txt",
                "+++ a.txt",
                "@@ -1 +1 @@",
                "-old",
                "\\ No newline at end of file",
                "+new",
            ),
        ).single()

        assertEquals(1, patch.addedCount)
        assertEquals(1, patch.removedCount)
    }

    @Test
    fun `a file deletion is recognised`() {
        val patch = UnifiedDiffParser.parse(
            diff("--- a/gone.txt", "+++ /dev/null", "@@ -1 +0,0 @@", "-content"),
        ).single()

        assertTrue(patch.isDeletedFile)
        assertEquals("gone.txt", patch.displayPath)
    }

    @Test
    fun `a hunk whose body contradicts its header is rejected`() {
        // Silently accepting this is how a patch corrupts a source file.
        val thrown = runCatching {
            UnifiedDiffParser.parse(
                diff("--- a.txt", "+++ a.txt", "@@ -1,5 +1,5 @@", " only one line"),
            )
        }.exceptionOrNull()

        assertTrue(thrown is UnifiedDiffParser.MalformedDiffException)
    }

    @Test
    fun `text with no diff in it is rejected with a useful message`() {
        val thrown = runCatching { UnifiedDiffParser.parse("I changed the file for you!") }
            .exceptionOrNull()

        assertTrue(thrown is UnifiedDiffParser.MalformedDiffException)
        assertTrue(thrown!!.message!!.contains("@@"))
    }

    @Test
    fun `a context line that lost its leading space is still parsed`() {
        // Models drop the space on empty context lines constantly.
        val patch = UnifiedDiffParser.parse(
            diff("--- a.txt", "+++ a.txt", "@@ -1,3 +1,3 @@", " one", "", "-three", "+THREE"),
        ).single()

        assertEquals(3, patch.hunks.single().oldCount)
    }
}
