package com.myfactory.forge.runtime.pty

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One interactive shell on a pseudo-terminal.
 *
 * Output is read on a dedicated thread with a blocking read, which is the only
 * shape that works for a PTY: there is no end-of-stream until the child exits,
 * and polling would either burn battery or add latency to every keystroke.
 */
class TerminalSession(
    private val scope: CoroutineScope,
    private val onExit: (Int) -> Unit = {},
) {

    enum class State { NOT_STARTED, RUNNING, EXITED, FAILED }

    private val _state = MutableStateFlow(State.NOT_STARTED)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Terminal output. Replays the recent past so a screen that is recreated
     * on rotation does not come back blank, but is bounded so a runaway
     * command cannot grow the buffer without limit.
     */
    private val _output = MutableSharedFlow<String>(
        replay = OUTPUT_REPLAY,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val output: SharedFlow<String> = _output.asSharedFlow()

    var exitCode: Int? = null
        private set

    var failureMessage: String? = null
        private set

    private var masterFd: Int = -1
    private var processId: Int = -1
    private var parcelFd: ParcelFileDescriptor? = null
    private var writer: FileOutputStream? = null
    private val closed = AtomicBoolean(false)

    val isRunning: Boolean get() = _state.value == State.RUNNING

    /**
     * @return true if the shell started. False means the device has no
     *   terminal; the caller must show the degraded UI rather than retrying.
     */
    fun start(
        executable: String,
        arguments: List<String>,
        environment: Map<String, String>,
        workingDirectory: String?,
        rows: Int = 24,
        columns: Int = 80,
    ): Boolean {
        if (!NativePty.isAvailable) {
            fail(NativePty.loadFailure ?: "The native terminal library is unavailable.")
            return false
        }
        if (_state.value == State.RUNNING) return true

        val pidHolder = IntArray(1)
        val argv = (listOf(executable) + arguments).toTypedArray()
        val envp = environment.map { (key, value) -> "$key=$value" }.toTypedArray()

        masterFd = try {
            NativePty.createSubprocess(
                executable = executable,
                workingDirectory = workingDirectory,
                arguments = argv,
                environment = envp,
                processIdOut = pidHolder,
                rows = rows,
                columns = columns,
            )
        } catch (e: IOException) {
            fail(e.message ?: "The shell could not be started.")
            return false
        } catch (e: UnsatisfiedLinkError) {
            fail("The native terminal library is not loadable on this device.")
            return false
        }

        if (masterFd < 0) {
            fail("The system refused to allocate a pseudo-terminal.")
            return false
        }

        processId = pidHolder[0]
        val descriptor = ParcelFileDescriptor.adoptFd(masterFd)
        parcelFd = descriptor
        writer = FileOutputStream(descriptor.fileDescriptor)
        _state.value = State.RUNNING

        startReadLoop(descriptor)
        startReaper()
        return true
    }

    private fun startReadLoop(descriptor: ParcelFileDescriptor) {
        Thread({
            val input = FileInputStream(descriptor.fileDescriptor)
            val buffer = ByteArray(READ_BUFFER)
            try {
                while (!closed.get()) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    // Decoding per chunk can split a UTF-8 sequence. Replacing
                    // the broken bytes is the right trade against buffering an
                    // unbounded partial character.
                    _output.tryEmit(String(buffer, 0, read, Charsets.UTF_8))
                }
            } catch (e: IOException) {
                // Expected: the descriptor closes when the child exits.
            }
        }, "forge-pty-read").apply { isDaemon = true }.start()
    }

    private fun startReaper() {
        scope.launch(Dispatchers.IO) {
            val code = runCatching { NativePty.waitFor(processId) }.getOrDefault(-1)
            exitCode = code
            if (_state.value == State.RUNNING) _state.value = State.EXITED
            cleanUp()
            onExit(code)
        }
    }

    fun write(text: String) {
        if (!isRunning) return
        runCatching {
            writer?.apply {
                write(text.toByteArray(Charsets.UTF_8))
                flush()
            }
        }
    }

    /** Called on rotation and when the soft keyboard changes the viewport. */
    fun resize(rows: Int, columns: Int, pixelWidth: Int = 0, pixelHeight: Int = 0) {
        if (!isRunning || masterFd < 0) return
        runCatching { NativePty.setWindowSize(masterFd, rows, columns, pixelWidth, pixelHeight) }
    }

    /** Ctrl-C. Interrupts the foreground job without killing the shell. */
    fun interrupt() {
        if (isRunning && processId > 0) {
            runCatching { NativePty.sendSignal(processId, NativePty.SIGINT) }
        }
    }

    fun terminate() {
        if (processId > 0) {
            runCatching { NativePty.sendSignal(processId, NativePty.SIGTERM) }
        }
        cleanUp()
    }

    private fun cleanUp() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { writer?.close() }
        // ParcelFileDescriptor owns the fd after adoptFd; closing it twice
        // would close whatever descriptor the number was reused for.
        runCatching { parcelFd?.close() }
        writer = null
        parcelFd = null
        masterFd = -1
    }

    private fun fail(message: String) {
        failureMessage = message
        _state.value = State.FAILED
    }

    private companion object {
        const val READ_BUFFER = 8 * 1024
        const val OUTPUT_REPLAY = 64
    }
}
