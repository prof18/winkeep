package com.prof18.winkeep.internal

import com.prof18.winkeep.SavedMonitor
import com.prof18.winkeep.SavedPlacement
import com.prof18.winkeep.UnsupportedSchemaException
import com.prof18.winkeep.WindowBounds
import com.prof18.winkeep.WindowSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger

internal class SnapshotDecodeException(
    message: String,
    cause: Exception? = null,
) : Exception(message, cause)

internal object SnapshotCodec {
    fun encode(
        snapshot: WindowSnapshot,
        appId: String,
        windowId: String,
    ): String {
        validateIdentity(appId, windowId)
        validateSnapshot(snapshot)
        return buildJsonObject {
            put("schemaVersion", snapshot.schemaVersion)
            put("appId", appId)
            put("windowId", windowId)
            put("normalBounds", boundsObject(snapshot.normalBounds))
            put("placement", snapshot.placement.name)
            put(
                "monitor",
                snapshot.monitor?.let { monitor ->
                    buildJsonObject {
                        put("id", monitor.id)
                        put("workArea", boundsObject(monitor.workArea))
                    }
                } ?: JsonNull,
            )
        }.toString()
    }

    fun decode(
        raw: String,
        appId: String,
        windowId: String,
    ): WindowSnapshot {
        try {
            val root = Json.parseToJsonElement(raw).objectValue()
            val version = root.required("schemaVersion").primitiveValue()
            require(!version.isString && Regex("[1-9][0-9]*").matches(version.content)) { "Invalid schema version" }
            val number = BigInteger(version.content)
            if (number > BigInteger.ONE) throw UnsupportedSchemaException(version.content)
            require(root.required("appId").stringValue() == appId && root.required("windowId").stringValue() == windowId) {
                "Window record identity does not match adapter"
            }
            val placement = root.required("placement").stringValue()
            val monitor =
                root.required("monitor").let {
                    if (it == JsonNull) {
                        null
                    } else {
                        it.objectValue().let { value ->
                            SavedMonitor(value.required("id").stringValue(), bounds(value.required("workArea")))
                        }
                    }
                }
            return validateSnapshot(
                WindowSnapshot(
                    normalBounds = bounds(root.required("normalBounds")),
                    placement = SavedPlacement.entries.firstOrNull { it.name == placement } ?: SavedPlacement.Floating,
                    monitor = monitor,
                ),
            )
        } catch (error: UnsupportedSchemaException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw SnapshotDecodeException("Malformed window snapshot", error)
        }
    }

    private fun boundsObject(bounds: WindowBounds): JsonObject =
        buildJsonObject {
            put("x", bounds.x?.let(::JsonPrimitive) ?: JsonNull)
            put("y", bounds.y?.let(::JsonPrimitive) ?: JsonNull)
            put("width", bounds.width)
            put("height", bounds.height)
        }

    private fun bounds(element: JsonElement): WindowBounds =
        element.objectValue().let {
            WindowBounds(
                it.required("x").nullableNumber(),
                it.required("y").nullableNumber(),
                it.required("width").numberValue(),
                it.required("height").numberValue(),
            )
        }

    private fun JsonObject.required(key: String): JsonElement = get(key) ?: error("Missing $key")

    private fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: error("Expected object")

    private fun JsonElement.primitiveValue(): JsonPrimitive = this as? JsonPrimitive ?: error("Expected primitive")

    private fun JsonElement.stringValue(): String = primitiveValue().also { require(it.isString) { "Expected string" } }.content

    private fun JsonElement.nullableNumber(): Double? = if (this == JsonNull) null else numberValue()

    private fun JsonElement.numberValue(): Double =
        primitiveValue().let {
            require(!it.isString && it != JsonNull) { "Expected number" }
            it.content.toDouble().also { value -> require(value.isFinite()) { "Expected finite number" } }
        }
}
