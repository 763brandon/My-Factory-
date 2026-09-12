package com.myfactory.forge.di

import android.content.Context
import com.myfactory.forge.core.ai.ProviderId
import com.myfactory.forge.core.ai.ProviderRegistry
import com.myfactory.forge.core.ai.net.OkHttpTransport
import com.myfactory.forge.core.ai.providers.AnthropicProvider
import com.myfactory.forge.core.ai.providers.GeminiProvider
import com.myfactory.forge.core.ai.providers.OpenAiCompatibleProvider
import com.myfactory.forge.core.ai.providers.OpenAiProvider
import com.myfactory.forge.core.ai.providers.OpenRouterProvider
import com.myfactory.forge.core.capability.Capabilities
import com.myfactory.forge.core.capability.CapabilityDetector
import com.myfactory.forge.core.capability.DeviceProfile
import com.myfactory.forge.core.capability.TierOverride
import com.myfactory.forge.data.db.ForgeDatabase
import com.myfactory.forge.data.repository.ForgeRepository
import com.myfactory.forge.platform.AndroidDeviceProfiler
import com.myfactory.forge.security.KeystoreSecretStore
import com.myfactory.forge.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * Manual dependency wiring.
 *
 * A DI framework would add an annotation processor and a few hundred
 * kilobytes of dex for a graph this small. On a build that has to install and
 * run on a 2 GB Android 7 phone, that is a real cost for no benefit.
 */
class AppContainer(private val context: Context) {

    val httpClient: OkHttpClient by lazy { OkHttpTransport.defaultClient() }

    private val transport by lazy { OkHttpTransport(httpClient) }

    val secretStore: KeystoreSecretStore by lazy { KeystoreSecretStore(context) }

    val settings: SettingsStore by lazy { SettingsStore(context) }

    val database: ForgeDatabase by lazy { ForgeDatabase.get(context) }

    val repository: ForgeRepository by lazy { ForgeRepository(database) }

    val providers: ProviderRegistry by lazy {
        ProviderRegistry(
            mapOf(
                ProviderId.ANTHROPIC to AnthropicProvider(transport),
                ProviderId.OPENAI to OpenAiProvider(transport),
                ProviderId.GEMINI to GeminiProvider(transport),
                ProviderId.OPENROUTER to OpenRouterProvider(transport),
                ProviderId.OPENAI_COMPATIBLE to OpenAiCompatibleProvider(transport),
            ),
        )
    }

    val deviceProfile: DeviceProfile by lazy { AndroidDeviceProfiler.profile(context) }

    private val _capabilities = MutableStateFlow(
        CapabilityDetector.detect(deviceProfile, TierOverride.AUTO),
    )

    /** Recomputed when the user changes the tier override in Settings. */
    val capabilities: StateFlow<Capabilities> = _capabilities.asStateFlow()

    fun applyTierOverride(override: TierOverride) {
        _capabilities.value = CapabilityDetector.detect(deviceProfile, override)
    }

    /** Root of every workspace. Private to the app, so no storage permission. */
    val projectsRoot by lazy {
        java.io.File(context.filesDir, "projects").apply { mkdirs() }
    }

    val checkpointsRoot by lazy {
        java.io.File(context.filesDir, "checkpoints").apply { mkdirs() }
    }
}
