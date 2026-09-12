package com.myfactory.forge.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.myfactory.forge.ui.theme.CodeColors

/**
 * A small, single-pass tokeniser for editor and diff display.
 *
 * Not a parser and not trying to be. A real grammar for eight languages would
 * cost more memory and startup time than colour is worth on a 2 GB phone, and
 * would still be wrong on a file that does not compile, which is precisely
 * when someone is looking at it. This handles strings, comments, numbers,
 * annotations and keywords, which is the great majority of what the eye uses.
 */
object SyntaxHighlighter {

    enum class Language(val keywords: Set<String>) {
        KOTLIN(
            setOf(
                "package", "import", "class", "interface", "object", "fun", "val", "var",
                "if", "else", "when", "for", "while", "do", "return", "break", "continue",
                "true", "false", "null", "this", "super", "is", "as", "in", "out",
                "private", "public", "protected", "internal", "open", "abstract", "final",
                "override", "suspend", "data", "sealed", "enum", "companion", "init",
                "constructor", "by", "lateinit", "const", "operator", "inline", "reified",
                "try", "catch", "finally", "throw", "typealias", "vararg", "where",
            ),
        ),
        JAVA(
            setOf(
                "package", "import", "class", "interface", "enum", "extends", "implements",
                "public", "private", "protected", "static", "final", "abstract", "void",
                "int", "long", "float", "double", "boolean", "char", "byte", "short",
                "if", "else", "switch", "case", "default", "for", "while", "do", "return",
                "break", "continue", "new", "this", "super", "true", "false", "null",
                "try", "catch", "finally", "throw", "throws", "synchronized", "instanceof",
            ),
        ),
        JAVASCRIPT(
            setOf(
                "import", "export", "from", "default", "const", "let", "var", "function",
                "class", "extends", "return", "if", "else", "for", "while", "do", "switch",
                "case", "break", "continue", "new", "this", "super", "true", "false",
                "null", "undefined", "async", "await", "try", "catch", "finally", "throw",
                "typeof", "instanceof", "of", "in", "yield", "static", "get", "set",
                "interface", "type", "enum", "implements", "public", "private", "readonly",
            ),
        ),
        PYTHON(
            setOf(
                "import", "from", "as", "def", "class", "return", "if", "elif", "else",
                "for", "while", "break", "continue", "pass", "True", "False", "None",
                "and", "or", "not", "is", "in", "try", "except", "finally", "raise",
                "with", "lambda", "yield", "global", "nonlocal", "async", "await", "self",
            ),
        ),
        SHELL(
            setOf(
                "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case",
                "esac", "function", "return", "export", "local", "readonly", "source",
                "echo", "cd", "set", "unset", "trap", "exit",
            ),
        ),
        JSON(setOf("true", "false", "null")),
        MARKUP(emptySet()),
        PLAIN(emptySet()),
        ;

        companion object {
            fun forFileName(name: String): Language = when (name.substringAfterLast('.', "").lowercase()) {
                "kt", "kts" -> KOTLIN
                "java" -> JAVA
                "js", "mjs", "cjs", "jsx", "ts", "tsx" -> JAVASCRIPT
                "py", "pyi" -> PYTHON
                "sh", "bash", "zsh" -> SHELL
                "json", "jsonc" -> JSON
                "xml", "html", "htm", "svg" -> MARKUP
                else -> PLAIN
            }
        }
    }

    /**
     * Files past this size render unhighlighted. Building spans for a large
     * file is the fastest way to make a low-end device drop frames while
     * scrolling.
     */
    const val MAX_HIGHLIGHT_CHARS = 120_000

    fun highlight(
        text: String,
        language: Language,
        colors: CodeColors,
        enabled: Boolean = true,
    ): AnnotatedString {
        if (!enabled || language == Language.PLAIN || text.length > MAX_HIGHLIGHT_CHARS) {
            return AnnotatedString(text)
        }
        return if (language == Language.MARKUP) {
            highlightMarkup(text, colors)
        } else {
            highlightCode(text, language, colors)
        }
    }

