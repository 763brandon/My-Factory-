package com.myfactory.forge.runtime.proot

import com.myfactory.forge.core.archive.TarExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Progress reported to the bootstrap screen. */
sealed interface BootstrapProgress {
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : BootstrapProgress
    data object Verifying : BootstrapProgress
    data class Extracting(val bytesWritten: Long, val totalBytes: Long) : BootstrapProgress
    data class Finished(val rootfsDir: File) : BootstrapProgress
    data class Failed(val message: String, val recoverable: Boolean) : BootstrapProgress
}

/**
 * Downloads, verifies and unpacks a Linux rootfs.
 *
 * The order is download, then verify, then extract, and never anything else.
 * Extracting first and checking afterwards would mean executing unverified
 * code on the user's device, which is exactly what the checksum is for.
 */
class RootfsBootstrapper(
    private val runtime: LinuxRuntime,
    private val client: OkHttpClient = OkHttpClient(),
    private val symlinks: TarExtractor.SymlinkHandler = AndroidSymlinkHandler,
) {

    fun install(image: RootfsImage): Flow<BootstrapProgress> = flow {
        if (image.sha256 == RootfsCatalog.PLACEHOLDER_SHA) {
            emit(
                BootstrapProgress.Failed(
                    "This build has no verified download for ${image.displayName}. " +
                        "A rootfs is executable code, so it is not fetched without a " +
                        "checksum. See docs/NATIVE_BINARIES.md.",
                    recoverable = false,
                ),
            )
            return@flow
        }

        val target = runtime.rootfsDir(image)
        val download = File(runtime.rootfsParent, "${image.id}.tar.gz.part")
        download.parentFile?.mkdirs()

        try {
            // ---------------------------------------------------------- fetch
            val request = Request.Builder().url(image.url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(
                        BootstrapProgress.Failed(
                            "The download failed with HTTP ${response.code}.",
                            recoverable = true,
                        ),
                    )
                    return@flow
                }
                val body = response.body ?: run {
                    emit(BootstrapProgress.Failed("The server sent no data.", recoverable = true))
                    return@flow
                }
                val total = body.contentLength().takeIf { it > 0 } ?: image.downloadBytes

                body.byteStream().use { input ->
                    download.outputStream().buffered().use { output ->
                        val buffer = ByteArray(BUFFER)
                        var read = 0L
                        var lastReport = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            read += count
                            // Reporting every chunk would recompose faster than
                            // the screen refreshes and cost more than the copy.
                            if (read - lastReport > PROGRESS_STEP) {
                                lastReport = read
                                emit(BootstrapProgress.Downloading(read, total))
                            }
                        }
                        emit(BootstrapProgress.Downloading(read, total))
                    }
                }
            }

            // --------------------------------------------------------- verify
            emit(BootstrapProgress.Verifying)
            val actual = sha256Of(download)
            if (!actual.equals(image.sha256, ignoreCase = true)) {
                download.delete()
                emit(
                    BootstrapProgress.Failed(
                        "The download did not match its published checksum and was deleted. " +
                            "Expected ${image.sha256.take(16)}..., got ${actual.take(16)}...",
                        recoverable = true,
                    ),
                )
                return@flow
            }

            // -------------------------------------------------------- extract
            if (target.exists()) target.deleteRecursively()
            target.mkdirs()

            emit(BootstrapProgress.Extracting(0, image.installedBytes))
            val extractor = TarExtractor(symlinks = symlinks)
            val summary = download.inputStream().buffered(BUFFER).use { input ->
                extractor.extractGzip(input, target)
            }
            download.delete()

            runtime.markInstalled(image)
            emit(BootstrapProgress.Extracting(summary.totalBytes, summary.totalBytes))
            emit(BootstrapProgress.Finished(target))
        } catch (e: TarExtractor.TarException) {
            download.delete()
            target.deleteRecursively()
            emit(BootstrapProgress.Failed(e.message ?: "The archive is damaged.", recoverable = true))
        } catch (e: IOException) {
            download.delete()
            emit(
                BootstrapProgress.Failed(
                    e.message ?: "The download was interrupted.",
                    recoverable = true,
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    /** Removes an installed rootfs and reports how much was freed. */
    fun uninstall(image: RootfsImage): Long {
        val dir = runtime.rootfsDir(image)
        val size = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        dir.deleteRecursively()
        return size
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            HEX[value ushr 4].toString() + HEX[value and 0x0F]
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val PROGRESS_STEP = 512 * 1024L
        const val HEX = "0123456789abcdef"
    }
}

/**
 * Creates symlinks with android.system.Os, which is available from API 21.
 * java.nio.file.Files is API 26+ and would exclude Android 7.
 */
object AndroidSymlinkHandler : TarExtractor.SymlinkHandler {
    override fun createSymlink(link: File, target: String): Boolean = try {
        if (link.exists()) link.delete()
        android.system.Os.symlink(target, link.absolutePath)
        true
    } catch (e: android.system.ErrnoException) {
        false
    } catch (e: Exception) {
        false
    }
}
