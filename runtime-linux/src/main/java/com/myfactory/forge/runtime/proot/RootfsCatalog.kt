package com.myfactory.forge.runtime.proot

import com.myfactory.forge.core.capability.Abi
import com.myfactory.forge.core.capability.LinuxStrategy

/**
 * A downloadable Linux userspace image.
 *
 * [sha256] is mandatory. A rootfs is code that will execute on the user's
 * device, so an unverified download is not an option; a mismatch aborts the
 * bootstrap and deletes the file.
 */
data class RootfsImage(
    val id: String,
    val displayName: String,
    val abi: Abi,
    val strategy: LinuxStrategy,
    val url: String,
    val sha256: String,
    val downloadBytes: Long,
    val installedBytes: Long,
    /** Package manager, shown in the UI so the user knows apt from apk. */
    val packageManager: String,
    val defaultShell: String = "/bin/sh",
)

/**
 * The images this build knows about.
 *
 * URLs and checksums are declared here rather than hard-coded across the app
 * so that a fork, a mirror, or an offline install can replace one table.
 *
 * The checksums below are placeholders in the open-source tree: publishing a
 * checksum for a file this repository does not host would be a promise it
 * cannot keep. `tools/refresh-rootfs-catalog.sh` regenerates this table
 * against a mirror you control, and [isProvisioned] reports false until it
 * has been run, which the UI surfaces rather than attempting a download that
 * would fail verification. See docs/NATIVE_BINARIES.md.
 */
object RootfsCatalog {

    const val PLACEHOLDER_SHA = "0000000000000000000000000000000000000000000000000000000000000000"

    private const val MIB = 1024L * 1024L

    val images: List<RootfsImage> = listOf(
        RootfsImage(
            id = "ubuntu-22.04-arm64",
            displayName = "Ubuntu 22.04 (64-bit)",
            abi = Abi.ARM64,
            strategy = LinuxStrategy.PROOT_UBUNTU,
            url = "https://example.invalid/forge/ubuntu-22.04-arm64.tar.gz",
            sha256 = PLACEHOLDER_SHA,
            downloadBytes = 90 * MIB,
            installedBytes = 1400 * MIB,
            packageManager = "apt",
            defaultShell = "/bin/bash",
        ),
        RootfsImage(
            id = "ubuntu-22.04-armhf",
            displayName = "Ubuntu 22.04 (32-bit armhf)",
            abi = Abi.ARMV7,
            strategy = LinuxStrategy.PROOT_UBUNTU,
            url = "https://example.invalid/forge/ubuntu-22.04-armhf.tar.gz",
            sha256 = PLACEHOLDER_SHA,
            downloadBytes = 82 * MIB,
            installedBytes = 1250 * MIB,
            packageManager = "apt",
            defaultShell = "/bin/bash",
        ),
        RootfsImage(
            id = "alpine-3.20-arm64",
            displayName = "Alpine 3.20 (64-bit)",
            abi = Abi.ARM64,
            strategy = LinuxStrategy.PROOT_ALPINE,
            url = "https://example.invalid/forge/alpine-3.20-arm64.tar.gz",
            sha256 = PLACEHOLDER_SHA,
            downloadBytes = 4 * MIB,
            installedBytes = 190 * MIB,
            packageManager = "apk",
        ),
        RootfsImage(
            id = "alpine-3.20-armhf",
            displayName = "Alpine 3.20 (32-bit armhf)",
            abi = Abi.ARMV7,
            strategy = LinuxStrategy.PROOT_ALPINE,
            url = "https://example.invalid/forge/alpine-3.20-armhf.tar.gz",
            sha256 = PLACEHOLDER_SHA,
            downloadBytes = 4 * MIB,
            installedBytes = 180 * MIB,
            packageManager = "apk",
        ),
        RootfsImage(
            id = "alpine-3.20-x86_64",
            displayName = "Alpine 3.20 (x86_64)",
            abi = Abi.X86_64,
            strategy = LinuxStrategy.PROOT_ALPINE,
            url = "https://example.invalid/forge/alpine-3.20-x86_64.tar.gz",
            sha256 = PLACEHOLDER_SHA,
            downloadBytes = 4 * MIB,
            installedBytes = 190 * MIB,
            packageManager = "apk",
        ),
    )

    /** True once a real mirror and checksum have been configured for this build. */
    val isProvisioned: Boolean
        get() = images.none { it.sha256 == PLACEHOLDER_SHA }

    fun forDevice(abi: Abi, strategy: LinuxStrategy): RootfsImage? {
        if (!strategy.usesProot) return null
        return images.firstOrNull { it.abi == abi && it.strategy == strategy }
            // A 64-bit device can run the 32-bit image if the 64-bit one is
            // missing, which keeps a partial mirror usable.
            ?: images.firstOrNull { it.strategy == strategy && !it.abi.is64Bit && abi.is64Bit }
    }

    fun byId(id: String): RootfsImage? = images.firstOrNull { it.id == id }
}
