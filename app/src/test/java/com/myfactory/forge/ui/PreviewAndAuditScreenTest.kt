package com.myfactory.forge.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.myfactory.forge.core.capability.CapabilityDetector
import com.myfactory.forge.core.capability.DeviceProfile
import com.myfactory.forge.core.session.AuditCategory
import com.myfactory.forge.core.session.AuditEntry
import com.myfactory.forge.ui.screens.AuditLogScreen
import com.myfactory.forge.ui.screens.PreviewScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewAndAuditScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val lowRam = DeviceProfile(
        supportedAbis = listOf("armeabi-v7a"),
        totalRamBytes = 1L * 1024 * 1024 * 1024,
        availableRamBytes = 256L * 1024 * 1024,
        cpuCores = 4,
        sdkInt = 24,
        flaggedLowRam = true,
        freeDataBytes = 2L * 1024 * 1024 * 1024,
        hasWebView = false,
        hasPtyLibrary = false,
        hasProotBinary = false,
    )

    @Test
    fun `preview explains itself when the device has no usable webview`() {
        compose.render {
            PreviewScreen(
                capabilities = CapabilityDetector.detect(lowRam),
                initialUrl = "http://127.0.0.1:3000",
                servingUrl = null,
                onStartServing = {},
                onStopServing = {},
            )
        }

        compose.label("Preview is unavailable").assertExists()
        // The reason must be specific, not a shrug.
        compose.labelContaining("WebView").assertExists()
    }

    @Test
    fun `the activity log lists what the app did`() {
        val entries = listOf(
            AuditEntry(
                id = "a1",
                timestampMillis = 1_700_000_000_000L,
                category = AuditCategory.FILE_WRITE,
                summary = "Wrote src/App.kt",
                detail = "src/App.kt",
            ),
            AuditEntry(
                id = "a2",
                timestampMillis = 1_700_000_001_000L,
                category = AuditCategory.COMMAND_RUN,
                summary = "Ran: ./gradlew test",
                detail = "exit 0",
            ),
        )
        var cleared = 0
        compose.render {
            AuditLogScreen(entries = entries, onClear = { cleared++ })
        }

        compose.label("Wrote src/App.kt").assertExists()
        compose.label("Ran: ./gradlew test").assertExists()

        compose.node("Clear the log").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun `an empty activity log explains what will appear there`() {
        compose.render {
            AuditLogScreen(entries = emptyList(), onClear = {})
        }

        compose.labelContaining("Every file change, command and network request.").assertExists()
    }
}
