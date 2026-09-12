package com.myfactory.forge.core.files

import java.io.File
import java.io.IOException

/** One entry in a directory listing. */
data class FileNode(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val isSymlink: Boolean = false,
) {
    val extension: String get() = name.substringAfterLast('.', "")
}

/** What came back from a read, including why it may have been truncated. */
data class FileContent(
    val relativePath: String,
    val text: String,
    val sizeBytes: Long,
    val truncated: Boolean,
    val isBinary: Boolean,
)

class WorkspaceException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Every file operation in the app goes through here.
 *
 * The single job of this class is that nothing, including a tool call the
 * model invented, can touch a path outside the workspace. Each path is
 * resolved to its canonical form and checked against the canonical root, which
 * closes both `../` traversal and symlinks that point out of the tree.
 *
 * PRoot is explicitly not a security boundary. This class is the boundary for
 * anything the agent does through the JVM, and command execution is gated
 * separately by user approval.
 */
class WorkspaceFs(root: File) {

    /** Canonicalised once so every check compares like with like. */
    val root: File = root.canonicalFile

    init {
        if (!this.root.exists() && !this.root.mkdirs()) {
            throw WorkspaceException("Could not create the workspace at ${this.root}")
        }
        if (!this.root.isDirectory) {
            throw WorkspaceException("The workspace path ${this.root} is not a directory.")
        }
    }

    /**
     * Resolves a workspace-relative path to a real file.
     *
     * @throws WorkspaceException if the result would land outside the root.
     */
    fun resolve(relativePath: String): File {
        val cleaned = relativePath.trim().removePrefix("./")
        if (cleaned.startsWith("/") || WINDOWS_ABSOLUTE.matches(cleaned)) {
            throw WorkspaceException(
                "Absolute paths are not allowed. Use a path relative to the project root.",
            )
        }
        if (cleaned.contains(NUL)) {
            throw WorkspaceException("Path contains a null byte.")
        }

        val candidate = File(root, cleaned)
        // canonicalFile resolves both ".." and any symlink on the way, so one
        // prefix check afterwards covers every escape route.
        val canonical = try {
            candidate.canonicalFile
        } catch (e: IOException) {
            throw WorkspaceException("Could not resolve '$relativePath'.", e)
        }
        if (!isInsideRoot(canonical)) {
            throw WorkspaceException(
                "'$relativePath' resolves outside the project and was blocked.",
            )
        }
        return canonical
    }

    fun relativise(file: File): String {
        val canonical = file.canonicalFile
        if (canonical == root) return ""
        val rootPath = root.path + File.separator
        val path = canonical.path
        if (!path.startsWith(rootPath)) {
            throw WorkspaceException("$file is outside the project.")
        }
        return path.substring(rootPath.length).replace(File.separatorChar, '/')
    }

    private fun isInsideRoot(canonical: File): Boolean =
        canonical == root || canonical.path.startsWith(root.path + File.separator)

    fun exists(relativePath: String): Boolean = resolve(relativePath).exists()

    fun isDirectory(relativePath: String): Boolean = resolve(relativePath).isDirectory

