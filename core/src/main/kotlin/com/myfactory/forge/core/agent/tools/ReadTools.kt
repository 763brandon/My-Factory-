package com.myfactory.forge.core.agent.tools

import com.myfactory.forge.core.agent.AgentTool
import com.myfactory.forge.core.agent.ToolContext
import com.myfactory.forge.core.agent.ToolOutcome
import com.myfactory.forge.core.ai.JsonSchemas
import com.myfactory.forge.core.ai.ToolSpec
import com.myfactory.forge.core.files.WorkspaceFs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

internal fun JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

/** Reads a file, optionally a line range, with line numbers for the model. */
class ReadFileTool : AgentTool {

    override val requiresApproval: Boolean = false

    override val spec = ToolSpec(
        name = "read_file",
        description = "Read a UTF-8 text file from the project. Returns numbered lines. " +
            "Use start_line and max_lines for large files rather than reading the whole thing.",
        parametersSchema = JsonSchemas.obj(
            properties = mapOf(
                "path" to JsonSchemas.string("Path relative to the project root."),
                "start_line" to JsonSchemas.integer("1-based first line to return. Defaults to 1."),
                "max_lines" to JsonSchemas.integer("How many lines to return. Defaults to 400."),
            ),
            required = listOf("path"),
        ),
    )

    override fun describe(arguments: JsonObject): String =
        "Read ${arguments.str("path") ?: "a file"}"

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        val path = arguments.str("path")
            ?: return ToolOutcome.error("'path' is required.")
        return try {
            val content = context.workspace.read(path, context.maxOutputBytes.toLong())
            if (content.isBinary) {
                return ToolOutcome.error("'$path' is a binary file and cannot be read as text.")
            }

            val allLines = content.text.lines()
            val start = (arguments.int("start_line") ?: 1).coerceAtLeast(1)
            val limit = (arguments.int("max_lines") ?: DEFAULT_LINES).coerceIn(1, MAX_LINES)
            val slice = allLines.drop(start - 1).take(limit)

            if (slice.isEmpty()) {
                return ToolOutcome(
                    "'$path' has ${allLines.size} lines; line $start is past the end.",
                )
            }

            val numbered = slice.withIndex().joinToString(NEWLINE) { (offset, line) ->
                "${start + offset}	$line"
            }
            val end = start + slice.size - 1
            val notes = buildList {
                if (content.truncated) add("file truncated at the read limit")
                if (end < allLines.size) add("${allLines.size - end} more lines follow")
            }
            val suffix = if (notes.isEmpty()) "" else NEWLINE + "[" + notes.joinToString("; ") + "]"

            ToolOutcome(
                content = numbered + suffix,
                display = "Read $path, lines $start to $end",
            )
        } catch (e: Exception) {
            ToolOutcome.error(e.message ?: "Could not read '$path'.")
        }
    }

    private companion object {
        const val DEFAULT_LINES = 400
        const val MAX_LINES = 2000
        val NEWLINE: String = 10.toChar().toString()
    }
}

/** Lists one directory. */
class ListDirectoryTool : AgentTool {

    override val requiresApproval: Boolean = false

    override val spec = ToolSpec(
        name = "list_directory",
        description = "List the files and folders directly inside a project directory. " +
            "Pass an empty path for the project root.",
        parametersSchema = JsonSchemas.obj(
            properties = mapOf(
                "path" to JsonSchemas.string("Directory relative to the project root. Empty means the root."),
                "include_hidden" to JsonSchemas.boolean("Include dot-files. Defaults to false."),
            ),
            required = emptyList(),
        ),
    )

    override fun describe(arguments: JsonObject): String =
        "List ${arguments.str("path") ?: "the project root"}"

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        val path = arguments.str("path").orEmpty()
        return try {
            val nodes = context.workspace.list(path, arguments.bool("include_hidden") ?: false)
            if (nodes.isEmpty()) return ToolOutcome("The directory is empty.")
            val listing = nodes.joinToString(NEWLINE) { node ->
                if (node.isDirectory) "${node.name}/" else "${node.name}  (${node.sizeBytes} bytes)"
            }
            ToolOutcome(listing, "Listed ${nodes.size} entries in ${path.ifEmpty { "/" }}")
        } catch (e: Exception) {
            ToolOutcome.error(e.message ?: "Could not list '$path'.")
        }
    }

    private companion object {
        val NEWLINE: String = 10.toChar().toString()
    }
}

