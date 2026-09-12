package com.myfactory.forge.core.util

import kotlinx.serialization.json.Json as KJson

/** Shared JSON configurations. Providers add fields constantly; never be strict. */
object Json {
    /** For decoding anything that came off the wire. */
    val lenient: KJson = KJson {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** For anything persisted locally, where stability matters more. */
    val storage: KJson = KJson {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        explicitNulls = false
    }
}
