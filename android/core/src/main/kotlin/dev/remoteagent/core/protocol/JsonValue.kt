package dev.remoteagent.core.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Free-form JSON (tool inputs, approval inputs) is a JsonElement. Mirrors
// apple/RAKit/Sources/RAKit/Protocol/JSONValue.swift.

operator fun JsonElement.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** The value, when it's a JSON string. */
val JsonElement.stringValue: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

@OptIn(ExperimentalSerializationApi::class)
private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** Pretty-printed JSON text, with sorted keys. */
val JsonElement.pretty: String
    get() = prettyJson.encodeToString(JsonElement.serializer(), sorted())

private fun JsonElement.sorted(): JsonElement = when (this) {
    is JsonObject -> JsonObject(toSortedMap().mapValues { it.value.sorted() })
    is JsonArray -> JsonArray(map { it.sorted() })
    else -> this
}