/**
 * Regex search across the project.
 *
 * Runs in the JVM rather than shelling out, so it works identically on a
 * device with no Linux runtime at all. That is the whole point of the
 * LIGHTWEIGHT tier having a usable agent.
 */
class SearchTool : AgentTool {

    override val requiresApproval: Boolean = false

    override val spec = ToolSpec(
        name = "search",
        description = "Search project files for a regular expression. Returns matching lines " +
            "with their file and line number.",
        parametersSchema = JsonSchemas.obj(
            properties = mapOf(
                "pattern" to JsonSchemas.string("A regular expression, in Java syntax."),
                "path" to JsonSchemas.string("Directory to search under. Defaults to the project root."),
                "file_glob" to JsonSchemas.string("Restrict to file names matching this glob, e.g. *.kt"),
                "max_results" to JsonSchemas.integer("Maximum matching lines to return. Defaults to 100."),
            ),
            required = listOf("pattern"),
        ),
    )

    override fun describe(arguments: JsonObject): String =
        "Search for ${arguments.str("pattern")}"

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        val patternText = arguments.str("pattern")
            ?: return ToolOutcome.error("'pattern' is required.")
        val regex = try {
            Regex(patternText)
        } catch (e: Exception) {
            return ToolOutcome.error("'$patternText' is not a valid regular expression: ${e.message}")
        }
        val globRegex = arguments.str("file_glob")?.let { globToRegex(it) }
        val limit = (arguments.int("max_results") ?: 100).coerceIn(1, 500)

        return try {
            val results = mutableListOf<String>()
            var scanned = 0
            outer@ for (node in context.workspace.walk(arguments.str("path").orEmpty())) {
                if (node.isDirectory) continue
                if (globRegex != null && !globRegex.matches(node.name)) continue
                if (node.sizeBytes > MAX_FILE_BYTES) continue

                val content = try {
                    context.workspace.read(node.relativePath, MAX_FILE_BYTES)
                } catch (e: Exception) {
                    continue
                }
                if (content.isBinary) continue
                scanned++

                for ((index, line) in content.text.lineSequence().withIndex()) {
                    if (!regex.containsMatchIn(line)) continue
                    val shown = if (line.length > 220) line.take(220) + "..." else line
                    results += "${node.relativePath}:${index + 1}: ${shown.trim()}"
                    if (results.size >= limit) break@outer
                }
            }

            if (results.isEmpty()) {
                ToolOutcome("No matches for '$patternText' across $scanned files.")
            } else {
                ToolOutcome(
                    content = results.joinToString(NEWLINE),
                    display = "${results.size} matches for '$patternText'",
                )
            }
        } catch (e: Exception) {
            ToolOutcome.error(e.message ?: "Search failed.")
        }
    }

    /** Supports the two wildcards a model actually uses in a glob. */
    private fun globToRegex(glob: String): Regex {
        val pattern = buildString {
            for (ch in glob) {
                when (ch) {
                    '*' -> append("[^/]*")
                    '?' -> append('.')
                    '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' ->
                        append('\\').append(ch)
                    else -> append(ch)
                }
            }
        }
        return Regex(pattern, RegexOption.IGNORE_CASE)
    }

    private companion object {
        const val MAX_FILE_BYTES = 1024L * 1024
        val NEWLINE: String = 10.toChar().toString()
    }
}

/** A compact tree of the project, so the model can orient itself in one call. */
class ProjectOverviewTool : AgentTool {

    override val requiresApproval: Boolean = false

    override val spec = ToolSpec(
        name = "project_overview",
        description = "Show the project's file tree, skipping build output and dependency " +
            "directories. Call this first when you do not know the layout.",
        parametersSchema = JsonSchemas.obj(
            properties = mapOf(
                "max_entries" to JsonSchemas.integer("Maximum paths to list. Defaults to 300."),
            ),
            required = emptyList(),
        ),
    )

    override fun describe(arguments: JsonObject): String = "Show the project structure"

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        val limit = (arguments.int("max_entries") ?: 300).coerceIn(20, 2000)
        return try {
            val nodes = context.workspace.walk(maxEntries = limit)
            if (nodes.isEmpty()) return ToolOutcome("The project is empty.")
            val listing = nodes
                .sortedBy { it.relativePath }
                .joinToString(NEWLINE) { if (it.isDirectory) it.relativePath + "/" else it.relativePath }
            ToolOutcome(listing, "${nodes.size} paths")
        } catch (e: Exception) {
            ToolOutcome.error(e.message ?: "Could not read the project.")
        }
    }

    private companion object {
        val NEWLINE: String = 10.toChar().toString()
    }
}
