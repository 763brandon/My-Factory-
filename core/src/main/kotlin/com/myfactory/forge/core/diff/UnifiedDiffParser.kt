package com.myfactory.forge.core.diff

/**
 * Reads unified diff text back into [FilePatch] objects.
 *
 * This exists to serve the `apply_patch` agent tool, so it is deliberately
 * tolerant of what language models actually emit: fenced code blocks around
 * the diff, `a/` and `b/` prefixes, `diff --git` preambles, missing hunk
 * counts, and index or mode lines it has no use for. What it will not do is
 * guess: a hunk whose body does not match its declared counts is rejected so
 * the failure surfaces as a tool error rather than a corrupted file.
 */
object UnifiedDiffParser {

    private val HUNK_HEADER = Regex(
        "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@ ?(.*)$",
    )
    private val LINE_BREAK = Regex("\\u000D\\u000A|\\u000A|\\u000D")

    class MalformedDiffException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): List<FilePatch> {
        val lines = stripFences(text).split(LINE_BREAK)
        val patches = mutableListOf<FilePatch>()

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (!line.startsWith("--- ")) {
                index++
                continue
            }
            if (index + 1 >= lines.size || !lines[index + 1].startsWith("+++ ")) {
                index++
                continue
            }

            val oldPath = normalisePath(line.removePrefix("--- "))
            val newPath = normalisePath(lines[index + 1].removePrefix("+++ "))
            index += 2

            val hunks = mutableListOf<Hunk>()
            while (index < lines.size) {
                val header = HUNK_HEADER.find(lines[index]) ?: break
                val (oldStartRaw, oldCountRaw, newStartRaw, newCountRaw, heading) =
                    header.destructured
                val oldStart = oldStartRaw.toInt()
                val newStart = newStartRaw.toInt()
                // An omitted count means exactly one line, per the format.
                val declaredOld = oldCountRaw.ifEmpty { "1" }.toInt()
                val declaredNew = newCountRaw.ifEmpty { "1" }.toInt()
                index++

                val body = mutableListOf<HunkLine>()
                var oldSeen = 0
                var newSeen = 0
                while (index < lines.size && (oldSeen < declaredOld || newSeen < declaredNew)) {
                    val raw = lines[index]
                    // "\ No newline at end of file" carries no content.
                    if (raw.startsWith("\\")) {
                        index++
                        continue
                    }
                    // A trailing empty line before the next file header is
                    // padding, not a context line, when both sides are full.
                    if (raw.isEmpty() && oldSeen >= declaredOld && newSeen >= declaredNew) break

                    when (raw.firstOrNull()) {
                        '+' -> {
                            body += HunkLine(LineType.ADDED, raw.substring(1))
                            newSeen++
                        }
                        '-' -> {
                            body += HunkLine(LineType.REMOVED, raw.substring(1))
                            oldSeen++
                        }
                        ' ' -> {
                            body += HunkLine(LineType.CONTEXT, raw.substring(1))
                            oldSeen++
                            newSeen++
                        }
                        null -> {
                            // A context line that is itself empty commonly
                            // loses its leading space in transit.
                            body += HunkLine(LineType.CONTEXT, "")
                            oldSeen++
                            newSeen++
                        }
                        else -> break
                    }
                    index++
                }

                if (oldSeen != declaredOld || newSeen != declaredNew) {
                    throw MalformedDiffException(
                        "Hunk at line $oldStart declares -$declaredOld/+$declaredNew " +
                            "but its body contains -$oldSeen/+$newSeen.",
                    )
                }

                hunks += Hunk(oldStart, declaredOld, newStart, declaredNew, body, heading.trim())
            }

            if (hunks.isNotEmpty() || oldPath == FilePatch.DEV_NULL) {
                patches += FilePatch(
                    oldPath = oldPath,
                    newPath = newPath,
                    hunks = hunks,
                    isNewFile = oldPath == FilePatch.DEV_NULL,
                    isDeletedFile = newPath == FilePatch.DEV_NULL,
                )
            }
        }

        if (patches.isEmpty()) {
            throw MalformedDiffException(
                "No unified diff found. A patch needs '--- path', '+++ path' and at " +
                    "least one '@@' hunk header.",
            )
        }
        return patches
    }

    /** Drops a markdown fence the model wrapped the diff in. */
    private fun stripFences(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return text
        return trimmed
            .removePrefix("```")
            .substringAfter(10.toChar(), "")
            .substringBeforeLast("```")
    }

    /**
     * Turns `a/src/Main.kt` and a trailing timestamp into `src/Main.kt`.
     * Leaves /dev/null alone because it signals creation or deletion.
     */
    private fun normalisePath(raw: String): String {
        val withoutTimestamp = raw.split(9.toChar()).first().trim()
        if (withoutTimestamp == FilePatch.DEV_NULL) return FilePatch.DEV_NULL
        return withoutTimestamp
            .removePrefix("a/")
            .removePrefix("b/")
            .trim()
    }
}
