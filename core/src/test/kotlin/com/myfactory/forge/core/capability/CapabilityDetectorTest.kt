package com.myfactory.forge.core.capability

import com.myfactory.forge.core.capability.DeviceProfile.Companion.GIB
import com.myfactory.forge.core.capability.DeviceProfile.Companion.MIB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityDetectorTest {

    private fun profile(
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
        ram: Long = 6 * GIB,
        cores: Int = 8,
        lowRam: Boolean = false,
        free: Long = 8 * GIB,
        webView: Boolean = true,
        pty: Boolean = true,
        proot: Boolean = true,
        sdk: Int = 34,
    ) = DeviceProfile(
        supportedAbis = abis,
        totalRamBytes = ram,
        availableRamBytes = ram / 2,
        cpuCores = cores,
        sdkInt = sdk,
        flaggedLowRam = lowRam,
        freeDataBytes = free,
        hasWebView = webView,
        hasPtyLibrary = pty,
        hasProotBinary = proot,
    )

    @Test
    fun `modern arm64 flagship gets the full tier with Ubuntu`() {
        val result = CapabilityDetector.detect(profile())

        assertEquals(Tier.FULL, result.tier)
        assertEquals(Abi.ARM64, result.primaryAbi)
        assertEquals(LinuxStrategy.PROOT_UBUNTU, result.linuxStrategy)
        assertTrue(result.terminalEnabled)
        assertTrue(result.webPreviewEnabled)
    }

    @Test
    fun `32-bit armv7 phone with enough RAM still gets the full tier`() {
        // This is the case the upstream project cannot serve at all. A 32-bit
        // device that meets the RAM bar must get the same product as arm64.
        val result = CapabilityDetector.detect(
            profile(abis = listOf("armeabi-v7a"), ram = 4 * GIB),
        )

        assertEquals(Tier.FULL, result.tier)
        assertEquals(Abi.ARMV7, result.primaryAbi)
        assertEquals(LinuxStrategy.PROOT_UBUNTU, result.linuxStrategy)
        assertTrue(result.terminalEnabled)
    }

    @Test
    fun `2GB entry phone drops to lightweight but keeps a usable product`() {
        val result = CapabilityDetector.detect(
            profile(abis = listOf("armeabi-v7a"), ram = 2 * GIB, cores = 4, free = 3 * GIB),
        )

        assertEquals(Tier.LIGHTWEIGHT, result.tier)
        // Alpine, not Ubuntu: the smaller image is the point of the tier.
        assertEquals(LinuxStrategy.PROOT_ALPINE, result.linuxStrategy)
        assertTrue("agent editing must survive the low tier", result.editorFileSizeLimitBytes > 0)
        assertTrue(result.maxAgentIterations >= 5)
        assertFalse(result.liveSyntaxHighlighting)
    }

    @Test
    fun `vendor low-RAM flag overrides a generous RAM report`() {
        val result = CapabilityDetector.detect(profile(ram = 6 * GIB, lowRam = true))

        assertEquals(Tier.LIGHTWEIGHT, result.tier)
        assertTrue(result.reasons.any { it.contains("low-RAM") })
    }

    @Test
    fun `missing proot binary degrades to busybox rather than failing`() {
        val result = CapabilityDetector.detect(profile(proot = false))

        assertEquals(Tier.FULL, result.tier)
        assertEquals(LinuxStrategy.BUSYBOX, result.linuxStrategy)
        assertTrue(result.reasons.any { it.contains("PRoot binary not present") })
    }

    @Test
    fun `no pty library turns the terminal off without affecting anything else`() {
        val result = CapabilityDetector.detect(profile(pty = false))

        assertFalse(result.terminalEnabled)
        assertTrue(result.webPreviewEnabled)
        assertEquals(Tier.FULL, result.tier)
    }

    @Test
    fun `no webview turns preview off and says so`() {
        val result = CapabilityDetector.detect(profile(webView = false))

        assertFalse(result.webPreviewEnabled)
        assertTrue(result.reasons.any { it.contains("WebView") })
    }

    @Test
    fun `almost no free space still yields a working agent`() {
        val result = CapabilityDetector.detect(profile(free = 40 * MIB))

        assertEquals(Tier.LIGHTWEIGHT, result.tier)
        assertEquals(LinuxStrategy.NONE, result.linuxStrategy)
        assertFalse(result.terminalEnabled)
        // The product still exists: chat, edit, diff, checkpoint.
        assertTrue(result.maxAgentIterations > 0)
    }

    @Test
    fun `32-bit x86 avoids proot because ptrace is unreliable there`() {
        val result = CapabilityDetector.detect(profile(abis = listOf("x86"), ram = 4 * GIB))

        assertEquals(Abi.X86, result.primaryAbi)
        assertEquals(LinuxStrategy.BUSYBOX, result.linuxStrategy)
    }

    @Test
    fun `x86_64 emulator gets the full tier so the flow can be tested in CI`() {
        val result = CapabilityDetector.detect(
            profile(abis = listOf("x86_64", "x86"), ram = 4 * GIB),
        )

        assertEquals(Tier.FULL, result.tier)
        assertEquals(Abi.X86_64, result.primaryAbi)
        assertTrue(result.linuxStrategy.usesProot)
    }

    @Test
    fun `dual core device stays lightweight even with plenty of RAM`() {
        val result = CapabilityDetector.detect(profile(ram = 4 * GIB, cores = 2))

        assertEquals(Tier.LIGHTWEIGHT, result.tier)
        assertTrue(result.reasons.any { it.contains("CPU cores") })
    }

    @Test
    fun `manual override wins over detection in both directions`() {
        val weak = profile(ram = 1 * GIB, lowRam = true)
        assertEquals(
            Tier.FULL,
            CapabilityDetector.detect(weak, TierOverride.FORCE_FULL).tier,
        )
        assertEquals(
            Tier.LIGHTWEIGHT,
            CapabilityDetector.detect(profile(), TierOverride.FORCE_LIGHTWEIGHT).tier,
        )
    }

    @Test
    fun `unknown abi never crashes and never claims a linux runtime`() {
        val result = CapabilityDetector.detect(profile(abis = listOf("mips64")))

        assertEquals(Abi.UNKNOWN, result.primaryAbi)
        assertEquals(Tier.LIGHTWEIGHT, result.tier)
        assertFalse(result.linuxStrategy.usesProot)
    }

    @Test
    fun `the conservative unknown profile is safe to use before detection runs`() {
        val result = CapabilityDetector.detect(DeviceProfile.UNKNOWN)

        assertEquals(Tier.LIGHTWEIGHT, result.tier)
        assertFalse(result.terminalEnabled)
        assertFalse(result.webPreviewEnabled)
    }

    @Test
    fun `abi ranking follows the device's own preference order`() {
        assertEquals(Abi.ARM64, Abi.bestOf(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(Abi.ARMV7, Abi.bestOf(listOf("armeabi-v7a")))
        assertEquals(Abi.UNKNOWN, Abi.bestOf(emptyList()))
        assertEquals(Abi.ARMV7, Abi.bestOf(listOf("mips", "armeabi-v7a")))
    }
}
