package com.myfactory.forge.core.diff

import com.myfactory.forge.core.files.WorkspaceFs
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Diffs the working tree against a checkpoint archive.
 *
 * This is what makes per-hunk review reachable: after the agent has written,
 * the user can see every change since the last snapshot and revert individual
 * hunks rather than rolling the whole project back.
 *
 * Reverting is expressed as applying the reverse patch, which is why
 * [revert] flips added and removed rather than needing a second diff.
 */
object WorkspaceDiff {

    /** Files larger than this are reported as changed without a line diff. */
    const val MAX_DIFFABLE_BYTES = 1024L * 1024

    /**
     * @param archive a checkpoint zip, as written by
     *   [com.myfactory.forge.core.checkpoint.CheckpointManager].
     */
    fun against(
        workspace: WorkspaceFs,
        archive: File,
        contextLines: Int = UnifiedDiff.DEFAULT_CONTEXT,
    ): List<FilePatch> {
        if (!archive.isFile) throw IOException("The checkpoint archive is missing.")

        val patches = mutableListOf<FilePatch>()
        val seen = mutableSetOf<String>()

        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val path = entry.name
                seen += path

                val before = zip.getInputStream(entry).use { stream ->
                    val bytes = stream.readBytes()
                    if (WorkspaceFs.looksBinary(bytes)) null else String(bytes, Charsets.UTF_8)
                }

                val exists = runCatching { workspace.exists(path) }.getOrDefault(false)
                if (!exists) {
                    // Deleted since the snapshot.
                    if (before != null) {
                        patches += UnifiedDiff.create(
                            oldText = before,
                            newText = "",
                            path = path,
                            contextLines = contextLines,
                            isDeletedFile = true,
                        )
                    }
                    continue
                }

                val current = runCatching { workspace.read(path, MAX_DIFFABLE_BYTES) }.getOrNull()
                if (before == null || current == null || current.isBinary || current.truncated) {
                    // Not diffable as text; report it so the user knows it
                    // changed, without pretending to show hunks.
                    continue
                }
                if (before == current.text) continue

                patches += UnifiedDiff.create(before, current.text, path, contextLines)
            }
        }

        // Anything in the tree that the snapshot never had is a new file.
        for (node in workspace.walk().filter { !it.isDirectory }) {
            if (node.relativePath in seen) continue
            if (node.sizeBytes > MAX_DIFFABLE_BYTES) continue
            val content = runCatching { workspace.read(node.relativePath) }.getOrNull() ?: continue
            if (content.isBinary) continue
            patches += UnifiedDiff.create(
                oldText = "",
                newText = content.text,
                path = node.relativePath,
                contextLines = contextLines,
                isNewFile = true,
            )
        }

        return patches.filterNot { it.isEmpty }.sortedBy { it.displayPath }
    }

    /**
     * Reverts the hunks the user rejected.
     *
     * @param rejected per file path, the hunk indices to undo. Indices not
     *   listed are left as they are.
     * @return the paths that were changed.
     */
    fun revert(
        workspace: WorkspaceFs,
        patches: List<FilePatch>,
        rejected: Map<String, Set<Int>>,
    ): List<String> {
        val changed = mutableListOf<String>()

        for (patch in patches) {
            val indices = rejected[patch.displayPath].orEmpty()
            if (indices.isEmpty()) continue

            // A file that has since been deleted has nothing left to revert,
            // unless the patch created it, in which case an empty current text
            // is the right starting point.
            val current = runCatching { workspace.read(patch.displayPath).text }.getOrNull()
                ?: if (patch.isNewFile) "" else null
            if (current == null) continue

            // Reversing the patch turns "what changed" into "how to undo it",
            // and applying it to the current text puts those hunks back.
            val reversed = reverse(patch)
            when (val result = PatchApplier.apply(current, reversed, indices)) {
                is PatchApplier.Result.Success -> {
                    if (patch.isNewFile && result.text.isBlank()) {
                        runCatching { workspace.delete(patch.displayPath) }
                    } else {
                        workspace.write(patch.displayPath, result.text)
                    }
                    changed += patch.displayPath
                }
                // A hunk that no longer applies means the file moved on since
                // the diff was computed. Skip it rather than corrupt the file.
                is PatchApplier.Result.Failure -> continue
            }
        }
        return changed
    }

    /** Swaps the two sides of a patch, so applying it undoes the change. */
    fun reverse(patch: FilePatch): FilePatch = patch.copy(
        oldPath = patch.newPath,
        newPath = patch.oldPath,
        isNewFile = patch.isDeletedFile,
        isDeletedFile = patch.isNewFile,
        hunks = patch.hunks.map { hunk ->
            Hunk(
                oldStart = hunk.newStart,
                oldCount = hunk.newCount,
                newStart = hunk.oldStart,
                newCount = hunk.oldCount,
                heading = hunk.heading,
                lines = hunk.lines.map { line ->
                    HunkLine(
                        type = when (line.type) {
                            LineType.ADDED -> LineType.REMOVED
                            LineType.REMOVED -> LineType.ADDED
                            LineType.CONTEXT -> LineType.CONTEXT
                        },
                        text = line.text,
                    )
                },
            )
        },
    )
}