    /**
     * Lists one directory. Not recursive by design: a recursive walk of
     * node_modules on a 2 GB phone is exactly the kind of thing that gets an
     * app killed.
     */
    fun list(relativePath: String = "", includeHidden: Boolean = false): List<FileNode> {
        val dir = resolve(relativePath)
        if (!dir.isDirectory) throw WorkspaceException("'$relativePath' is not a directory.")
        val children = dir.listFiles() ?: throw WorkspaceException("Could not read '$relativePath'.")
        return children
            .asSequence()
            .filter { includeHidden || !it.name.startsWith(".") }
            .filter { it.name !in ALWAYS_HIDDEN }
            .map { child ->
                FileNode(
                    relativePath = relativise(child),
                    name = child.name,
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isFile) child.length() else 0L,
                    lastModified = child.lastModified(),
                    isSymlink = isSymlink(child),
                )
            }
            // Directories first, then case-insensitive by name, which is what
            // a file browser is expected to do.
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .toList()
    }

    fun read(relativePath: String, maxBytes: Long = DEFAULT_MAX_READ): FileContent {
        val file = resolve(relativePath)
        if (!file.isFile) throw WorkspaceException("'$relativePath' is not a file.")

        val size = file.length()
        val bytes = file.inputStream().use { stream ->
            val limit = minOf(size, maxBytes).toInt()
            val buffer = ByteArray(limit)
            var read = 0
            while (read < limit) {
                val count = stream.read(buffer, read, limit - read)
                if (count < 0) break
                read += count
            }
            if (read == limit) buffer else buffer.copyOf(read)
        }

        if (looksBinary(bytes)) {
            return FileContent(relativePath, "", size, truncated = false, isBinary = true)
        }
        return FileContent(
            relativePath = relativePath,
            text = String(bytes, Charsets.UTF_8),
            sizeBytes = size,
            truncated = size > maxBytes,
            isBinary = false,
        )
    }

    fun write(relativePath: String, content: String) {
        val file = resolve(relativePath)
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw WorkspaceException("Could not create ${relativise(parent)}.")
            }
        }
        // Write to a sibling and rename, so an interrupted write cannot leave
        // a half-written source file behind.
        val temp = File(file.parentFile, "." + file.name + ".forge-tmp")
        try {
            temp.writeText(content, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                // Rename fails across some SAF-backed mounts; fall back.
                file.writeText(content, Charsets.UTF_8)
                temp.delete()
            }
        } catch (e: IOException) {
            temp.delete()
            throw WorkspaceException("Could not write '$relativePath'.", e)
        }
    }

    fun mkdirs(relativePath: String) {
        val dir = resolve(relativePath)
        if (dir.isDirectory) return
        if (!dir.mkdirs()) throw WorkspaceException("Could not create '$relativePath'.")
    }

    fun delete(relativePath: String, recursive: Boolean = false) {
        if (relativePath.isBlank()) {
            throw WorkspaceException("Refusing to delete the project root.")
        }
        val file = resolve(relativePath)
        if (!file.exists()) return
        val ok = if (file.isDirectory && recursive) file.deleteRecursively() else file.delete()
        if (!ok) throw WorkspaceException("Could not delete '$relativePath'.")
    }

    fun move(fromRelative: String, toRelative: String) {
        val from = resolve(fromRelative)
        val to = resolve(toRelative)
        if (!from.exists()) throw WorkspaceException("'$fromRelative' does not exist.")
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            throw WorkspaceException("Could not move '$fromRelative' to '$toRelative'.")
        }
    }

    /**
     * Depth-limited recursive walk, used by search and by checkpointing.
     * Skips the directories that dominate a JavaScript project's file count.
     */
    fun walk(
        relativePath: String = "",
        maxDepth: Int = 12,
        maxEntries: Int = 20_000,
        skipDirectories: Set<String> = DEFAULT_SKIP,
    ): List<FileNode> {
        val start = resolve(relativePath)
        val out = mutableListOf<FileNode>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(start to 0)

        while (queue.isNotEmpty() && out.size < maxEntries) {
            val (dir, depth) = queue.removeFirst()
            if (depth > maxDepth) continue
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (out.size >= maxEntries) break
                if (child.isDirectory && child.name in skipDirectories) continue
                // Never follow a symlink during a walk; it is the easy way to
                // spin forever or to leave the workspace.
                if (isSymlink(child)) continue
                val node = FileNode(
                    relativePath = relativise(child),
                    name = child.name,
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isFile) child.length() else 0L,
                    lastModified = child.lastModified(),
                )
                out += node
                if (child.isDirectory) queue.add(child to depth + 1)
            }
        }
        return out
    }

    private fun isSymlink(file: File): Boolean = runCatching {
        file.canonicalFile != file.absoluteFile
    }.getOrDefault(false)

    companion object {
        const val DEFAULT_MAX_READ: Long = 2L * 1024 * 1024

        private val NUL: Char = 0.toChar()
        private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[\\\\/].*")

        /** Never listed: internal bookkeeping the user did not create. */
        val ALWAYS_HIDDEN = setOf(".forge")

        val DEFAULT_SKIP = setOf(
            ".git", "node_modules", ".gradle", "build", ".idea", "__pycache__",
            ".venv", "venv", "dist", ".next", "target", ".forge",
        )

        /**
         * A NUL byte in the first block is the same heuristic git uses. It is
         * cheap and wrong rarely enough to be the right call on a phone.
         */
        fun looksBinary(bytes: ByteArray): Boolean {
            val limit = minOf(bytes.size, 8000)
            for (i in 0 until limit) {
                if (bytes[i] == 0.toByte()) return true
            }
            return false
        }
    }
}
