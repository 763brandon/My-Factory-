package com.myfactory.forge.ui

import androidx.compose.ui.graphics.Color
import com.myfactory.forge.ui.components.SyntaxHighlighter
import com.myfactory.forge.ui.theme.CodeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The highlighter runs in a VisualTransformation, so the one property that
 * must never break is that it does not change the text: if it did, the caret
 * and the selection would desynchronise from the buffer on every keystroke.
 */
class SyntaxHighlighterTest {

    private val nl: String = 10.toChar().toString()

    private val colors = CodeColors(
        added = Color.Green,
        addedBackground = Color.Green,
        removed = Color.Red,
        removedBackground = Color.Red,
        keyword = Color.Magenta,
        string = Color.Green,
        comment = Color.Gray,
        number = Color.Blue,
        annotation = Color.Yellow,
        lineNumber = Color.Gray,
        editorBackground = Color.White,
    )

    private fun highlight(text: String, language: SyntaxHighlighter.Language) =
        SyntaxHighlighter.highlight(text, language, colors)

    @Test
    fun `highlighting never changes the text itself`() {
        val samples = mapOf(
            SyntaxHighlighter.Language.KOTLIN to
                "fun main() {$nl    val x = \"hi\" // note$nl}",
            SyntaxHighlighter.Language.PYTHON to
                "def f(): # comment${nl}    return 'ok'",
            SyntaxHighlighter.Language.JAVASCRIPT to
                "const a = `template ${'$'}{x}`;$nl/* block */",
            SyntaxHighlighter.Language.MARKUP to
                "<html><!-- c --><body/></html>",
            SyntaxHighlighter.Language.JSON to
                "{\"a\": 1, \"b\": true}",
        )
        for ((language, source) in samples) {
            assertEquals(
                "text changed for $language",
                source,
                highlight(source, language).text,
            )
        }
    }

    @Test
    fun `an unterminated string does not swallow the rest of the file`() {
        val source = "val broken = \"oops$nl" + "fun next() {}"

        val result = highlight(source, SyntaxHighlighter.Language.KOTLIN)

        assertEquals(source, result.text)
        // "fun" on the following line must still be a keyword, which means the
        // string ended at the newline.
        assertTrue(result.spanStyles.any { it.start > source.indexOf("fun next") - 1 })
    }

    @Test
    fun `an unterminated block comment does not crash`() {
        val source = "/* never closed$nl" + "still going"

        assertEquals(source, highlight(source, SyntaxHighlighter.Language.KOTLIN).text)
    }

    @Test
    fun `keywords are styled and identifiers are not`() {
        val result = highlight("val total = count", SyntaxHighlighter.Language.KOTLIN)

        val styled = result.spanStyles.map { result.text.substring(it.start, it.end) }
        assertTrue("val" in styled)
        assertTrue("total" !in styled)
        assertTrue("count" !in styled)
    }

    @Test
    fun `an escaped quote does not end a string early`() {
        val backslash = 92.toChar()
        val source = "val s = \"a${backslash}\"b\" + tail"

        val result = highlight(source, SyntaxHighlighter.Language.KOTLIN)

        assertEquals(source, result.text)
        val styled = result.spanStyles.map { source.substring(it.start, it.end) }
        assertTrue(styled.any { it == "\"a${backslash}\"b\"" })
    }

    @Test
    fun `a very large file is left unhighlighted rather than stalling`() {
        val huge = "val x = 1$nl".repeat(SyntaxHighlighter.MAX_HIGHLIGHT_CHARS / 5)

        val result = highlight(huge, SyntaxHighlighter.Language.KOTLIN)

        assertEquals(huge, result.text)
        assertTrue("no spans should be built past the cap", result.spanStyles.isEmpty())
    }

    @Test
    fun `plain text is passed through untouched`() {
        val source = "class fun val -- these are just words"

        val result = highlight(source, SyntaxHighlighter.Language.PLAIN)

        assertEquals(source, result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `language is chosen from the file extension`() {
        assertEquals(
            SyntaxHighlighter.Language.KOTLIN,
            SyntaxHighlighter.Language.forFileName("Main.kt"),
        )
        assertEquals(
            SyntaxHighlighter.Language.JAVASCRIPT,
            SyntaxHighlighter.Language.forFileName("app.tsx"),
        )
        assertEquals(
            SyntaxHighlighter.Language.PYTHON,
            SyntaxHighlighter.Language.forFileName("script.py"),
        )
        assertEquals(
            SyntaxHighlighter.Language.MARKUP,
            SyntaxHighlighter.Language.forFileName("layout.xml"),
        )
        assertEquals(
            SyntaxHighlighter.Language.PLAIN,
            SyntaxHighlighter.Language.forFileName("notes.txt"),
        )
        assertEquals(
            SyntaxHighlighter.Language.PLAIN,
            SyntaxHighlighter.Language.forFileName("Makefile"),
        )
    }

    @Test
    fun `disabling highlighting returns the text with no spans`() {
        val result = SyntaxHighlighter.highlight(
            "fun main() {}",
            SyntaxHighlighter.Language.KOTLIN,
            colors,
            enabled = false,
        )

        assertTrue(result.spanStyles.isEmpty())
    }
}
