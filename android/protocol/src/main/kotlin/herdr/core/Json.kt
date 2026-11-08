package herdr.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.longOrNull
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull
val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()
val JsonElement?.isNullish: Boolean get() = this == null || this is JsonNull

fun JsonObject.with(vararg pairs: Pair<String, JsonElement?>): JsonObject {
    val m = toMutableMap()
    for ((k, v) in pairs) if (v == null) m.remove(k) else m[k] = v
    return JsonObject(m)
}

fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.filter { it.second != null }.associate { it.first to el(it.second) })

fun el(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to el(it.value) })
    is Iterable<*> -> JsonArray(v.map { el(it) })
    is Array<*> -> JsonArray(v.map { el(it) })
    else -> JsonPrimitive(v.toString())
}
