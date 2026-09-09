package com.myfactory.forge.runtime.shell

import com.myfactory.forge.core.agent.ShellRunner
import com.myfactory.forge.runtime.proot.LinuxRuntime
import com.myfactory.forge.runtime.proot.RuntimeAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs one-shot commands for the agent's run_command tool.
 *
 * Separate from [com.myfactory.forge.runtime.pty.TerminalSession] on purpose:
 * the agent wants a command that ends and hands back output, while the
 * terminal wants an interactive session that lives. Using a PTY here would
 * mean parsing escape sequences out of the agent's tool results.
 *
 * The user has already approved this specific command by the time it arrives.
 * That approval is the security boundary; PRoot is not.
 */
class ProotShellRunner(
    private val runtime: LinuxRuntime,
    private val workspaceRoot: File,
) : ShellRunner {

    override fun describeEnvironment(): String = when (val state = runtime.availability()) {
        is RuntimeAvailability.Ready ->
            "${state.image.displayName}, mounted at ${LinuxRuntime.WORKSPACE_MOUNT}"
        is RuntimeAvailability.NeedsBootstrap ->
            "${state.image.displayName} (not installed yet)"
        is RuntimeAvailability.BusyBoxOnly ->
            "BusyBox only: file operations work, package managers do not"
        is RuntimeAvailability.Unavailable -> state.reason
    }

    override suspend fun run(
        command: String,
        workingDirectory: String?,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): ShellRunner.Result = withContext(Dispatchers.IO) {
        val argv = when (val state = runtime.availability()) {
            is RuntimeAvailability.Ready -> runtime.buildProotCommand(
                image = state.image,
                workspaceDir = workspaceRoot,
                command = listOf(state.image.defaultShell, "-lc", withCd(command, workingDirectory)),
            )

            is RuntimeAvailability.BusyBoxOnly -> listOf(
                state.busyBox.absolutePath,
                "sh",
                "-c",
                withCd(command, workingDirectory),
            )

            is RuntimeAvailability.NeedsBootstrap -> throw IOException(
                "The Linux runtime is not installed yet. Open the Terminal tab to set it up.",
            )

            is RuntimeAvailability.Unavailable -> throw IOException(state.reason)
        }

        val builder = ProcessBuilder(argv).apply {
            directory(workspaceRoot)
            environment().clear()
            environment().putAll(runtime.terminalEnvironment())
            redirectErrorStream(false)
        }

        val process = try {
            builder.start()
        } catch (e: IOException) {
            throw IOException("Could not start the command: ${e.message}", e)
        }

        // Read both streams on their own threads. A command that fills the
        // stderr pipe while nobody drains it deadlocks, which looks to the
        // user like a hang with no explanation.
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = drain(process.inputStream, stdout, maxOutputBytes)
        val errThread = drain(process.errorStream, stderr, maxOutputBytes)

        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            outThread.join(DRAIN_JOIN_MS)
            errThread.join(DRAIN_JOIN_MS)
            return@withContext ShellRunner.Result(
                exitCode = 124,
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                timedOut = true,
            )
        }

        outThread.join(DRAIN_JOIN_MS)
        errThread.join(DRAIN_JOIN_MS)

        ShellRunner.Result(
            exitCode = process.exitValue(),
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            timedOut = false,
        )
    }

    /**
     * The tool takes a working directory relative to the project. Inside the
     * rootfs the project is at a fixed mount point, so the cd is prepended
     * rather than passed to ProcessBuilder.
     */
    private fun withCd(command: String, workingDirectory: String?): String {
        if (workingDirectory.isNullOrBlank()) return command
        val safe = workingDirectory.trim().trimStart('/')
        // Quoted so a directory with a space or a quote cannot break out of
        // the cd and become part of the command.
        val quoted = "'" + safe.replace("'", "'" + '"' + "'" + '"' + "'") + "'"
        return "cd $quoted && $command"
    }

    private fun drain(
        stream: java.io.InputStream,
        sink: StringBuilder,
        maxBytes: Int,
    ): Thread = Thread({
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                var total = 0
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (total >= maxBytes) continue // keep draining, stop storing
                    val take = minOf(read, maxBytes - total)
                    sink.appendRange(buffer, 0, take)
                    total += take
                    if (total >= maxBytes) {
                        sink.append(10.toChar()).append("[output truncated at $maxBytes bytes]")
                    }
                }
            }
        } catch (e: IOException) {
            // The process died; whatever was captured is what we report.
        }
    }, "forge-shell-drain").apply {
        isDaemon = true
        start()
    }

    private companion object {
        const val DRAIN_JOIN_MS = 2000L
    }
}