    private fun highlightCode(
        text: String,
        language: Language,
        colors: CodeColors,
    ): AnnotatedString = buildAnnotatedString {
        var index = 0
        val length = text.length
        val hashComments = language == Language.PYTHON || language == Language.SHELL

        while (index < length) {
            val ch = text[index]

            // ---- comments -------------------------------------------------
            if (hashComments && ch == '#') {
                val end = text.indexOf(NEWLINE, index).takeIf { it >= 0 } ?: length
                span(colors.comment, text.substring(index, end))
                index = end
                continue
            }
            if (!hashComments && ch == '/' && index + 1 < length) {
                if (text[index + 1] == '/') {
                    val end = text.indexOf(NEWLINE, index).takeIf { it >= 0 } ?: length
                    span(colors.comment, text.substring(index, end))
                    index = end
                    continue
                }
                if (text[index + 1] == '*') {
                    val end = text.indexOf("*/", index + 2).let { if (it >= 0) it + 2 else length }
                    span(colors.comment, text.substring(index, end))
                    index = end
                    continue
                }
            }

            // ---- strings --------------------------------------------------
            if (ch == '"' || ch == '\'' || ch == '`') {
                val end = findStringEnd(text, index, ch)
                span(colors.string, text.substring(index, end))
                index = end
                continue
            }

            // ---- annotations and decorators -------------------------------
            if (ch == '@' && index + 1 < length && text[index + 1].isLetter()) {
                var end = index + 1
                while (end < length && (text[end].isLetterOrDigit() || text[end] == '.' || text[end] == '_')) end++
                span(colors.annotation, text.substring(index, end))
                index = end
                continue
            }

            // ---- numbers --------------------------------------------------
            if (ch.isDigit() && (index == 0 || !isIdentifierPart(text[index - 1]))) {
                var end = index
                while (end < length && (text[end].isLetterOrDigit() || text[end] == '.' || text[end] == '_')) end++
                span(colors.number, text.substring(index, end))
                index = end
                continue
            }

            // ---- identifiers and keywords ---------------------------------
            if (isIdentifierStart(ch)) {
                var end = index
                while (end < length && isIdentifierPart(text[end])) end++
                val word = text.substring(index, end)
                if (word in language.keywords) span(colors.keyword, word) else append(word)
                index = end
                continue
            }

            append(ch)
            index++
        }
    }

    private fun highlightMarkup(text: String, colors: CodeColors): AnnotatedString =
        buildAnnotatedString {
            var index = 0
            val length = text.length
            while (index < length) {
                val ch = text[index]
                if (ch == '<') {
                    if (text.startsWith("<!--", index)) {
                        val end = text.indexOf("-->", index).let { if (it >= 0) it + 3 else length }
                        span(colors.comment, text.substring(index, end))
                        index = end
                        continue
                    }
                    val end = text.indexOf('>', index).let { if (it >= 0) it + 1 else length }
                    span(colors.keyword, text.substring(index, end))
                    index = end
                    continue
                }
                append(ch)
                index++
            }
        }

    /** Walks to the closing quote, respecting backslash escapes. */
    private fun findStringEnd(text: String, start: Int, quote: Char): Int {
        var index = start + 1
        while (index < text.length) {
            val ch = text[index]
            if (ch == BACKSLASH) {
                index += 2
                continue
            }
            if (ch == quote) return index + 1
            // An unterminated string should not swallow the rest of the file.
            if (ch == NEWLINE && quote != '`') return index
            index++
        }
        return text.length
    }

    private fun isIdentifierStart(ch: Char) = ch.isLetter() || ch == '_' || ch == '$'
    private fun isIdentifierPart(ch: Char) = ch.isLetterOrDigit() || ch == '_' || ch == '$'

    private fun androidx.compose.ui.text.AnnotatedString.Builder.span(color: Color, text: String) {
        withStyle(SpanStyle(color = color)) { append(text) }
    }

    private val NEWLINE: Char = 10.toChar()
    private val BACKSLASH: Char = 92.toChar()
}
