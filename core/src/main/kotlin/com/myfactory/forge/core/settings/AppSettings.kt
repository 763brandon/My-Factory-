package com.myfactory.forge.core.settings

import com.myfactory.forge.core.capability.TierOverride
import kotlinx.serialization.Serializable

/**
 * User preferences.
 *
 * Defaults matter here: diagnostics off, approvals on, nothing that phones
 * home. A user who never opens this screen gets the private, safe behaviour.
 */
@Serializable
data class AppSettings(
    val activeProviderConfigId: String? = null,
    val tierOverride: TierOverride = TierOverride.AUTO,

    /**
     * Off by default and only ever local. There is no analytics endpoint in
     * this app; turning it on writes a rotating log file the user can read and
     * attach to a bug report themselves.
     */
    val diagnosticsEnabled: Boolean = false,

    /** Snapshot the workspace before the first agent write of each turn. */
    val autoCheckpointBeforeAgentWrites: Boolean = true,

    /**
     * When true the approval sheet offers "always allow for this session".
     * Turning it off forces a prompt for every single action.
     */
    val allowSessionApprovals: Boolean = true,

    val editorFontScale: Float = 1.0f,
    val editorSoftWrap: Boolean = true,
    val editorShowLineNumbers: Boolean = true,

    /** BCP 47 tag, or null to follow the system language. */
    val languageTag: String? = null,

    val terminalFontScale: Float = 1.0f,
    val previewAutoRefresh: Boolean = true,
) {
    companion object {
        val DEFAULT = AppSettings()
    }
}
