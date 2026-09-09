package com.myfactory.forge.core.capability

import com.myfactory.forge.core.capability.DeviceProfile.Companion.GIB
import com.myfactory.forge.core.capability.DeviceProfile.Companion.MIB

/**
 * Turns a [DeviceProfile] into a [Capabilities] decision.
 *
 * Design rule: this never throws and never returns "unsupported". The worst
 * outcome is LIGHTWEIGHT with [LinuxStrategy.NONE], which is still a usable
 * product - chat, browse, edit, diff, checkpoint. Every reduction is recorded
 * in [Capabilities.reasons] so the user can see it in Settings.
 */
object CapabilityDetector {

    /** Below this the device does not get a Linux userspace. */
    const val FULL_TIER_MIN_RAM: Long = 3 * GIB

    /** An Ubuntu rootfs unpacks to well over 1 GB; do not start if it cannot fit. */
    const val UBUNTU_MIN_FREE_BYTES: Long = 2200 * MIB

    /** Alpine unpacks to roughly 200 MB with the toolchain we install. */
    const val ALPINE_MIN_FREE_BYTES: Long = 700 * MIB

    fun detect(
        profile: DeviceProfile,
        override: TierOverride = TierOverride.AUTO,
    ): Capabilities {
        val reasons = mutableListOf<String>()
        val abi = profile.primaryAbi

        val tier = resolveTier(profile, override, reasons)
        val linux = resolveLinuxStrategy(profile, tier, reasons)

        val terminal = when {
            !profile.hasPtyLibrary -> {
                reasons += "Terminal off: the native PTY library did not load on this ABI."
                false
            }
            linux == LinuxStrategy.NONE -> {
                reasons += "Terminal off: no local shell is available."
                false
            }
            else -> true
        }

        val webPreview = when {
            !profile.hasWebView -> {
                reasons += "Web preview off: no usable Android System WebView on this device."
                false
            }
            // A 1 GB device running a Chromium renderer next to a Compose UI and
            // a dev server will thrash. Refuse rather than ship an OOM.
            profile.totalRamBytes < 1400 * MIB -> {
                reasons += "Web preview off: under 1.4 GB RAM the renderer competes with the editor."
                false
            }
            else -> true
        }

        val lightweight = tier == Tier.LIGHTWEIGHT
        return Capabilities(
            tier = tier,
            primaryAbi = abi,
            linuxStrategy = linux,
            terminalEnabled = terminal,
            webPreviewEnabled = webPreview,
            editorFileSizeLimitBytes = if (lightweight) 512 * 1024L else 4 * MIB,
            streamBatchChars = if (lightweight) 96 else 24,
            maxAgentIterations = if (lightweight) 12 else 25,
            maxToolOutputBytes = if (lightweight) 48 * 1024 else 192 * 1024,
            liveSyntaxHighlighting = !lightweight,
            reasons = reasons.toList(),
        )
    }

    private fun resolveTier(
        profile: DeviceProfile,
        override: TierOverride,
        reasons: MutableList<String>,
    ): Tier {
        when (override) {
            TierOverride.FORCE_FULL -> {
                reasons += "Tier forced to FULL in Settings; automatic detection bypassed."
                return Tier.FULL
            }
            TierOverride.FORCE_LIGHTWEIGHT -> {
                reasons += "Tier forced to LIGHTWEIGHT in Settings."
                return Tier.LIGHTWEIGHT
            }
            TierOverride.AUTO -> Unit
        }

        if (profile.flaggedLowRam) {
            reasons += "LIGHTWEIGHT: the device reports itself as low-RAM hardware."
            return Tier.LIGHTWEIGHT
        }
        if (profile.totalRamBytes < FULL_TIER_MIN_RAM) {
            reasons += "LIGHTWEIGHT: ${formatBytes(profile.totalRamBytes)} of RAM, below the 3 GB FULL threshold."
            return Tier.LIGHTWEIGHT
        }
        if (profile.primaryAbi == Abi.UNKNOWN) {
            reasons += "LIGHTWEIGHT: unrecognised CPU ABI ${profile.supportedAbis}."
            return Tier.LIGHTWEIGHT
        }
        if (profile.cpuCores <= 2) {
            reasons += "LIGHTWEIGHT: ${profile.cpuCores} CPU cores cannot carry a build inside PRoot."
            return Tier.LIGHTWEIGHT
        }
        if (profile.freeDataBytes < ALPINE_MIN_FREE_BYTES) {
            reasons += "LIGHTWEIGHT: only ${formatBytes(profile.freeDataBytes)} free, too little for any rootfs."
            return Tier.LIGHTWEIGHT
        }
        return Tier.FULL
    }

    private fun resolveLinuxStrategy(
        profile: DeviceProfile,
        tier: Tier,
        reasons: MutableList<String>,
    ): LinuxStrategy {
        if (!profile.hasProotBinary) {
            reasons += "PRoot binary not present for ${profile.primaryAbi.id}; falling back to BusyBox."
            return busyboxOrNone(profile, reasons)
        }
        // PRoot needs ptrace, which the x86 32-bit emulator images historically
        // handle badly. Do not promise a rootfs we cannot drive.
        if (profile.primaryAbi == Abi.X86) {
            reasons += "PRoot is not supported on 32-bit x86; using BusyBox instead."
            return busyboxOrNone(profile, reasons)
        }
        if (profile.primaryAbi == Abi.UNKNOWN) {
            return busyboxOrNone(profile, reasons)
        }

        if (tier == Tier.LIGHTWEIGHT) {
            return if (profile.freeDataBytes >= ALPINE_MIN_FREE_BYTES) {
                reasons += "Using the Alpine rootfs: smaller image for a low-RAM device."
                LinuxStrategy.PROOT_ALPINE
            } else {
                reasons += "Not enough free space for Alpine; using BusyBox."
                busyboxOrNone(profile, reasons)
            }
        }

        return if (profile.freeDataBytes >= UBUNTU_MIN_FREE_BYTES) {
            LinuxStrategy.PROOT_UBUNTU
        } else {
            reasons += "Only ${formatBytes(profile.freeDataBytes)} free; using Alpine rather than Ubuntu."
            LinuxStrategy.PROOT_ALPINE
        }
    }

    private fun busyboxOrNone(profile: DeviceProfile, reasons: MutableList<String>): LinuxStrategy {
        if (profile.freeDataBytes < 64 * MIB) {
            reasons += "No local shell: under 64 MB free. The agent can still edit files."
            return LinuxStrategy.NONE
        }
        return LinuxStrategy.BUSYBOX
    }

    internal fun formatBytes(bytes: Long): String = when {
        bytes >= GIB -> String.format("%.1f GB", bytes.toDouble() / GIB)
        bytes >= MIB -> "${bytes / MIB} MB"
        else -> "$bytes B"
    }
}
