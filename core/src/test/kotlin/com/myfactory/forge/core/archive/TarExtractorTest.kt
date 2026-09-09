package com.myfactory.forge.core.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class TarExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Builds a tar archive by hand so the tests do not depend on a tar binary. */
    private class TarBuilder {
        private val out = ByteArrayOutputStream()

        fun addFile(name: String, content: String, mode: Int = 420 /* 0644 */): TarBuilder {
            val bytes = content.toByteArray()
            out.write(header(name, bytes.size.toLong(), '0', mode, ""))
            out.write(bytes)
            pad(bytes.size)
            return this
        }

        fun addDirectory(name: String): TarBuilder {
            out.write(header(name, 0, '5', 493 /* 0755 */, ""))
            return this
        }

        fun addSymlink(name: String, target: String): TarBuilder {
            out.write(header(name, 0, '2', 511 /* 0777 */, target))
            return this
        }

        fun build(): ByteArray {
            out.write(ByteArray(1024)) // two zero blocks terminate the archive
            return out.toByteArray()
        }

        fun buildGzipped(): ByteArray {
            val raw = build()
            val compressed = ByteArrayOutputStream()
            GZIPOutputStream(compressed).use { it.write(raw) }
            return compressed.toByteArray()
        }

        private fun pad(size: Int) {
            val remainder = size % 512
            if (remainder != 0) out.write(ByteArray(512 - remainder))
        }

        private fun header(
            name: String,
            size: Long,
            type: Char,
            mode: Int,
            linkTarget: String,
        ): ByteArray {
            val block = ByteArray(512)
            fun put(text: String, offset: Int, length: Int) {
                val bytes = text.toByteArray()
                System.arraycopy(bytes, 0, block, offset, minOf(bytes.size, length - 1))
            }
            put(name, 0, 100)
            put(mode.toString(8).padStart(7, '0'), 100, 8)
            put("0000000", 108, 8)
            put("0000000", 116, 8)
            put(size.toString(8).padStart(11, '0'), 124, 12)
            put("00000000000", 136, 12)
            block[156] = type.code.toByte()
            put(linkTarget, 157, 100)
            put("ustar", 257, 6)
            put("00", 263, 3)

            // The checksum field is treated as spaces while it is computed.
            for (i in 148 until 156) block[i] = 32
            var sum = 0
            for (byte in block) sum += (byte.toInt() and 0xFF)
            put(sum.toString(8).padStart(6, '0'), 148, 8)
            block[154] = 0
            block[155] = 32
            return block
        }
    }

    @Test
    fun `extracts files and directories`() {
        val archive = TarBuilder()
            .addDirectory("rootfs/")
            .addFile("rootfs/etc/hostname", "forge")
            .addFile("rootfs/bin/sh", "#!/bin/sh", mode = 493)
            .build()
        val destination = temp.newFolder("out")

        val summary = TarExtractor().extract(archive.inputStream(), destination)

        assertEquals(2, summary.fileCount)
        assertEquals("forge", File(destination, "rootfs/etc/hostname").readText())
        assertTrue("the executable bit must survive", File(destination, "rootfs/bin/sh").canExecute())
    }

    @Test
    fun `extracts a gzipped archive`() {
        val archive = TarBuilder().addFile("a.txt", "hello").buildGzipped()
        val destination = temp.newFolder("out")

        TarExtractor().extractGzip(archive.inputStream(), destination)

        assertEquals("hello", File(destination, "a.txt").readText())
    }

    @Test
    fun `an entry that escapes the destination is skipped, not written`() {
        // Tar slip. A malicious or corrupt rootfs must not write to the app's
        // own data directory.
        val archive = TarBuilder()
            .addFile("../escaped.txt", "pwned")
            .addFile("/absolute.txt", "pwned")
            .addFile("safe.txt", "fine")
            .build()
        val parent = temp.newFolder("parent")
        val destination = File(parent, "out").apply { mkdirs() }

        val summary = TarExtractor().extract(archive.inputStream(), destination)

        assertEquals(2, summary.skippedCount)
        assertEquals(1, summary.fileCount)
        assertFalse(File(parent, "escaped.txt").exists())
        assertEquals("fine", File(destination, "safe.txt").readText())
    }

    @Test
    fun `symlinks are delegated to the platform handler`() {
        val archive = TarBuilder()
            .addFile("real.txt", "content")
            .addSymlink("link.txt", "real.txt")
            .build()
        val destination = temp.newFolder("out")
        val created = mutableListOf<Pair<String, String>>()
        val handler = object : TarExtractor.SymlinkHandler {
            override fun createSymlink(link: File, target: String): Boolean {
                created += link.name to target
                return true
            }
        }

        val summary = TarExtractor(symlinks = handler).extract(archive.inputStream(), destination)

        assertEquals(1, summary.symlinkCount)
        assertEquals(listOf("link.txt" to "real.txt"), created)
    }

    @Test
    fun `the default handler skips symlinks rather than failing`() {
        val archive = TarBuilder().addSymlink("link", "target").build()

        val summary = TarExtractor().extract(archive.inputStream(), temp.newFolder("out"))

        assertEquals(1, summary.skippedCount)
    }

    @Test
    fun `extraction stops at the byte budget`() {
        val archive = TarBuilder().addFile("big.txt", "x".repeat(4096)).build()

        val thrown = runCatching {
            TarExtractor(maxTotalBytes = 1024).extract(archive.inputStream(), temp.newFolder("out"))
        }.exceptionOrNull()

        assertTrue(thrown is TarExtractor.TarException)
    }

    @Test
    fun `a truncated archive fails loudly instead of writing a partial file`() {
        val full = TarBuilder().addFile("a.txt", "x".repeat(2000)).build()
        val truncated = full.copyOf(700)

        val thrown = runCatching {
            TarExtractor().extract(truncated.inputStream(), temp.newFolder("out"))
        }.exceptionOrNull()

        assertTrue(thrown is TarExtractor.TarException)
    }

    @Test
    fun `a corrupt header checksum is rejected`() {
        val archive = TarBuilder().addFile("a.txt", "x").build()
        archive[0] = 'Z'.code.toByte() // changes the name without fixing the checksum

        val thrown = runCatching {
            TarExtractor().extract(archive.inputStream(), temp.newFolder("out"))
        }.exceptionOrNull()

        assertTrue(thrown is TarExtractor.TarException)
    }

    @Test
    fun `an empty archive extracts nothing and does not fail`() {
        val summary = TarExtractor().extract(ByteArray(1024).inputStream(), temp.newFolder("out"))

        assertEquals(0, summary.fileCount)
        assertEquals(0, summary.directoryCount)
    }
}
