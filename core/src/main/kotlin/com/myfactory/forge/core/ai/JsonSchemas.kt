package com.myfactory.forge.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Small helpers for hand-writing the JSON Schema that describes a tool. */
object JsonSchemas {

    fun obj(
        properties: Map<String, JsonElement>,
        required: List<String> = emptyList(),
        description: String? = null,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        if (description != null) put("description", description)
        put("properties", JsonObject(properties))
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }

    fun string(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
    }

    fun integer(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    fun boolean(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    /**
     * Gemini's function-declaration schema is a subset of JSON Schema and
     * rejects the vocabulary the other providers tolerate. Strip what it does
     * not accept rather than letting a request fail with an opaque 400.
     */
    fun sanitiseForGemini(schema: JsonObject): JsonObject {
        val unsupported = setOf(
            "\$schema", "\$id", "\$ref", "additionalProperties", "default",
            "examples", "const", "oneOf", "anyOf", "allOf", "not",
            "patternProperties", "minLength", "maxLength", "pattern",
        )
        fun clean(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(
                element.filterKeys { it !in unsupported }.mapValues { clean(it.value) },
            )
            is JsonArray -> JsonArray(element.map(::clean))
            else -> element
        }
        return clean(schema) as JsonObject
    }
}
