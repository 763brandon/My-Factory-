package com.myfactory.forge.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myfactory.forge.core.capability.Abi
import com.myfactory.forge.core.capability.LinuxStrategy
import com.myfactory.forge.runtime.proot.LinuxRuntime
import com.myfactory.forge.runtime.proot.RootfsCatalog
import com.myfactory.forge.runtime.proot.RuntimeAvailability
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The degradation contract, on a device.
 *
 * These builds ship without PRoot binaries, so on any current device the
 * runtime should report an honest reduced state and never throw. That is the
 * behaviour the README promises, so it is tested rather than assumed.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeDegradationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun availabilityIsAlwaysAnswerableAndNeverThrows() {
        for (abi in Abi.entries) {
            for (strategy in LinuxStrategy.entries) {
                val runtime = LinuxRuntime(context, abi, strategy)
                val availability = runtime.availability()
                assertTrue(
                    "unexpected availability for $abi / $strategy",
                    availability is RuntimeAvailability.Ready ||
                        availability is RuntimeAvailability.NeedsBootstrap ||
                        availability is RuntimeAvailability.BusyBoxOnly ||
                        availability is RuntimeAvailability.Unavailable,
                )
            }
        }
    }

    @Test
    fun aMissingProotBinaryDegradesRatherThanFailing() {
        val runtime = LinuxRuntime(context, Abi.ARM64, LinuxStrategy.PROOT_UBUNTU)

        if (runtime.prootBinary == null) {
            val availability = runtime.availability()
            assertTrue(
                "a missing PRoot binary must degrade, not crash",
                availability is RuntimeAvailability.BusyBoxOnly ||
                    availability is RuntimeAvailability.Unavailable,
            )
        }
    }

    @Test
    fun theCatalogRefusesToPromiseAnUnverifiedImage() {
        // Until a build points the catalog at a real mirror with real
        // checksums, the UI must say so rather than start a download that
        // cannot be verified.
        if (!RootfsCatalog.isProvisioned) {
            assertTrue(
                RootfsCatalog.images.any { it.sha256 == RootfsCatalog.PLACEHOLDER_SHA },
            )
        }
    }

    @Test
    fun theTerminalEnvironmentPointsInsideTheApp() {
        val runtime = LinuxRuntime(context, Abi.ARM64, LinuxStrategy.PROOT_ALPINE)
        val environment = runtime.terminalEnvironment()

        // Nothing may point at shared storage or another app's data.
        assertTrue(environment["HOME"]!!.startsWith(context.filesDir.absolutePath))
        assertTrue(environment["TMPDIR"]!!.startsWith(context.cacheDir.absolutePath))
    }
}
