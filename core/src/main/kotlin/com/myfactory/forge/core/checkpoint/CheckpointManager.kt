package com.myfactory.forge.core.checkpoint

import com.myfactory.forge.core.files.WorkspaceFs
import java.io.File
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Stores workspace snapshots as zip archives outside the workspace itself.
 *
 * java.util.zip is used rather than anything richer because it is present on
 * API 24 with no desugaring and no extra dex weight, which matters for the
 * 32-bit low-end build.
 */
class CheckpointManager(
    private val workspace: WorkspaceFs,
    /** Must not be inside the workspace, or snapshots would nest. */
    private val storageDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
) {

    init {
        if (!storageDir.exists() && !storageDir.mkdirs()) {
            throw IOException("Could not create the checkpoint directory at $storageDir")
        }
    }

    class CheckpointException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Zips every tracked file in the workspace.
     *
     * @param maxBytes refuses rather than silently truncating, so a restore is
     *   never partial. A project too large to snapshot is a real answer the
     *   user needs to see.
     */
    fun create(
        projectId: String,
        label: String,
        automatic: Boolean = false,
        sessionId: String? = null,
        maxBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    ): Checkpoint {
        val id = idGenerator()
        val archive = archiveFile(id)
        val nodes = workspace.walk().filter { !it.isDirectory }

        val declared = nodes.sumOf { it.sizeBytes }
        if (declared > maxBytes) {
            throw CheckpointException(
                "This project is ${declared / (1024 * 1024)} MB, over the " +
                    "${maxBytes / (1024 * 1024)} MB checkpoint limit. Exclude build " +
                    "output or raise the limit in Settings.",
            )
        }

        try {
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                // Speed over ratio: a phone snapshotting before every agent
                // write should not stall the UI for a few hundred kilobytes.
                zip.setLevel(Deflater.BEST_SPEED)
                for (node in nodes) {
                    val source = workspace.resolve(node.relativePath)
                    if (!source.isFile) continue
                    val entry = ZipEntry(node.relativePath).apply { time = node.lastModified }
                    zip.putNextEntry(entry)
                    source.inputStream().use { it.copyTo(zip, COPY_BUFFER) }
                    zip.closeEntry()
                }
            }
        } catch (e: IOException) {
            archive.delete()
            throw CheckpointException("Could not write the checkpoint.", e)
        }

        return Checkpoint(
            id = id,
            projectId = projectId,
            label = label.ifBlank { "Checkpoint" },
            createdAtMillis = clock(),
            fileCount = nodes.size,
            archiveBytes = archive.length(),
            automatic = automatic,
            sessionId = sessionId,
        )
    }

    /**
     * Restores a snapshot over the workspace.
     *
     * Files tracked by the snapshot are replaced. Files that appeared since
     * are removed, so the result matches the snapshot exactly rather than
     * being a merge; anything skipped by [WorkspaceFs.DEFAULT_SKIP], such as
     * .git and node_modules, is left alone.
     */
    fun restore(checkpoint: Checkpoint) {
        val archive = archiveFile(checkpoint.id)
        if (!archive.isFile) {
            throw CheckpointException("Checkpoint ${checkpoint.label} is missing from storage.")
        }

        val restored = mutableSetOf<String>()
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // resolve() re-applies the traversal guard, so a
                        // tampered archive cannot write outside the project.
                        val target = workspace.resolve(entry.name)
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { out -> zip.copyTo(out, COPY_BUFFER) }
                        restored += entry.name
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: IOException) {
            throw CheckpointException("Could not read the checkpoint archive.", e)
        }

        for (node in workspace.walk().filter { !it.isDirectory }) {
            if (node.relativePath !in restored) {
                runCatching { workspace.delete(node.relativePath) }
            }
        }
    }

    fun delete(checkpoint: Checkpoint) {
        archiveFile(checkpoint.id).delete()
    }

    fun archiveExists(checkpoint: Checkpoint): Boolean = archiveFile(checkpoint.id).isFile

    fun archiveSize(checkpoint: Checkpoint): Long = archiveFile(checkpoint.id).length()

    /** Total bytes held by all snapshots, for the storage line in Settings. */
    fun totalStorageBytes(): Long =
        storageDir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun archiveFile(id: String): File = File(storageDir, "$id.zip")

    companion object {
        const val DEFAULT_MAX_ARCHIVE_BYTES: Long = 256L * 1024 * 1024
        private const val COPY_BUFFER = 64 * 1024
    }
}
