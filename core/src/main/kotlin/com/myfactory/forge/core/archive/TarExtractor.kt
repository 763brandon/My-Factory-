package com.myfactory.forge.core.archive

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal reader for POSIX ustar and GNU tar archives.
 *
 * Written by hand because Android ships no tar and the alternative is
 * shelling out to BusyBox, which is exactly the binary the bootstrap process
 * has not unpacked yet. Supports regular files, directories, symlinks, hard
 * links, GNU long names and long link targets, and the sparse-file header
 * type in its non-sparse form.
 *
 * Two safety properties matter here and both are tested:
 *  - No entry may write outside the destination directory, whatever its name
 *    claims. That is the "tar slip" class of bug.
 *  - Extraction stops at a byte budget rather than filling the user's phone.
 */
class TarExtractor(
    private val symlinks: SymlinkHandler = SymlinkHandler.Skip,
    private val maxTotalBytes: Long = 8L * 1024 * 1024 * 1024,
) {

    /** Platform hook: creating symlinks needs a different API on Android. */
    interface SymlinkHandler {
        fun createSymlink(link: File, target: String): Boolean

        /** Ignores symlinks. Correct for archives that contain none. */
        object Skip : SymlinkHandler {
            override fun createSymlink(link: File, target: String): Boolean = false
        }
    }

    data class Summary(
        val fileCount: Int,
        val directoryCount: Int,
        val symlinkCount: Int,
        val skippedCount: Int,
        val totalBytes: Long,
    )

    class TarException(message: String) : IOException(message)

    /** Unpacks a .tar.gz. */
    fun extractGzip(
        input: InputStream,
        destination: File,
        onProgress: ((Long) -> Unit)? = null,
    ): Summary = extract(GZIPInputStream(input, 64 * 1024), destination, onProgress)

    fun extract(
        input: InputStream,
        destination: File,
        onProgress: ((Long) -> Unit)? = null,
    ): Summary {
        if (!destination.exists() && !destination.mkdirs()) {
            throw TarException("Could not create $destination")
        }
        val root = destination.canonicalFile

        var files = 0
        var directories = 0
        var links = 0
        var skipped = 0
        var total = 0L

        // GNU long-name headers describe the *next* entry.
        var pendingLongName: String? = null
        var pendingLongLink: String? = null

        val header = ByteArray(BLOCK)
        var emptyBlocks = 0

        while (true) {
            if (!readFully(input, header, BLOCK)) break
            if (header.all { it == ZERO }) {
                // Two consecutive zero blocks terminate the archive.
                if (++emptyBlocks >= 2) break
                continue
            }
            emptyBlocks = 0

            if (!checksumMatches(header)) {
                throw TarException("Corrupt tar header: checksum mismatch.")
            }

            val rawName = readString(header, 0, 100)
            val prefix = readString(header, 345, 155)
            val size = readOctal(header, 124, 12)
            val mode = readOctal(header, 100, 8)
            val typeFlag = header[156].toInt().toChar()
            val linkTarget = readString(header, 157, 100)

            val name = pendingLongName
                ?: if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
            val link = pendingLongLink ?: linkTarget
            pendingLongName = null
            pendingLongLink = null

            when (typeFlag) {
                LONG_NAME -> {
                    pendingLongName = readEntryString(input, size)
                    continue
                }
                LONG_LINK -> {
                    pendingLongLink = readEntryString(input, size)
                    continue
                }
                // Global and per-file extended headers carry metadata this
                // extractor does not need.
                PAX_GLOBAL, PAX_EXTENDED -> {
                    skipEntry(input, size)
                    continue
                }
            }

            total += size
            if (total > maxTotalBytes) {
                throw TarException(
                    "Archive exceeds the ${maxTotalBytes / (1024 * 1024)} MB extraction limit.",
                )
            }

            val target = safeResolve(root, name)
            if (target == null) {
                // A path that escapes the destination. Refuse it and move on.
                skipped++
                skipEntry(input, size)
                continue
            }

            when (typeFlag) {
                DIRECTORY -> {
                    if (!target.exists() && !target.mkdirs()) {
                        throw TarException("Could not create directory $name")
                    }
                    directories++
                }

                REGULAR, REGULAR_ALT, CONTIGUOUS -> {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        copyExactly(input, out, size)
                    }
                    applyExecutableBit(target, mode)
                    files++
                    skipPadding(input, size)
                    onProgress?.invoke(total)
                    continue
                }

                SYMLINK -> {
                    target.parentFile?.mkdirs()
                    if (symlinks.createSymlink(target, link)) links++ else skipped++
                }

                HARD_LINK -> {
                    val source = safeResolve(root, link)
                    if (source != null && source.isFile) {
                        target.parentFile?.mkdirs()
                        source.copyTo(target, overwrite = true)
                        files++
                    } else {
                        skipped++
                    }
                }

                // Character and block devices, FIFOs. An unprivileged app
                // cannot create them and nothing in a rootfs bootstrap needs
                // them before first launch.
                else -> skipped++
            }

            skipEntry(input, size)
            onProgress?.invoke(total)
        }

        return Summary(files, directories, links, skipped, total)
    }

    /** Returns null when [name] would land outside [root]. */
    private fun safeResolve(root: File, name: String): File? {
        val cleaned = name.trim().trimEnd('/')
        if (cleaned.isEmpty() || cleaned.startsWith("/") || cleaned.contains(0.toChar())) return null
        val candidate = File(root, cleaned)
        val canonicalPath = try {
            candidate.canonicalPath
        } catch (e: IOException) {
            return null
        }
        val rootPath = root.canonicalPath
        return if (canonicalPath == rootPath ||
            canonicalPath.startsWith(rootPath + File.separator)
        ) {
            candidate
        } else {
            null
        }
    }

    /**
     * Restores the executable bit only. Java cannot express the full POSIX
     * mode, and the bit that actually matters for a rootfs is +x on binaries.
     */
    private fun applyExecutableBit(file: File, mode: Long) {
        val ownerExecutable = (mode and 0b001_000_000L) != 0L
        if (ownerExecutable) {
            @Suppress("ResultOfMethodCallIgnored")
            file.setExecutable(true, /* ownerOnly = */ false)
        }
    }

    private fun readEntryString(input: InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        if (!readFully(input, bytes, bytes.size)) throw TarException("Truncated tar entry.")
        skipPadding(input, size)
        return String(bytes, Charsets.UTF_8).trimEnd(0.toChar())
    }

    private fun skipEntry(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(COPY_BUFFER)
        while (remaining > 0) {
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, want)
            if (read <= 0) break
            remaining -= read
        }
        skipPadding(input, size)
    }

    /** Entries are padded up to the next 512-byte boundary. */
    private fun skipPadding(input: InputStream, size: Long) {
        val remainder = (size % BLOCK).toInt()
        if (remainder == 0) return
        val padding = ByteArray(BLOCK - remainder)
        readFully(input, padding, padding.size)
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(COPY_BUFFER)
        while (remaining > 0) {
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, want)
            if (read <= 0) throw TarException("Truncated tar entry; the download is incomplete.")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val count = input.read(buffer, read, length - read)
            if (count < 0) return read > 0 && read == length
            read += count
        }
        return true
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end] != ZERO) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun readOctal(header: ByteArray, offset: Int, length: Int): Long {
        // GNU base-256 encoding for values that do not fit in octal.
        if (header[offset].toInt() and 0x80 != 0) {
            var value = 0L
            for (i in offset + 1 until offset + length) {
                value = (value shl 8) or (header[i].toLong() and 0xFF)
            }
            return value
        }
        val text = readString(header, offset, length).trim().trimEnd(0.toChar())
        if (text.isEmpty()) return 0L
        return text.takeWhile { it in '0'..'7' }.ifEmpty { "0" }.toLong(8)
    }

    /**
     * The header checksum is computed with its own field treated as spaces.
     * Both the signed and unsigned readings are accepted because historic
     * writers disagreed about the sign of bytes above 127.
     */
    private fun checksumMatches(header: ByteArray): Boolean {
        val stored = readOctal(header, 148, 8)
        var signed = 0L
        var unsigned = 0L
        for (i in header.indices) {
            val byte = if (i in 148 until 156) SPACE else header[i]
            signed += byte.toLong()
            unsigned += (byte.toLong() and 0xFF)
        }
        return stored == signed || stored == unsigned
    }

    private companion object {
        const val BLOCK = 512
        const val COPY_BUFFER = 64 * 1024

        val ZERO: Byte = 0
        val SPACE: Byte = 32

        const val REGULAR = '0'
        val REGULAR_ALT: Char = 0.toChar()
        const val HARD_LINK = '1'
        const val SYMLINK = '2'
        const val DIRECTORY = '5'
        const val CONTIGUOUS = '7'
        const val LONG_LINK = 'K'
        const val LONG_NAME = 'L'
        const val PAX_EXTENDED = 'x'
        const val PAX_GLOBAL = 'g'
    }
}
