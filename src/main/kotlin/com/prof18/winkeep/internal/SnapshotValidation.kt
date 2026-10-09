package com.prof18.winkeep.internal

import com.prof18.winkeep.UnsupportedSchemaException
import com.prof18.winkeep.WindowBounds
import com.prof18.winkeep.WindowSnapshot
import kotlin.math.roundToInt

internal class InvalidSnapshotException(
    message: String,
) : IllegalArgumentException(message)

internal fun validateSnapshot(snapshot: WindowSnapshot): WindowSnapshot {
    if (snapshot.schemaVersion > 1) throw UnsupportedSchemaException(snapshot.schemaVersion.toString())
    if (snapshot.schemaVersion != 1) throw InvalidSnapshotException("Schema version must be a positive integer")
    validateBounds(snapshot.normalBounds)
    snapshot.monitor?.let { validateBounds(it.workArea, requirePosition = true) }
    return snapshot
}

internal fun validateDefaults(bounds: WindowBounds): WindowBounds {
    try {
        return validateBounds(bounds)
    } catch (error: InvalidSnapshotException) {
        throw IllegalArgumentException("Invalid caller default dimensions", error)
    }
}

internal fun validateIdentity(
    appId: String,
    windowId: String,
) {
    for (id in listOf(appId, windowId)) {
        require(id.isNotBlank()) { "Application and window identifiers must not be blank" }
        require(id.codePointCount(0, id.length) <= 256) { "Identifiers may contain at most 256 Unicode code points" }
    }
}

internal fun validateBounds(
    bounds: WindowBounds,
    requirePosition: Boolean = false,
): WindowBounds {
    if ((bounds.x == null) != (bounds.y == null)) throw InvalidSnapshotException("Position must be a complete pair")
    if (requirePosition && bounds.x == null) throw InvalidSnapshotException("Monitor work area requires a position")
    bounds.x?.let { validateNumber(it, dimension = false) }
    bounds.y?.let { validateNumber(it, dimension = false) }
    validateNumber(bounds.width, dimension = true)
    validateNumber(bounds.height, dimension = true)
    return bounds
}

private fun validateNumber(
    value: Double,
    dimension: Boolean,
) {
    val nativeValue = value.toFloat().toDouble()
    if (!value.isFinite() ||
        !nativeValue.isFinite() ||
        nativeValue < Int.MIN_VALUE.toDouble() ||
        nativeValue > Int.MAX_VALUE.toDouble()
    ) {
        throw InvalidSnapshotException("Window value is outside finite native coordinate range: $value")
    }
    if (dimension && (value <= 0 || nativeValue.roundToInt() < 1)) {
        throw InvalidSnapshotException("Window dimensions must round to at least one native coordinate unit")
    }
}
