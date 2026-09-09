package com.myfactory.forge.core.diff

/** The role a line plays inside a hunk. */
enum class LineType(val marker: Char) {
    CONTEXT(' '),
    ADDED('+'),
    REMOVED('-'),
}

data class HunkLine(val type: LineType, val text: String)

/**
 * One contiguous change region.
 *
 * Line numbers are 1-based and inclusive, matching the unified diff format so
 * a rendered hunk header needs no adjustment.
 */
data class Hunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<HunkLine>,
    /** Free text after the second @@, which git uses for the enclosing function. */
    val heading: String = "",
) {
    val addedCount: Int get() = lines.count { it.type == LineType.ADDED }
    val removedCount: Int get() = lines.count { it.type == LineType.REMOVED }

    fun header(): String {
        val old = if (oldCount == 1) "$oldStart" else "$oldStart,$oldCount"
        val new = if (newCount == 1) "$newStart" else "$newStart,$newCount"
        val base = "@@ -$old +$new @@"
        return if (heading.isBlank()) base else "$base $heading"
    }
}

/** The complete set of changes to one file. */
data class FilePatch(
    val oldPath: String,
    val newPath: String,
    val hunks: List<Hunk>,
    val isNewFile: Boolean = false,
    val isDeletedFile: Boolean = false,
    /** Set when the content could not be diffed as text; hunks will be empty. */
    val isBinary: Boolean = false,
) {
    val displayPath: String get() = if (newPath != DEV_NULL) newPath else oldPath
    val addedCount: Int get() = hunks.sumOf { it.addedCount }
    val removedCount: Int get() = hunks.sumOf { it.removedCount }
    val isEmpty: Boolean get() = hunks.isEmpty() && !isBinary

    companion object {
        const val DEV_NULL = "/dev/null"
    }
}
