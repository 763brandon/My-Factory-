package com.myfactory.forge.ui

import androidx.compose.ui.test.junit4.createComposeRule
import com.myfactory.forge.core.ai.ProviderConfig
import com.myfactory.forge.core.ai.ProviderId
import com.myfactory.forge.core.capability.CapabilityDetector
import com.myfactory.forge.core.capability.DeviceProfile
import com.myfactory.forge.core.capability.TierOverride
import com.myfactory.forge.core.settings.AppSettings
import com.myfactory.forge.ui.screens.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val profile = DeviceProfile(
        supportedAbis = listOf("arm64-v8a"),
        totalRamBytes = 6L * 1024 * 1024 * 1024,
        availableRamBytes = 3L * 1024 * 1024 * 1024,
        cpuCores = 8,
        sdkInt = 34,
        flaggedLowRam = false,
        freeDataBytes = 16L * 1024 * 1024 * 1024,
        hasWebView = true,
        hasPtyLibrary = true,
        hasProotBinary = true,
    )

    private val anthropic = ProviderConfig(
        id = "c1",
        providerId = ProviderId.ANTHROPIC,
        displayName = "Anthropic",
        baseUrl = "https://api.anthropic.com",
        model = "claude-sonnet-5",
    )

    /** Captures the settings the screen asks to write. */
    private class Captured {
        var settings = AppSettings.DEFAULT
        var language: String? = null
        var languageSet = false
        var addProvider = 0
        var auditOpened = 0
        var keysCleared = 0
        var activeProvider: ProviderConfig? = null
        var editedProvider: ProviderConfig? = null
        var sourceOpened = 0
    }

    private fun show(
        settings: AppSettings = AppSettings.DEFAULT,
        providers: List<ProviderConfig> = listOf(anthropic),
        keystoreWarning: String? = null,
    ): Captured {
        val captured = Captured().apply { this.settings = settings }
        compose.render {
            SettingsScreen(
                settings = captured.settings,
                capabilities = CapabilityDetector.detect(profile),
                profile = profile,
                providers = providers,
                activeProviderId = null,
                versionName = "0.1.0",
                keystoreWarning = keystoreWarning,
                onUpdateSettings = { transform -> captured.settings = transform(captured.settings) },
                onAddProvider = { captured.addProvider++ },
                onEditProvider = { captured.editedProvider = it },
                onSetActiveProvider = { captured.activeProvider = it },
                onOpenAuditLog = { captured.auditOpened++ },
                onClearKeys = { captured.keysCleared++ },
                onSetLanguage = {
                    captured.language = it
                    captured.languageSet = true
                },
                onOpenSource = { captured.sourceOpened++ },
            )
        }
        return captured
    }

    @Test
    fun `the tier chips change the override`() {
        val captured = show()

        compose.node("Lightweight").scrollAndClick()
        assertEquals(TierOverride.FORCE_LIGHTWEIGHT, captured.settings.tierOverride)

        compose.node("Full").scrollAndClick()
        assertEquals(TierOverride.FORCE_FULL, captured.settings.tierOverride)

        compose.node("Automatic").scrollAndClick()
        assertEquals(TierOverride.AUTO, captured.settings.tierOverride)
    }

    @Test
    fun `the privacy switches toggle their setting`() {
        val captured = show()

        assertTrue(captured.settings.autoCheckpointBeforeAgentWrites)
        compose.node("Checkpoint before agent writes").scrollAndClick()
        assertFalse(captured.settings.autoCheckpointBeforeAgentWrites)

        assertFalse(captured.settings.diagnosticsEnabled)
        compose.node("Local diagnostic log").scrollAndClick()
        assertTrue("diagnostics must be opt-in, and the switch must work",
            captured.settings.diagnosticsEnabled)
    }

    @Test
    fun `turning off session approvals is possible`() {
        val captured = show()

        compose.node("Allow “for this session” approvals").scrollAndClick()

        assertFalse(captured.settings.allowSessionApprovals)
    }

    @Test
    fun `the language chips set and clear the override`() {
        val captured = show()

        compose.node("Kiswahili").scrollAndClick()
        assertEquals("sw", captured.language)

        compose.node("Follow the system").scrollAndClick()
        assertTrue(captured.languageSet)
        assertEquals(null, captured.language)
    }

    @Test
    fun `add provider opens the editor`() {
        val captured = show(providers = emptyList())

        compose.node("Add a provider").scrollAndClick()

        assertEquals(1, captured.addProvider)
    }

    @Test
    fun `a listed provider can be made active and edited`() {
        val captured = show()

        compose.node("Use this provider").scrollAndClick()
        assertEquals(anthropic, captured.activeProvider)

        compose.node("Edit").scrollAndClick()
        assertEquals(anthropic, captured.editedProvider)
    }

    @Test
    fun `the activity log and key wipe are reachable`() {
        val captured = show()

        compose.node("Activity log").scrollAndClick()
        assertEquals(1, captured.auditOpened)

        compose.node("Delete all stored API keys").scrollAndClick()
        assertEquals(1, captured.keysCleared)
    }

    @Test
    fun `the source link is offered so the licence claim is checkable`() {
        val captured = show()

        compose.node("Source code").scrollAndClick()

        assertEquals(1, captured.sourceOpened)
    }

    @Test
    fun `the detected device facts are shown`() {
        show()

        compose.label("arm64-v8a").assertExists()
        compose.label("PROOT_UBUNTU").assertExists()
    }

    @Test
    fun `a keystore failure is surfaced rather than hidden`() {
        show(keystoreWarning = "hardware keystore unavailable")

        compose.labelContaining("hardware keystore unavailable").assertExists()
    }

    @Test
    fun `the no-affiliation disclaimer is present`() {
        show()

        compose.labelContaining("Not affiliated with").assertExists()
    }
}
