package com.myfactory.forge.runtime.proot

import android.content.Context
import com.myfactory.forge.core.capability.Abi
import com.myfactory.forge.core.capability.LinuxStrategy
import java.io.File

/** Why a Linux runtime is or is not usable on this device, right now. */
sealed interface RuntimeAvailability {
    /** A rootfs is installed and commands can run inside it. */
    data class Ready(val image: RootfsImage, val rootfsDir: File) : RuntimeAvailability

    /** PRoot and an image exist for this device but nothing is installed yet. */
    data class NeedsBootstrap(val image: RootfsImage) : RuntimeAvailability

    /**
     * No PRoot for this device, but BusyBox is present. File operations work;
     * apt, npm and git do not.
     */
    data class BusyBoxOnly(val busyBox: File) : RuntimeAvailability

    /** Nothing runs locally. The agent still edits files through the JVM. */
    data class Unavailable(val reason: String) : RuntimeAvailability
}

/**
 * Locates the native helper binaries and decides what this device can do.
 *
 * The binaries live in jniLibs under `lib*.so` names. That is not cosmetic:
 * since API 29 an app may not execute a file it wrote into its own data
 * directory, and the native library directory is the one place the installer
 * puts files that stay executable. See docs/NATIVE_BINARIES.md for how to
 * build them.
 *
 * PRoot is not a security boundary. It rewrites syscall paths with ptrace so
 * an unprivileged process sees a different filesystem layout. It does not
 * contain a hostile program, and this app does not pretend otherwise: command
 * execution is gated by user approval, not by PRoot.
 */
class LinuxRuntime(
    private val context: Context,
    private val abi: Abi,
    private val strategy: LinuxStrategy,
) {

    private val nativeLibraryDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    val prootBinary: File?
        get() = File(nativeLibraryDir, "libproot.so").takeIf { it.canExecute() }

    val busyBoxBinary: File?
        get() = File(nativeLibraryDir, "libbusybox.so").takeIf { it.canExecute() }

    /** Where an installed rootfs lives. Private to the app. */
    val rootfsParent: File
        get() = File(context.filesDir, "rootfs")

    fun rootfsDir(image: RootfsImage): File = File(rootfsParent, image.id)

    /** A rootfs that only half-extracted must not be treated as installed. */
    fun isInstalled(image: RootfsImage): Boolean {
        val dir = rootfsDir(image)
        return File(dir, INSTALL_MARKER).isFile && File(dir, "bin").isDirectory
    }

    fun markInstalled(image: RootfsImage) {
        File(rootfsDir(image), INSTALL_MARKER).writeText(image.sha256)
    }

    fun availability(): RuntimeAvailability {
        if (strategy == LinuxStrategy.NONE) {
            return RuntimeAvailability.Unavailable(
                "This device does not have room for a Linux runtime.",
            )
        }

        if (strategy == LinuxStrategy.BUSYBOX) {
            val busyBox = busyBoxBinary
                ?: return RuntimeAvailability.Unavailable(
                    "No BusyBox binary is bundled for ${abi.id}.",
                )
            return RuntimeAvailability.BusyBoxOnly(busyBox)
        }

        if (prootBinary == null) {
            val busyBox = busyBoxBinary
            return if (busyBox != null) {
                RuntimeAvailability.BusyBoxOnly(busyBox)
            } else {
                RuntimeAvailability.Unavailable(
                    "No PRoot binary is bundled for ${abi.id}. See docs/NATIVE_BINARIES.md.",
                )
            }
        }

        val image = RootfsCatalog.forDevice(abi, strategy)
            ?: return RuntimeAvailability.Unavailable(
                "No Linux image is published for ${abi.id}.",
            )

        return if (isInstalled(image)) {
            RuntimeAvailability.Ready(image, rootfsDir(image))
        } else {
            RuntimeAvailability.NeedsBootstrap(image)
        }
    }

    /**
     * Builds the PRoot invocation for a rootfs.
     *
     * The bind mounts are what make the environment feel real: /dev, /proc and
     * /sys come from Android, and the user's project is bound at a fixed path
     * so the same command works whatever the app's data directory is called.
     */
    fun buildProotCommand(
        image: RootfsImage,
        workspaceDir: File,
        command: List<String>,
    ): List<String> {
        val proot = prootBinary ?: error("buildProotCommand called with no PRoot binary")
        val rootfs = rootfsDir(image)

        return buildList {
            add(proot.absolutePath)
            // Emulate root inside the rootfs; apt and apk refuse to run without it.
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            // Android's /proc/stat is unreadable to apps; PRoot substitutes it.
            add("-b")
            add("/dev/urandom:/dev/random")
            add("-b")
            add("${workspaceDir.absolutePath}:$WORKSPACE_MOUNT")
            add("-w")
            add(WORKSPACE_MOUNT)
            // Link2symlink keeps hard links working on filesystems that lack them.
            add("--link2symlink")
            add("--kill-on-exit")
            add("/usr/bin/env")
            add("-i")
            add("HOME=/root")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            addAll(command)
        }
    }

    /** The environment handed to the PTY. Deliberately minimal. */
    fun terminalEnvironment(): Map<String, String> = mapOf(
        "TERM" to "xterm-256color",
        "HOME" to context.filesDir.absolutePath,
        "TMPDIR" to context.cacheDir.absolutePath,
        "LANG" to "C.UTF-8",
        "PROOT_TMP_DIR" to context.cacheDir.absolutePath,
        // PRoot writes its loader here; without it, exec fails on API 29+.
        "PROOT_LOADER" to File(nativeLibraryDir, "libloader.so").absolutePath,
    )

    companion object {
        /** Where the user's project appears inside the rootfs. */
        const val WORKSPACE_MOUNT = "/workspace"

        private const val INSTALL_MARKER = ".forge-installed"
    }
}
