package com.myfactory.forge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myfactory.forge.core.capability.CapabilityDetector
import com.myfactory.forge.core.capability.TierOverride
import com.myfactory.forge.platform.AndroidDeviceProfiler
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Profiling reads a dozen platform APIs that vendors get wrong. This confirms
 * that on the device under test it produces a sane profile and a usable tier,
 * whatever those APIs return.
 */
@RunWith(AndroidJUnit4::class)
class CapabilityInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun profilingProducesSaneReadings() {
        val profile = AndroidDeviceProfiler.profile(context)

        assertTrue("no ABI reported", profile.supportedAbis.isNotEmpty())
        assertTrue("RAM reads as zero", profile.totalRamBytes > 0)
        assertTrue("no CPU cores", profile.cpuCores >= 1)
        assertTrue("SDK below the floor", profile.sdkInt >= 24)
    }

    @Test
    fun everyTierOverrideYieldsAUsableConfiguration() {
        val profile = AndroidDeviceProfiler.profile(context)

        for (override in TierOverride.entries) {
            val capabilities = CapabilityDetector.detect(profile, override)

            // The invariant the whole degradation design rests on: the agent
            // is always usable, whatever the hardware or the override.
            assertTrue(capabilities.maxAgentIterations > 0)
            assertTrue(capabilities.editorFileSizeLimitBytes > 0)
            assertTrue(capabilities.maxToolOutputBytes > 0)
        }
    }

    @Test
    fun theDetectedAbiIsOneTheApkShips() {
        val profile = AndroidDeviceProfiler.profile(context)
        val capabilities = CapabilityDetector.detect(profile)

        assertTrue(
            "detected ${capabilities.primaryAbi} which this APK does not ship",
            capabilities.primaryAbi.id in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"),
        )
    }
}
