package com.myfactory.forge.core.capability

/**
 * Everything the tier decision needs to know about the machine it is running
 * on, expressed without a single Android import.
 *
 * The app fills this in from ActivityManager and Build; tests fill it in by
 * hand. That separation is the only reason tier selection is unit-testable.
 */
data class DeviceProfile(
    /** Ordered best-first, exactly as [android.os.Build.SUPPORTED_ABIS] reports it. */
    val supportedAbis: List<String>,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val cpuCores: Int,
    val sdkInt: Int,
    /** ActivityManager.isLowRamDevice(); vendors set this on entry hardware. */
    val flaggedLowRam: Boolean,
    /** Free space on the volume that holds the app's private data directory. */
    val freeDataBytes: Long,
    val hasWebView: Boolean,
    /** True once libforgepty.so has actually been loaded, not merely shipped. */
    val hasPtyLibrary: Boolean,
    /** True when a proot binary is present in the native library directory. */
    val hasProotBinary: Boolean,
) {
    val primaryAbi: Abi get() = Abi.bestOf(supportedAbis)

    companion object {
        const val GIB: Long = 1024L * 1024L * 1024L
        const val MIB: Long = 1024L * 1024L

        /**
         * A conservative stand-in used before detection has run, and by unit
         * tests that only care about one field. Deliberately describes a weak
         * device so that any code path taken by default is the safe one.
         */
        val UNKNOWN = DeviceProfile(
            supportedAbis = listOf("armeabi-v7a"),
            totalRamBytes = 2 * GIB,
            availableRamBytes = 512 * MIB,
            cpuCores = 4,
            sdkInt = 24,
            flaggedLowRam = true,
            freeDataBytes = 512 * MIB,
            hasWebView = false,
            hasPtyLibrary = false,
            hasProotBinary = false,
        )
    }
}

/** The ABIs this project ships, ranked. */
enum class Abi(val id: String, val is64Bit: Boolean) {
    ARM64("arm64-v8a", true),
    ARMV7("armeabi-v7a", false),
    X86_64("x86_64", true),
    X86("x86", false),
    UNKNOWN("unknown", false),
    ;

    companion object {
        fun fromId(id: String): Abi = entries.firstOrNull { it.id == id } ?: UNKNOWN

        /**
         * Picks the first recognised ABI in the device's own preference order.
         * A 64-bit phone lists arm64-v8a first and armeabi-v7a after it, so
         * taking the head of the list gives the right answer without us
         * re-ranking anything.
         */
        fun bestOf(abis: List<String>): Abi =
            abis.map(::fromId).firstOrNull { it != UNKNOWN } ?: UNKNOWN
    }
}
