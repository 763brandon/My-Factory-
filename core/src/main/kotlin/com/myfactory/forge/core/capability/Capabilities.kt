package com.myfactory.forge.core.capability

/**
 * Which of the two product tiers this device gets.
 *
 * The tiers exist to protect low-end hardware, not to gate features by OS
 * version: the OS floor is uniform at Android 7. A LIGHTWEIGHT device still
 * gets the agent, the editor, diff review and checkpoints. What it loses is
 * the heavy Linux userspace.
 */
enum class Tier { FULL, LIGHTWEIGHT }

/** How, if at all, this device can run Linux commands locally. */
enum class LinuxStrategy {
    /** Full Ubuntu 22.04 rootfs under PRoot. Node, Git, Python, apt. */
    PROOT_UBUNTU,

    /** Alpine rootfs under PRoot. ~5x smaller, musl, apk. */
    PROOT_ALPINE,

    /** No PRoot. A bundled BusyBox applet set covers basic file operations. */
    BUSYBOX,

    /** No local execution at all. The agent still edits files through the JVM. */
    NONE,
    ;

    val usesProot: Boolean get() = this == PROOT_UBUNTU || this == PROOT_ALPINE
}

/** User-facing escape hatch for when our guess is wrong. */
enum class TierOverride { AUTO, FORCE_FULL, FORCE_LIGHTWEIGHT }

/**
 * The resolved answer, plus a human-readable trail of why. [reasons] is shown
 * verbatim in Settings so a user on an odd device can see what we decided and
 * report it.
 */
data class Capabilities(
    val tier: Tier,
    val primaryAbi: Abi,
    val linuxStrategy: LinuxStrategy,
    val terminalEnabled: Boolean,
    val webPreviewEnabled: Boolean,
    /** Files larger than this open read-only in a plain viewer. */
    val editorFileSizeLimitBytes: Long,
    /** How many characters of streamed text we buffer before recomposing. */
    val streamBatchChars: Int,
    /** Cap on agent turns per request, to bound memory and battery. */
    val maxAgentIterations: Int,
    /** Cap on bytes read back from a single tool call. */
    val maxToolOutputBytes: Int,
    /** Whether to keep syntax highlighting on while typing. */
    val liveSyntaxHighlighting: Boolean,
    val reasons: List<String>,
) {
    val isLightweight: Boolean get() = tier == Tier.LIGHTWEIGHT
}
