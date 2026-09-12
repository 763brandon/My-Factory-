package com.myfactory.forge.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.myfactory.forge.core.capability.Abi
import com.myfactory.forge.core.capability.CapabilityDetector
import com.myfactory.forge.core.capability.DeviceProfile
import com.myfactory.forge.core.capability.LinuxStrategy
import com.myfactory.forge.runtime.proot.RootfsCatalog
import com.myfactory.forge.runtime.proot.RuntimeAvailability
import com.myfactory.forge.ui.screens.TerminalScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The terminal has four honest outcomes and none of them may be a crash or a
 * blank screen. Each is asserted here, because on most real devices today
 * the app lands in one of the degraded ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val capable = DeviceProfile(
        supportedAbis = listOf("arm64-v8a"),
        totalRamBytes = 6L * 1024 * 1024 * 1024,
        availableRamBytes = 3L * 1024 * 1024 * 1024,
        cpuCores = 8,
        sdkInt = 34,
        flaggedLowRam = false,
        freeDataBytes = 16L * 1024 * 1024 * 1024,
        hasWebView = true,
        hasPtyLibrary = true,
        hasProotBinary = true,
    )

    private class Actions {
        var start = 0
        var bootstrap = 0
    }

    private fun show(
        profile: DeviceProfile = capable,
        availability: RuntimeAvailability,
    ): Actions {
        val actions = Actions()
        compose.render {
            TerminalScreen(
                capabilities = CapabilityDetector.detect(profile),
                availability = availability,
                session = null,
                bootstrapProgress = null,
                onStartShell = { actions.start++ },
                onStopShell = {},
                onInterrupt = {},
                onSendLine = {},
                onBootstrap = { actions.bootstrap++ },
            )
        }
        return actions
    }

    @Test
    fun `no runtime at all explains itself instead of showing a blank screen`() {
        show(availability = RuntimeAvailability.Unavailable("No PRoot binary is bundled."))

        compose.label("No terminal on this device").assertExists()
        compose.labelContaining("No PRoot binary is bundled.").assertExists()
    }

    @Test
    fun `a missing pty library disables the terminal even when proot is present`() {
        show(
            profile = capable.copy(hasPtyLibrary = false),
            availability = RuntimeAvailability.BusyBoxOnly(File("/nonexistent/libbusybox.so")),
        )

        compose.label("No terminal on this device").assertExists()
    }

    @Test
    fun `busybox mode says plainly what does not work`() {
        show(availability = RuntimeAvailability.BusyBoxOnly(File("/data/lib/libbusybox.so")))

        compose.labelContaining("Package managers, Node and Git are not.").assertExists()
        compose.label("Start shell").assertExists()
    }

    @Test
    fun `busybox mode can still start a shell`() {
        val actions = show(
            availability = RuntimeAvailability.BusyBoxOnly(File("/data/lib/libbusybox.so")),
        )

        compose.node("Start shell").performClick()

        assertEquals(1, actions.start)
    }

    @Test
    fun `a ready runtime offers the shell`() {
        val image = RootfsCatalog.forDevice(Abi.ARM64, LinuxStrategy.PROOT_UBUNTU)!!
        val actions = show(
            availability = RuntimeAvailability.Ready(image, File("/data/rootfs/ubuntu")),
        )

        compose.node("Start shell").performClick()

        assertEquals(1, actions.start)
    }

    @Test
    fun `an uninstalled runtime offers setup and names the cost`() {
        val image = RootfsCatalog.forDevice(Abi.ARM64, LinuxStrategy.PROOT_UBUNTU)!!
        show(availability = RuntimeAvailability.NeedsBootstrap(image))

        compose.label("Set up the Linux runtime").assertExists()
        compose.labelContaining(image.displayName).assertExists()
    }

    @Test
    fun `an unprovisioned build refuses to start an unverifiable download`() {
        val image = RootfsCatalog.forDevice(Abi.ARM64, LinuxStrategy.PROOT_UBUNTU)!!
        val actions = show(availability = RuntimeAvailability.NeedsBootstrap(image))

        // This build ships placeholder checksums, so the button must be inert
        // rather than fetching code it cannot verify.
        compose.node("Download and install").assertIsNotEnabled()
        assertEquals(0, actions.bootstrap)
    }
}
