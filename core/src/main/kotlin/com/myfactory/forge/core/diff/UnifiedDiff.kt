package com.myfactory.forge.core.diff

/**
 * Renders and reads the unified diff format.
 *
 * Two consumers rely on this:
 *  - The review screen, which shows the user exactly what the agent proposes
 *    before anything touches the disk.
 *  - The `apply_patch` tool, which has to read a diff the model wrote by hand
 *    and is therefore forgiving about the parts models get wrong.
 */
object UnifiedDiff {

    const val DEFAULT_CONTEXT = 3
    private val NEWLINE: String = 10.toChar().toString()

    /** Builds a patch describing how to turn [oldText] into [newText]. */
    fun create(
        oldText: String,
        newText: String,
        path: String,
        contextLines: Int = DEFAULT_CONTEXT,
        isNewFile: Boolean = false,
        isDeletedFile: Boolean = false,
    ): FilePatch {
        val ops = DiffEngine.diff(oldText, newText)
        val hunks = buildHunks(ops, contextLines)
        return FilePatch(
            oldPath = if (isNewFile) FilePatch.DEV_NULL else path,
            newPath = if (isDeletedFile) FilePatch.DEV_NULL else path,
            hunks = hunks,
            isNewFile = isNewFile,
            isDeletedFile = isDeletedFile,
        )
    }

    /**
     * Groups an edit script into hunks, keeping [contextLines] of unchanged
     * text on each side and merging runs that would otherwise overlap.
     */
    fun buildHunks(ops: List<DiffOp>, contextLines: Int = DEFAULT_CONTEXT): List<Hunk> {
        val changeIndices = ops.indices.filter { ops[it] !is DiffOp.Keep }
        if (changeIndices.isEmpty()) return emptyList()

        // Collect [start, end] windows around each change, then coalesce any
        // that touch so the output does not repeat context lines.
        val windows = mutableListOf<IntRange>()
        for (index in changeIndices) {
            val start = (index - contextLines).coerceAtLeast(0)
            val end = (index + contextLines).coerceAtMost(ops.lastIndex)
            val previous = windows.lastOrNull()
            if (previous != null && start <= previous.last + 1) {
                windows[windows.lastIndex] = previous.first..maxOf(previous.last, end)
            } else {
                windows += start..end
            }
        }

        // Walk the whole script once, tracking both line counters, and cut the
        // hunks out as the windows come up.
        val hunks = mutableListOf<Hunk>()
        var oldLine = 1
        var newLine = 1
        var windowIndex = 0
        var current: MutableList<HunkLine>? = null
        var hunkOldStart = 0
        var hunkNewStart = 0
        var hunkOldCount = 0
        var hunkNewCount = 0

        for ((index, op) in ops.withIndex()) {
            val window = windows.getOrNull(windowIndex)
            val inWindow = window != null && index in window

            if (inWindow && current == null) {
                current = mutableListOf()
                hunkOldStart = oldLine
                hunkNewStart = newLine
                hunkOldCount = 0
                hunkNewCount = 0
            }

            if (inWindow) {
                when (op) {
                    is DiffOp.Keep -> {
                        current!! += HunkLine(LineType.CONTEXT, op.text)
                        hunkOldCount++
                        hunkNewCount++
                    }
                    is DiffOp.Delete -> {
                        current!! += HunkLine(LineType.REMOVED, op.text)
                        hunkOldCount++
                    }
                    is DiffOp.Insert -> {
                        current!! += HunkLine(LineType.ADDED, op.text)
                        hunkNewCount++
                    }
                }
            }

            when (op) {
                is DiffOp.Keep -> {
                    oldLine++
                    newLine++
                }
                is DiffOp.Delete -> oldLine++
                is DiffOp.Insert -> newLine++
            }

            if (window != null && index == window.last) {
                hunks += Hunk(
                    // An empty side is reported as starting at the line before,
                    // which is what git does for a pure insertion into nothing.
                    oldStart = if (hunkOldCount == 0) hunkOldStart - 1 else hunkOldStart,
                    oldCount = hunkOldCount,
                    newStart = if (hunkNewCount == 0) hunkNewStart - 1 else hunkNewStart,
                    newCount = hunkNewCount,
                    lines = current!!.toList(),
                )
                current = null
                windowIndex++
            }
        }
        return hunks
    }

    /** Renders a patch in the format `git apply` accepts. */
    fun render(patch: FilePatch): String = buildString {
        append("--- ").append(patch.oldPath).append(NEWLINE)
        append("+++ ").append(patch.newPath).append(NEWLINE)
        if (patch.isBinary) {
            append("Binary files differ").append(NEWLINE)
            return@buildString
        }
        for (hunk in patch.hunks) {
            append(hunk.header()).append(NEWLINE)
            for (line in hunk.lines) {
                append(line.type.marker).append(line.text).append(NEWLINE)
            }
        }
    }

    fun render(patches: List<FilePatch>): String =
        patches.joinToString(separator = "") { render(it) }
}
