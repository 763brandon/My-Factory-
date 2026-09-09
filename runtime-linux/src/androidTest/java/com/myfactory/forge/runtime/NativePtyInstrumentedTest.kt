package com.myfactory.forge.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myfactory.forge.runtime.pty.NativePty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the native PTY on a real device.
 *
 * The 32-bit build is the higher-risk target, so this must be run on an
 * armeabi-v7a device or AVD as well as arm64. Run with:
 *
 *   ./gradlew :runtime-linux:connectedDebugAndroidTest
 *
 * These tests use /system/bin/sh, which is present on every Android device
 * and needs no rootfs, so they check the JNI layer rather than PRoot.
 */
@RunWith(AndroidJUnit4::class)
class NativePtyInstrumentedTest {

    private val newline: String = 10.toChar().toString()

    @Test
    fun theNativeLibraryLoadsOnThisAbi() {
        assertTrue(
            "libforgepty.so failed to load: ${NativePty.loadFailure}",
            NativePty.isAvailable,
        )
    }

    @Test
    fun theLibraryMatchesTheProcessWordSize() {
        val expected = if (android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() &&
            android.os.Process.is64Bit()
        ) {
            64
        } else {
            32
        }

        assertEquals(
            "the loaded .so does not match the process ABI",
            expected,
            NativePty.nativeWordSize(),
        )
    }

    @Test
    fun aShellStartsAndEchoesBack() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = com.myfactory.forge.runtime.pty.TerminalSession(scope)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val started = session.start(
            executable = "/system/bin/sh",
            arguments = emptyList(),
            environment = mapOf(
                "TERM" to "xterm-256color",
                "HOME" to context.filesDir.absolutePath,
                "PATH" to "/system/bin:/system/xbin",
            ),
            workingDirectory = context.filesDir.absolutePath,
        )
        assertTrue("the shell did not start: ${session.failureMessage}", started)

        val collected = StringBuilder()
        val collector = scope.launch {
            session.output.collect { collected.append(it) }
        }

        session.write("echo forge-pty-ok" + newline)

        val sawEcho = withTimeoutOrNull(10_000) {
            while (!collected.contains("forge-pty-ok")) {
                kotlinx.coroutines.delay(100)
            }
            true
        }

        collector.cancel()
        session.terminate()

        assertTrue("the shell never echoed the command back", sawEcho == true)
    }

    @Test
    fun resizingDoesNotThrowWhileTheShellIsRunning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = com.myfactory.forge.runtime.pty.TerminalSession(scope)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val started = session.start(
            executable = "/system/bin/sh",
            arguments = emptyList(),
            environment = mapOf("PATH" to "/system/bin"),
            workingDirectory = context.filesDir.absolutePath,
            rows = 24,
            columns = 80,
        )
        assertTrue(started)

        // Rotation and the soft keyboard both trigger this path.
        session.resize(40, 120)
        session.resize(20, 60, pixelWidth = 1080, pixelHeight = 1920)

        session.terminate()
    }

    @Test
    fun terminatingAnAlreadyDeadSessionIsSafe() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = com.myfactory.forge.runtime.pty.TerminalSession(scope)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        session.start(
            executable = "/system/bin/sh",
            arguments = listOf("-c", "exit 3"),
            environment = mapOf("PATH" to "/system/bin"),
            workingDirectory = context.filesDir.absolutePath,
        )
        kotlinx.coroutines.delay(1500)

        // Double termination must not close a recycled descriptor.
        session.terminate()
        session.terminate()
    }

    @Test
    fun startingAMissingExecutableFailsCleanly() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = com.myfactory.forge.runtime.pty.TerminalSession(scope)

        // forkpty succeeds and the child fails to exec, which the parent sees
        // as output on the PTY rather than as an exception. Either way, no
        // crash and the session is usable enough to report.
        session.start(
            executable = "/does/not/exist",
            arguments = emptyList(),
            environment = emptyMap(),
            workingDirectory = null,
        )
        session.terminate()
    }
}
