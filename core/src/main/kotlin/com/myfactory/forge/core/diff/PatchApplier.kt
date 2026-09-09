package com.myfactory.forge.core.diff

/**
 * Applies hunks to text, one hunk at a time.
 *
 * Per-hunk selection is the point: the review screen lets the user accept some
 * of the agent's changes and reject the rest, so this cannot be a whole-file
 * operation. Rejected hunks leave the surrounding text untouched and shift the
 * line offsets applied to the hunks that follow.
 */
object PatchApplier {

    private val NEWLINE: String = 10.toChar().toString()

    /**
     * How far from its declared position a hunk may be found. Models routinely
     * miscount line numbers by a few, and a file may have moved on since the
     * diff was produced; the context lines are the real anchor.
     */
    const val SEARCH_RADIUS = 200

    sealed interface Result {
        data class Success(
            val text: String,
            val appliedHunks: List<Int>,
            val skippedHunks: List<Int>,
        ) : Result

        data class Failure(val message: String, val hunkIndex: Int) : Result
    }

    /**
     * @param selectedHunks indices into [patch].hunks to apply; null applies
     *   every hunk.
     */
    fun apply(
        original: String,
        patch: FilePatch,
        selectedHunks: Set<Int>? = null,
    ): Result {
        if (patch.isBinary) {
            return Result.Failure("Cannot apply a patch to binary content.", -1)
        }

        val lines = DiffEngine.splitLines(original).toMutableList()
        val trailingNewline = original.isEmpty() || DiffEngine.endsWithNewline(original)

        val applied = mutableListOf<Int>()
        val skipped = mutableListOf<Int>()
        // Every applied hunk moves the ones after it by the number of lines it
        // added or removed.
        var offset = 0

        for ((index, hunk) in patch.hunks.withIndex()) {
            if (selectedHunks != null && index !in selectedHunks) {
                skipped += index
                continue
            }

            val expected = hunk.lines
                .filter { it.type != LineType.ADDED }
                .map { it.text }

            val guess = (hunk.oldStart - 1 + offset).coerceAtLeast(0)
            val position = locate(lines, expected, guess)
                ?: return Result.Failure(
                    "Hunk ${index + 1} does not match the file. Expected to find " +
                        describeAnchor(expected) + " near line ${guess + 1}.",
                    index,
                )

            val replacement = hunk.lines
                .filter { it.type != LineType.REMOVED }
                .map { it.text }

            repeat(expected.size) { lines.removeAt(position) }
            lines.addAll(position, replacement)
            offset += replacement.size - expected.size
            applied += index
        }

        val text = if (lines.isEmpty()) {
            ""
        } else {
            lines.joinToString(NEWLINE) + if (trailingNewline) NEWLINE else ""
        }
        return Result.Success(text, applied, skipped)
    }

    /** Applies every hunk. Convenience for the non-interactive path. */
    fun applyAll(original: String, patch: FilePatch): Result = apply(original, patch, null)

    /**
     * Finds where [expected] sits in [lines], starting at [guess] and spiralling
     * outwards. Exact matches only: a fuzzy match that silently mangles source
     * code is worse than a tool error the agent can react to.
     */
    private fun locate(lines: List<String>, expected: List<String>, guess: Int): Int? {
        if (expected.isEmpty()) return guess.coerceIn(0, lines.size)
        if (matchesAt(lines, expected, guess)) return guess

        for (distance in 1..SEARCH_RADIUS) {
            val before = guess - distance
            if (before >= 0 && matchesAt(lines, expected, before)) return before
            val after = guess + distance
            if (after + expected.size <= lines.size && matchesAt(lines, expected, after)) return after
        }
        return null
    }

    private fun matchesAt(lines: List<String>, expected: List<String>, at: Int): Boolean {
        if (at < 0 || at + expected.size > lines.size) return false
        for (i in expected.indices) {
            if (lines[at + i] != expected[i]) return false
        }
        return true
    }

    private fun describeAnchor(expected: List<String>): String {
        val first = expected.firstOrNull { it.isNotBlank() } ?: return "an empty context block"
        val shown = if (first.length > 60) first.take(60) + "..." else first
        return "\"" + shown.trim() + "\""
    }
}
