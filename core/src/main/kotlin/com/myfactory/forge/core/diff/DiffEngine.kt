package com.myfactory.forge.core.diff

/** A single edit in the shortest edit script between two line sequences. */
sealed interface DiffOp {
    val text: String

    data class Keep(override val text: String) : DiffOp
    data class Insert(override val text: String) : DiffOp
    data class Delete(override val text: String) : DiffOp
}

/**
 * Line-level difference using Myers' O(ND) algorithm.
 *
 * The greedy forward pass records a snapshot of the furthest-reaching path
 * array at every edit distance, then walks those snapshots backwards to
 * recover the edit script. That costs O(ND) memory, which is the usual trade
 * for an implementation short enough to audit. Source files on a phone are
 * small enough for this to be the right trade, and a guard caps pathological
 * inputs so a minified bundle degrades to a whole-file replacement rather than
 * spending a minute in the loop.
 */
object DiffEngine {

    /**
     * Past this edit distance a diff stops being useful to a human and starts
     * being expensive, so it degrades to "replace the file".
     */
    const val MAX_EDIT_DISTANCE = 6000

    /** Line feed, U+000A. */
    private val LF: Char = 10.toChar()

    /** Carriage return, U+000D. */
    private val CR: Char = 13.toChar()

    private val LINE_BREAK = Regex("\\u000D\\u000A|\\u000A|\\u000D")

    fun diffLines(old: List<String>, new: List<String>): List<DiffOp> {
        // Trimming the shared prefix and suffix first is what makes a one-line
        // change inside a 4000-line file cost almost nothing.
        var prefix = 0
        val maxPrefix = minOf(old.size, new.size)
        while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++

        var suffix = 0
        val maxSuffix = minOf(old.size - prefix, new.size - prefix)
        while (suffix < maxSuffix &&
            old[old.size - 1 - suffix] == new[new.size - 1 - suffix]
        ) {
            suffix++
        }

        val oldMiddle = old.subList(prefix, old.size - suffix)
        val newMiddle = new.subList(prefix, new.size - suffix)

        val middle = myers(oldMiddle, newMiddle)
            ?: (oldMiddle.map { DiffOp.Delete(it) } + newMiddle.map { DiffOp.Insert(it) })

        return buildList {
            for (i in 0 until prefix) add(DiffOp.Keep(old[i]))
            addAll(middle)
            for (i in old.size - suffix until old.size) add(DiffOp.Keep(old[i]))
        }
    }

    fun diff(oldText: String, newText: String): List<DiffOp> =
        diffLines(splitLines(oldText), splitLines(newText))

    /**
     * Splits on LF, CRLF or CR, dropping one trailing terminator so that a
     * file ending in a newline yields the number of lines a human would count.
     */
    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split(LINE_BREAK)
        return if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    /** True when the text ended with a line terminator. */
    fun endsWithNewline(text: String): Boolean =
        text.isNotEmpty() && (text.last() == LF || text.last() == CR)

    /** Returns null when the edit distance exceeds [MAX_EDIT_DISTANCE]. */
    private fun myers(old: List<String>, new: List<String>): List<DiffOp>? {
        val n = old.size
        val m = new.size
        if (n == 0) return new.map { DiffOp.Insert(it) }
        if (m == 0) return old.map { DiffOp.Delete(it) }

        val max = minOf(n + m, MAX_EDIT_DISTANCE)
        val offset = max
        val size = 2 * max + 1
        val v = IntArray(size)
        val trace = ArrayList<IntArray>(max + 1)

        for (d in 0..max) {
            trace.add(v.copyOf())
            var k = -d
            while (k <= d) {
                val index = k + offset
                if (index < 1 || index > size - 2) {
                    k += 2
                    continue
                }
                // Decide whether this diagonal was reached by an insertion
                // (move down) or a deletion (move right).
                var x = if (k == -d || (k != d && v[index - 1] < v[index + 1])) {
                    v[index + 1]
                } else {
                    v[index - 1] + 1
                }
                var y = x - k
                // Slide along the diagonal through every matching line.
                while (x < n && y < m && old[x] == new[y]) {
                    x++
                    y++
                }
                v[index] = x
                if (x >= n && y >= m) return backtrack(trace, old, new, d, offset, size)
                k += 2
            }
        }
        return null
    }

    /** Walks the recorded snapshots backwards into a concrete edit script. */
    private fun backtrack(
        trace: List<IntArray>,
        old: List<String>,
        new: List<String>,
        editDistance: Int,
        offset: Int,
        size: Int,
    ): List<DiffOp> {
        val reversed = ArrayList<DiffOp>(old.size + new.size)
        var x = old.size
        var y = new.size

        for (d in editDistance downTo 1) {
            val v = trace[d]
            val k = x - y
            val index = k + offset
            val cameFromInsert = k == -d ||
                (k != d && index - 1 >= 0 && index + 1 < size && v[index - 1] < v[index + 1])
            val prevK = if (cameFromInsert) k + 1 else k - 1
            val prevIndex = prevK + offset
            val prevX = if (prevIndex in 0 until size) v[prevIndex] else 0
            val prevY = prevX - prevK

            // Unwind the diagonal slide before replaying the edit that
            // preceded it.
            while (x > prevX && y > prevY) {
                x--
                y--
                reversed.add(DiffOp.Keep(old[x]))
            }
            if (cameFromInsert) {
                y--
                reversed.add(DiffOp.Insert(new[y]))
            } else {
                x--
                reversed.add(DiffOp.Delete(old[x]))
            }
        }
        // Whatever is left is the leading run of identical lines.
        while (x > 0 && y > 0) {
            x--
            y--
            reversed.add(DiffOp.Keep(old[x]))
        }
        while (y > 0) {
            y--
            reversed.add(DiffOp.Insert(new[y]))
        }
        while (x > 0) {
            x--
            reversed.add(DiffOp.Delete(old[x]))
        }
        return reversed.asReversed()
    }
}
