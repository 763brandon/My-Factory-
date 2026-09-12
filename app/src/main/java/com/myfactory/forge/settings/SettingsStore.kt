package com.myfactory.forge.settings

import android.content.Context
import androidx.core.content.edit
import com.myfactory.forge.core.capability.TierOverride
import com.myfactory.forge.core.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferences, backed by SharedPreferences and exposed as a StateFlow.
 *
 * DataStore would be the modern choice, but it pulls a coroutine-backed file
 * layer in for a dozen scalar values. SharedPreferences is already in the
 * process and reads from an in-memory map after the first load.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("forge_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    private fun load() = AppSettings(
        activeProviderConfigId = prefs.getString(KEY_ACTIVE_PROVIDER, null),
        tierOverride = runCatching {
            TierOverride.valueOf(prefs.getString(KEY_TIER, null) ?: TierOverride.AUTO.name)
        }.getOrDefault(TierOverride.AUTO),
        diagnosticsEnabled = prefs.getBoolean(KEY_DIAGNOSTICS, false),
        autoCheckpointBeforeAgentWrites = prefs.getBoolean(KEY_AUTO_CHECKPOINT, true),
        allowSessionApprovals = prefs.getBoolean(KEY_SESSION_APPROVALS, true),
        editorFontScale = prefs.getFloat(KEY_EDITOR_SCALE, 1.0f),
        editorSoftWrap = prefs.getBoolean(KEY_SOFT_WRAP, true),
        editorShowLineNumbers = prefs.getBoolean(KEY_LINE_NUMBERS, true),
        languageTag = prefs.getString(KEY_LANGUAGE, null),
        terminalFontScale = prefs.getFloat(KEY_TERMINAL_SCALE, 1.0f),
        previewAutoRefresh = prefs.getBoolean(KEY_PREVIEW_REFRESH, true),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit {
            putString(KEY_ACTIVE_PROVIDER, next.activeProviderConfigId)
            putString(KEY_TIER, next.tierOverride.name)
            putBoolean(KEY_DIAGNOSTICS, next.diagnosticsEnabled)
            putBoolean(KEY_AUTO_CHECKPOINT, next.autoCheckpointBeforeAgentWrites)
            putBoolean(KEY_SESSION_APPROVALS, next.allowSessionApprovals)
            putFloat(KEY_EDITOR_SCALE, next.editorFontScale)
            putBoolean(KEY_SOFT_WRAP, next.editorSoftWrap)
            putBoolean(KEY_LINE_NUMBERS, next.editorShowLineNumbers)
            putString(KEY_LANGUAGE, next.languageTag)
            putFloat(KEY_TERMINAL_SCALE, next.terminalFontScale)
            putBoolean(KEY_PREVIEW_REFRESH, next.previewAutoRefresh)
        }
        _settings.value = next
    }

    private companion object {
        const val KEY_ACTIVE_PROVIDER = "active_provider"
        const val KEY_TIER = "tier_override"
        const val KEY_DIAGNOSTICS = "diagnostics"
        const val KEY_AUTO_CHECKPOINT = "auto_checkpoint"
        const val KEY_SESSION_APPROVALS = "session_approvals"
        const val KEY_EDITOR_SCALE = "editor_scale"
        const val KEY_SOFT_WRAP = "soft_wrap"
        const val KEY_LINE_NUMBERS = "line_numbers"
        const val KEY_LANGUAGE = "language"
        const val KEY_TERMINAL_SCALE = "terminal_scale"
        const val KEY_PREVIEW_REFRESH = "preview_refresh"
    }
}
