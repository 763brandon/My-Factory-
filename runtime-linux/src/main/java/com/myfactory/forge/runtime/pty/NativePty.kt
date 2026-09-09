package com.myfactory.forge.runtime.pty

/**
 * The JNI surface of libforgepty.so.
 *
 * [isAvailable] is the honest answer to "can this device run a terminal". It
 * is false when the library is missing for this ABI, when the loader refused
 * it, or when a vendor ROM has blocked the call. Nothing above this class may
 * assume a terminal exists.
 */
object NativePty {

    /** Null when the library loaded; otherwise why it did not. */
    val loadFailure: String? = try {
        System.loadLibrary("forgepty")
        null
    } catch (e: UnsatisfiedLinkError) {
        e.message ?: "libforgepty.so is not present for this ABI"
    } catch (e: SecurityException) {
        e.message ?: "loading libforgepty.so was refused"
    }

    val isAvailable: Boolean = loadFailure == null

    /** 32 on armeabi-v7a and x86, 64 on arm64-v8a and x86_64. */
    fun nativeWordSize(): Int = if (isAvailable) {
        runCatching { abiWordSize() }.getOrDefault(0)
    } else {
        0
    }

    /**
     * Forks [executable] on a fresh pseudo-terminal.
     *
     * @param processIdOut receives the child's pid in element 0.
     * @return the master file descriptor.
     */
    @JvmStatic
    external fun createSubprocess(
        executable: String,
        workingDirectory: String?,
        arguments: Array<String>,
        environment: Array<String>,
        processIdOut: IntArray,
        rows: Int,
        columns: Int,
    ): Int

    @JvmStatic
    external fun setWindowSize(
        fd: Int,
        rows: Int,
        columns: Int,
        pixelWidth: Int,
        pixelHeight: Int,
    )

    @JvmStatic
    external fun waitFor(processId: Int): Int

    @JvmStatic
    external fun sendSignal(processId: Int, signalNumber: Int)

    @JvmStatic
    external fun closeFd(fd: Int)

    @JvmStatic
    external fun abiWordSize(): Int

    const val SIGHUP = 1
    const val SIGINT = 2
    const val SIGKILL = 9
    const val SIGTERM = 15
}
