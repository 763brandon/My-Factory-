package com.myfactory.forge.core.ai

import kotlinx.coroutines.flow.Flow

/**
 * One AI backend.
 *
 * Adding a provider means implementing this and registering it in
 * [ProviderRegistry]. Nothing else in the app changes: the agent loop, the UI
 * and the settings screen all work against this interface.
 */
interface AiProvider {
    val id: ProviderId

    /** Pre-filled in Settings when the user picks this provider. */
    fun defaultBaseUrl(): String

    /** Offered as a starting point when the endpoint cannot list models. */
    fun fallbackModels(): List<ModelInfo>

    /** True when the provider needs a key. Self-hosted endpoints usually do not. */
    fun requiresApiKey(): Boolean = true

    /**
     * Asks the endpoint what models it serves. Returns a failure rather than
     * throwing, because a provider without a model-list endpoint is a normal
     * situation, not an error the user needs to see as a crash.
     */
    suspend fun listModels(config: ProviderConfig, apiKey: String?): Result<List<ModelInfo>>

    /**
     * Runs one turn. The flow is cold; collecting it opens the connection and
     * cancelling the collector closes it.
     *
     * Implementations must terminate with exactly one of
     * [StreamEvent.Completed] or [StreamEvent.Failed], and must never let a
     * raw exception escape - the agent loop relies on that to keep a session
     * alive after a transport failure.
     */
    fun stream(request: ChatRequest): Flow<StreamEvent>
}

/** Look-up from [ProviderId] to a live adapter. */
class ProviderRegistry(private val providers: Map<ProviderId, AiProvider>) {

    fun get(id: ProviderId): AiProvider =
        providers[id] ?: error("No adapter registered for $id")

    fun getOrNull(id: ProviderId): AiProvider? = providers[id]

    fun all(): List<AiProvider> = ProviderId.entries.mapNotNull { providers[it] }
}
