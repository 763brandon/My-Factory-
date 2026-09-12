package com.myfactory.forge.core.settings

/**
 * Where API keys live.
 *
 * The contract is deliberately narrow: store, read, delete, list aliases.
 * Nothing here returns a key by accident, and no key is ever part of a data
 * class that gets serialised, logged or persisted alongside a provider config.
 *
 * The Android implementation wraps each secret with an AES-256-GCM key held in
 * the platform keystore. The in-memory implementation below is for tests only.
 */
interface SecretStore {
    fun put(alias: String, secret: String)
    fun get(alias: String): String?
    fun remove(alias: String)
    fun contains(alias: String): Boolean
    fun aliases(): Set<String>
}

/** Test double. Never wire this into a release build. */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()

    override fun put(alias: String, secret: String) {
        values[alias] = secret
    }

    override fun get(alias: String): String? = values[alias]

    override fun remove(alias: String) {
        values.remove(alias)
    }

    override fun contains(alias: String): Boolean = values.containsKey(alias)

    override fun aliases(): Set<String> = values.keys.toSet()
}
