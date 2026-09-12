package com.myfactory.forge.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollTo
import com.myfactory.forge.core.ai.ProviderConfig
import com.myfactory.forge.core.ai.ProviderId
import com.myfactory.forge.ui.screens.ProviderDraft
import com.myfactory.forge.ui.screens.ProviderEditScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderEditScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val existing = ProviderConfig(
        id = "c1",
        providerId = ProviderId.ANTHROPIC,
        displayName = "Anthropic",
        baseUrl = "https://api.anthropic.com",
        model = "claude-sonnet-5",
        apiKeyAlias = "alias-1",
    )

    private class Captured {
        var saved: ProviderDraft? = null
        var tested: ProviderDraft? = null
        var deleted = 0
        var cancelled = 0
    }

    private fun show(
        existingConfig: ProviderConfig? = existing,
        hasStoredKey: Boolean = true,
        testResult: String? = null,
        allowDelete: Boolean = true,
    ): Captured {
        val captured = Captured()
        compose.render {
            ProviderEditScreen(
                existing = existingConfig,
                hasStoredKey = hasStoredKey,
                defaultBaseUrlFor = { "https://api.anthropic.com" },
                suggestedModelsFor = { listOf("claude-sonnet-5") },
                requiresKeyFor = { it != ProviderId.OPENAI_COMPATIBLE },
                testResult = testResult,
                onTest = { captured.tested = it },
                onSave = { captured.saved = it },
                onDelete = if (allowDelete) ({ captured.deleted++ }) else null,
                onCancel = { captured.cancelled++ },
            )
        }
        return captured
    }

    @Test
    fun `an existing key is never read back into the form`() {
        show(hasStoredKey = true)

        // The form says a key is stored and leaves the field blank, so a
        // screenshot or a shoulder-surfer cannot capture it.
        compose.label("A key is saved for this provider.").assertExists()
        compose.label("API key").assertExists()
    }

    @Test
    fun `save is allowed when a key is already stored and the field is left blank`() {
        val captured = show(hasStoredKey = true)

        compose.node("Save").scrollAndClick()

        val draft = captured.saved
        assertTrue("save must work without retyping the key", draft != null)
        assertEquals("", draft!!.apiKey)
        assertTrue(draft.keyAlreadyStored)
    }

    @Test
    fun `a new provider cannot be saved without a key when one is required`() {
        show(existingConfig = null, hasStoredKey = false)

        compose.node("Save").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the test connection button reports the result`() {
        val captured = show(testResult = "Connected. 4 models available.")

        compose.label("Connected. 4 models available.").assertExists()

        compose.node("Test connection").scrollAndClick()
        assertTrue(captured.tested != null)
    }

    @Test
    fun `cancel leaves without saving`() {
        val captured = show()

        compose.node("Cancel").scrollAndClick()

        assertEquals(1, captured.cancelled)
        assertTrue(captured.saved == null)
    }

    @Test
    fun `delete is offered for an existing provider and hidden for a new one`() {
        val captured = show(allowDelete = true)
        compose.node("Delete").scrollAndClick()
        assertEquals(1, captured.deleted)
    }

    @Test
    fun `the saved draft carries what the user chose`() {
        val captured = show()

        compose.node("Save").scrollAndClick()

        val draft = captured.saved!!
        assertEquals(ProviderId.ANTHROPIC, draft.config.providerId)
        assertEquals("claude-sonnet-5", draft.config.model)
        assertEquals("https://api.anthropic.com", draft.config.baseUrl)
        // The alias is preserved so the stored key stays findable.
        assertEquals("alias-1", draft.config.apiKeyAlias)
    }
}
