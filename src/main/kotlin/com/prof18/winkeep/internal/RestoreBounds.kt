package com.prof18.winkeep.internal

import com.prof18.winkeep.SavedMonitor
import com.prof18.winkeep.WindowBounds
import com.prof18.winkeep.WindowSnapshot
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal fun restoreBounds(
    saved: WindowSnapshot?,
    defaults: WindowBounds,
    screens: List<ScreenInfo>,
): RestoreResult {
    val fallback = validateDefaults(defaults).copy(x = null, y = null)
    val snapshot =
        try {
            saved?.let(::validateSnapshot)
        } catch (_: InvalidSnapshotException) {
            null
        } ?: WindowSnapshot(normalBounds = fallback)
    val usable = screens.filter(::isUsable).sortedWith(compareByDescending<ScreenInfo> { it.isDefault }.thenBy { it.id })
    if (usable.isEmpty()) return RestoreResult(WindowSnapshot(normalBounds = fallback), screensUnavailable = true)
    val selected =
        usable.firstOrNull { it.id == snapshot.monitor?.id }
            ?: usable
                .maxByOrNull { intersection(snapshot.normalBounds, it.workArea) }
                ?.takeIf { intersection(snapshot.normalBounds, it.workArea) > 0 }
            ?: usable.first()
    val area = selected.workArea
    val rawLeft = ceil(requireNotNull(area.x))
    val rawTop = ceil(requireNotNull(area.y))
    val right = floor(area.x + area.width)
    val bottom = floor(area.y + area.height)
    val left = nativeCoordinate(rawLeft, rawLeft, right - 1)
    val top = nativeCoordinate(rawTop, rawTop, bottom - 1)
    val width = nativeDimension(snapshot.normalBounds.width, right - left)
    val height = nativeDimension(snapshot.normalBounds.height, bottom - top)
    val bounds = snapshot.normalBounds
    val oldArea = snapshot.monitor?.takeIf { it.id == selected.id }?.workArea
    val desiredX =
        bounds.x?.let { it + (oldArea?.x?.let { old -> area.x - old } ?: 0.0) }
            ?: (left + (right - left - width) / 2)
    val desiredY =
        bounds.y?.let { it + (oldArea?.y?.let { old -> area.y - old } ?: 0.0) }
            ?: (top + (bottom - top - height) / 2)
    val repaired =
        WindowBounds(
            x = nativeCoordinate(desiredX, left, right - width),
            y = nativeCoordinate(desiredY, top, bottom - height),
            width = width,
            height = height,
        )
    validateBounds(repaired, requirePosition = true)
    return RestoreResult(
        snapshot.copy(normalBounds = repaired, monitor = SavedMonitor(selected.id, area)),
        screensUnavailable = false,
    )
}

private fun isUsable(screen: ScreenInfo): Boolean =
    try {
        val area = validateBounds(screen.workArea, requirePosition = true)
        val left = ceil(requireNotNull(area.x))
        val top = ceil(requireNotNull(area.y))
        val right = floor(area.x + area.width)
        val bottom = floor(area.y + area.height)
        if (right - left < 1 || bottom - top < 1) {
            false
        } else {
            nativeCoordinate(left, left, right - 1)
            nativeCoordinate(top, top, bottom - 1)
            true
        }
    } catch (_: InvalidSnapshotException) {
        false
    }

private fun nativeDimension(
    desired: Double,
    maximum: Double,
): Double {
    val requested =
        desired
            .toFloat()
            .roundToInt()
            .toDouble()
            .coerceIn(1.0, maximum)
    var native = requested.toFloat()
    if (native.toDouble() > maximum) native = Math.nextDown(native)
    return native.roundToInt().toDouble()
}

private fun nativeCoordinate(
    desired: Double,
    minimum: Double,
    maximum: Double,
): Double {
    val largestNative = Math.nextDown(Int.MAX_VALUE.toFloat())
    var native = desired.coerceIn(minimum, maximum).toFloat().coerceIn(Int.MIN_VALUE.toFloat(), largestNative)
    if (native.toDouble() < minimum) native = Math.nextUp(native)
    if (native.toDouble() > maximum) native = Math.nextDown(native)
    if (!native.isFinite() || native.toDouble() !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
        throw InvalidSnapshotException("No finite native position fits the usable area")
    }
    val rounded = native.roundToInt().toDouble()
    if (rounded !in minimum..maximum) throw InvalidSnapshotException("No representable native position fits the usable area")
    return rounded
}

private fun intersection(
    bounds: WindowBounds,
    area: WindowBounds,
): Double {
    val x = bounds.x ?: return 0.0
    val y = bounds.y ?: return 0.0
    val left = requireNotNull(area.x)
    val top = requireNotNull(area.y)
    val width = max(0.0, min(x + bounds.width, left + area.width) - max(x, left))
    val height = max(0.0, min(y + bounds.height, top + area.height) - max(y, top))
    return width * height
}
