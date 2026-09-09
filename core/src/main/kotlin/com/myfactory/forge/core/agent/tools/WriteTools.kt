package com.myfactory.forge.core.agent.tools

import com.myfactory.forge.core.agent.AgentTool
import com.myfactory.forge.core.agent.ToolContext
import com.myfactory.forge.core.agent.ToolOutcome
import com.myfactory.forge.core.ai.JsonSchemas
import com.myfactory.forge.core.ai.ToolSpec
import com.myfactory.forge.core.diff.PatchApplier
import com.myfactory.forge.core.diff.UnifiedDiff
import com.myfactory.forge.core.diff.UnifiedDiffParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every tool in this file mutates the user's files and therefore sets
 * [AgentTool.requiresApproval]. The agent loop refuses to execute them until
 * the user has approved the specific call on screen, with a diff in front of
 * them.
 */

/** Creates or replaces a file wholesale. */
class WriteFileTool : AgentTool {

    override val requiresApproval: Boolean = true

    override val spec = ToolSpec(
        name = "write_file",
        description = "Create a file, or replace an existing file's entire contents. " +
            "Prefer edit_file for changes to a file that already exists.",
        parametersSchema = JsonSchemas.obj(
            properties = mapOf(
                "path" to JsonSchemas.string("Path relative to the project root."),
                "content" to JsonSchemas.string("The complete new contents of the file."),
            ),
            required = listOf("path", "content"),
        ),
    )

    override fun describe(arguments: JsonObject): String =
        "Write ${arguments.str("path") ?: "a file"}"

    /** Renders the proposed change so the approval sheet can show it. */
    fun previewDiff(arguments: JsonObject, context: ToolContext): String? {
        val path = arguments.str("path") ?: return null
        val next = arguments["content"]?.jsonPrimitive?.content ?: return null
        val existing = runCatching {
            if (context.workspace.exists(path)) context.workspace.read(path).text else ""
        }.getOrDefault("")
        val patch = UnifiedDiff.create(
            oldText = existing,
            newText = next,
            path = path,
            isNewFile = existing.isEmpty() && !context.workspace.exists(path),
        )
        return if (patch.isEmpty) null else UnifiedDiff.render(patch)
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        val path = arguments.str("path") ?: return ToolOutcome.error("'path' is required.")
        val content = arguments.str("content") ?: ""
        return try {
            val existed = context.workspace.exists(path)
            context.workspace.write(path, content)
            val lines = content.lines().size
            ToolOutcome(
                content = if (existed) {
                    "Replaced '$path' ($lines lines)."
                } else {
                    "Created '$path' ($lines lines)."
                },
                display = if (existed) "Replaced $path" else "Created $path",
                mutatedPaths = listOf(path),
            )
        } catch (e: Exception) {
            ToolOutcome.error(e.message ?: "Could not write '$path'.")
        }
    }
}

/**
 * Replaces one exact string in a file.
 *
 * Exact-match replacement rather than line numbers, because a model's idea of
 * a line number drifts but its quotation of surrounding code is usually
 * reliable. A non-unique match is an error, not a guess.
 */
class EditFileTool : AgentTool {

    override val requiresApproval: Boolean = true

    override val spec = ToolSpec(
        name = "edit_file",
        description = "Replace an exact string in an existing file. The old_string must appear " +
            "exactly once unless replace_all is true, so include enough surrounding context " +
            "to make it unique.",
        parametersSchema = JsonSchemas.obj(
            properties = mapOf(
                "path" to JsonSchemas.string("Path relative to the project root."),
                "old_string" to JsonSchemas.string("The exact text to replace, including indentation."),
                "new_string" to JsonSchemas.string("The replacement text. Empty deletes the old text."),
                "replace_all" to JsonSchemas.boolean("Replace every occurrence. Defaults to false."),
            ),
            required = listOf("path", "old_string", "new_string"),
        ),
    )

    override fun describe(arguments: JsonObject): String =
        "Edit ${arguments.str("path") ?: "a file"}"

