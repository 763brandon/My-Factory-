package com.myfactory.forge.platform

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import com.myfactory.forge.core.capability.DeviceProfile
import com.myfactory.forge.runtime.pty.NativePty
import java.io.File

/**
 * Fills in a [DeviceProfile] from the real device.
 *
 * Every reading is defensive. Vendor ROMs return nonsense from these APIs
 * often enough that a crash here would be a crash on exactly the low-end
 * hardware this app is meant to support, so each lookup falls back to a
 * conservative value instead.
 */
object AndroidDeviceProfiler {

    fun profile(context: Context): DeviceProfile {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        val memory = ActivityManager.MemoryInfo()
        runCatching { activityManager?.getMemoryInfo(memory) }

        return DeviceProfile(
            supportedAbis = supportedAbis(),
            totalRamBytes = memory.totalMem.takeIf { it > 0 } ?: readMemTotal(),
            availableRamBytes = memory.availMem.takeIf { it > 0 } ?: 0L,
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            sdkInt = Build.VERSION.SDK_INT,
            flaggedLowRam = runCatching { activityManager?.isLowRamDevice }.getOrNull() ?: false,
            freeDataBytes = freeBytes(context.filesDir),
            hasWebView = hasWebView(context),
            hasPtyLibrary = NativePty.isAvailable,
            hasProotBinary = hasProotBinary(context),
        )
    }

    private fun supportedAbis(): List<String> =
        Build.SUPPORTED_ABIS?.toList()?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(
                @Suppress("DEPRECATION") Build.CPU_ABI,
                @Suppress("DEPRECATION") Build.CPU_ABI2,
            ).filter { it.isNotBlank() }

    /** Fallback for devices where MemoryInfo.totalMem reads zero. */
    private fun readMemTotal(): Long = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemTotal:") }
                ?.filter { it.isDigit() }
                ?.toLongOrNull()
                ?.times(1024L)
        }
    }.getOrNull() ?: (2L * 1024 * 1024 * 1024)

    private fun freeBytes(dir: File): Long = runCatching {
        val stat = StatFs(dir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    /**
     * The presence of the WebView package is not enough: it can be present and
     * disabled, or present and broken. This checks that the provider resolves
     * and reports a version.
     */
    private fun hasWebView(context: Context): Boolean = runCatching {
        // WebViewCompat resolves the provider on API 24 and 25 too, where the
        // framework call does not exist and the package name varies by vendor.
        androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context) != null
    }.getOrElse {
        runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW)
        }.getOrDefault(false)
    }

    /**
     * PRoot ships as libproot.so inside jniLibs, because the native library
     * directory is the only place an app may still execute from on API 29+.
     */
    private fun hasProotBinary(context: Context): Boolean = runCatching {
        File(context.applicationInfo.nativeLibraryDir, "libproot.so").canExecute()
    }.getOrDefault(false)
}