    fun previewDiff(arguments: JsonObject, context: ToolContext): String? {
        val path = arguments.str("path") ?: return null
        val next = computeNext(arguments, context).getOrNull() ?: return null
        val existing = runCatching { context.workspace.read(path).text }.getOrNull() ?: return null
        val patch = UnifiedDiff.create(existing, next, path)
        return if (patch.isEmpty) null else UnifiedDiff.render(patch)
    }

    private fun computeNext(arguments: JsonObject, context: ToolContext): Result<String> {
        val path = arguments.str("path") ?: return Result.failure(IllegalArgumentException("'path' is required."))
        val oldString = arguments["old_string"]?.jsonPrimitive?.content
            ?: return Result.failure(IllegalArgumentException("'old_string' is required."))
        val newString = arguments["new_string"]?.jsonPrimitive?.content ?: ""
        val replaceAll = arguments.bool("replace_all") ?: false

        return runCatching {
            val content = context.workspace.read(path)
            if (content.isBinary) error("'$path' is binary and cannot be edited as text.")
            val text = content.text

            val occurrences = countOccurrences(text, oldString)
            when {
                occurrences == 0 -> error(
                    "The text to replace was not found in '$path'. It must match exactly, " +
                        "including whitespace and indentation.",
                )
                occurrences > 1 && !replaceAll -> error(
                    "The text to replace appears $occurrences times in '$path'. Add surrounding " +
                        "context to make it unique, or set replace_all.",
                )
            }
            if (replaceAll) text.replace(oldString, newString) else text.replaceFirst(oldString, newString)
        }
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        val path = arguments.str("path") ?: return ToolOutcome.error("'path' is required.")
        val next = computeNext(arguments, context)
            .getOrElse { return ToolOutcome.error(it.message ?: "Edit failed.") }
        return try {
            context.workspace.write(path, next)
            ToolOutcome(
                content = "Edited '$path'.",
                display = "Edited $path",
                mutatedPaths = listOf(path),
            )
        } catch (e: Exception) {
            ToolOutcome.error(e.message ?: "Could not write '$path'.")
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}

/** Applies a unified diff, which is how a model proposes several edits at once. */
class ApplyPatchTool : AgentTool {

    override val requiresApproval: Boolean = true

    override val spec = ToolSpec(
        name = "apply_patch",
        description = "Apply a unified diff to the project. Use this to change several places " +
            "at once. Paths in the diff are relative to the project root.",
        parametersSchema = JsonSchemas.obj(
            properties = mapOf(
                "patch" to JsonSchemas.string(
                    "A unified diff with --- and +++ headers and @@ hunks.",
                ),
            ),
            required = listOf("patch"),
        ),
    )

    override fun describe(arguments: JsonObject): String {
        val patch = arguments.str("patch") ?: return "Apply a patch"
        val files = runCatching { UnifiedDiffParser.parse(patch).map { it.displayPath } }
            .getOrDefault(emptyList())
        return when {
            files.isEmpty() -> "Apply a patch"
            files.size == 1 -> "Patch ${files.first()}"
            else -> "Patch ${files.size} files"
        }
    }

    fun previewDiff(arguments: JsonObject): String? = arguments.str("patch")

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        val patchText = arguments.str("patch") ?: return ToolOutcome.error("'patch' is required.")

        val patches = try {
            UnifiedDiffParser.parse(patchText)
        } catch (e: Exception) {
            return ToolOutcome.error(e.message ?: "The patch could not be parsed.")
        }

        // Compute every result before writing anything, so a patch that fails
        // on its third file does not leave the first two half-applied.
        val staged = mutableListOf<Pair<String, String?>>()
        for (patch in patches) {
            val path = patch.displayPath
            if (patch.isDeletedFile) {
                staged += path to null
                continue
            }
            val original = if (patch.isNewFile) {
                ""
            } else {
                runCatching { context.workspace.read(path).text }
                    .getOrElse { return ToolOutcome.error("Cannot patch '$path': ${it.message}") }
            }
            when (val result = PatchApplier.applyAll(original, patch)) {
                is PatchApplier.Result.Success -> staged += path to result.text
                is PatchApplier.Result.Failure ->
                    return ToolOutcome.error("Cannot patch '$path': ${result.message}")
            }
        }

        return try {
            for ((path, content) in staged) {
                if (content == null) context.workspace.delete(path) else context.workspace.write(path, content)
            }
            val added = patches.sumOf { it.addedCount }
            val removed = patches.sumOf { it.removedCount }
            ToolOutcome(
                content = "Applied the patch to ${staged.size} file(s): +$added / -$removed lines.",
                display = "Patched ${staged.size} file(s), +$added / -$removed",
                mutatedPaths = staged.map { it.first },
            )
        } catch (e: Exception) {
            ToolOutcome.error(e.message ?: "Could not write the patched files.")
        }
    }
}

/** Deletes a file. Separate from write_file so approval text is unambiguous. */
class DeleteFileTool : AgentTool {

    override val requiresApproval: Boolean = true

    override val spec = ToolSpec(
        name = "delete_file",
        description = "Delete a file from the project.",
        parametersSchema = JsonSchemas.obj(
            properties = mapOf(
                "path" to JsonSchemas.string("Path relative to the project root."),
            ),
            required = listOf("path"),
        ),
    )

    override fun describe(arguments: JsonObject): String =
        "Delete ${arguments.str("path") ?: "a file"}"

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        val path = arguments.str("path") ?: return ToolOutcome.error("'path' is required.")
        return try {
            if (!context.workspace.exists(path)) return ToolOutcome("'$path' does not exist.")
            context.workspace.delete(path, recursive = false)
            ToolOutcome("Deleted '$path'.", "Deleted $path", mutatedPaths = listOf(path))
        } catch (e: Exception) {
            ToolOutcome.error(e.message ?: "Could not delete '$path'.")
        }
    }
}

/** Runs a shell command, when the device has a shell. */
class RunCommandTool : AgentTool {

    override val requiresApproval: Boolean = true

    override val spec = ToolSpec(
        name = "run_command",
        description = "Run a shell command in the project. Only available when this device has " +
            "a Linux runtime. Long-running servers should be started by the user, not here.",
        parametersSchema = JsonSchemas.obj(
            properties = mapOf(
                "command" to JsonSchemas.string("The command line to run."),
                "working_directory" to JsonSchemas.string(
                    "Directory relative to the project root. Defaults to the root.",
                ),
                "timeout_seconds" to JsonSchemas.integer("Seconds before the command is killed. Defaults to 120."),
            ),
            required = listOf("command"),
        ),
    )

    override fun describe(arguments: JsonObject): String =
        arguments.str("command")?.let { "Run: $it" } ?: "Run a command"

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        val command = arguments.str("command") ?: return ToolOutcome.error("'command' is required.")
        val shell = context.shell
            ?: return ToolOutcome.error(
                "This device has no Linux runtime, so commands cannot be run. Edit files " +
                    "directly instead.",
            )
        val timeout = (arguments.int("timeout_seconds") ?: 120).coerceIn(1, 900) * 1000L

        return try {
            val result = shell.run(
                command = command,
                workingDirectory = arguments.str("working_directory"),
                timeoutMillis = timeout,
                maxOutputBytes = context.maxOutputBytes,
            )
            val newline = 10.toChar().toString()
            val body = buildString {
                append("exit code: ").append(result.exitCode)
                if (result.timedOut) append(" (timed out)")
                if (result.stdout.isNotBlank()) {
                    append(newline).append("stdout:").append(newline).append(result.stdout)
                }
                if (result.stderr.isNotBlank()) {
                    append(newline).append("stderr:").append(newline).append(result.stderr)
                }
            }
            ToolOutcome(
                content = body,
                display = "$command (exit ${result.exitCode})",
                isError = result.exitCode != 0,
            )
        } catch (e: Exception) {
            ToolOutcome.error(e.message ?: "The command could not be run.")
        }
    }
}
