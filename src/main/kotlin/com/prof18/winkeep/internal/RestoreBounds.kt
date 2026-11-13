package com.prof18.winkeep.internal

import com.prof18.winkeep.SavedMonitor
import com.prof18.winkeep.ScreenArea
import com.prof18.winkeep.WindowBounds
import com.prof18.winkeep.WindowOrigin
import com.prof18.winkeep.WindowSnapshot
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Brings [saved] (or [defaults]) fully inside one usable screen. Width and height are first
 * raised to [minimum], then clamped to the work area, so a screen smaller than the minimum wins.
 */
internal fun restoreBounds(
    saved: WindowSnapshot?,
    defaults: Dimensions,
    screens: List<ScreenInfo>,
    minimum: Dimensions? = null,
): RestoreResult {
    val fallback =
        WindowBounds(
            null,
            max(defaults.width, minimum?.width ?: 0.0),
            max(defaults.height, minimum?.height ?: 0.0),
        )
    val snapshot = saved ?: WindowSnapshot(normalBounds = fallback)
    val usable =
        screens
            .mapNotNull { screen -> usableArea(screen)?.let { screen to it } }
            .sortedWith(compareByDescending<Pair<ScreenInfo, ScreenArea>> { it.first.isDefault }.thenBy { it.first.id })
    if (usable.isEmpty()) return RestoreResult(WindowSnapshot(normalBounds = fallback), screensUnavailable = true)
    val (selected, area) =
        usable.firstOrNull { it.first.id == snapshot.monitor?.id }
            ?: usable
                .maxByOrNull { intersection(snapshot.normalBounds, it.second) }
                ?.takeIf { intersection(snapshot.normalBounds, it.second) > 0 }
            ?: usable.first()
    val rawLeft = ceil(area.x)
    val rawTop = ceil(area.y)
    val right = floor(area.x + area.width)
    val bottom = floor(area.y + area.height)
    val left = nativeCoordinate(rawLeft, rawLeft, right - 1)
    val top = nativeCoordinate(rawTop, rawTop, bottom - 1)
    val bounds = snapshot.normalBounds
    val width = nativeDimension(max(bounds.width, minimum?.width ?: 0.0), right - left)
    val height = nativeDimension(max(bounds.height, minimum?.height ?: 0.0), bottom - top)
    val oldArea = snapshot.monitor?.takeIf { it.id == selected.id }?.workArea
    val desiredX = bounds.position?.let { it.x + (oldArea?.let { old -> area.x - old.x } ?: 0.0) } ?: (left + (right - left - width) / 2)
    val desiredY = bounds.position?.let { it.y + (oldArea?.let { old -> area.y - old.y } ?: 0.0) } ?: (top + (bottom - top - height) / 2)
    val repaired =
        WindowBounds(
            WindowOrigin(
                x = nativeCoordinate(desiredX, left, right - width),
                y = nativeCoordinate(desiredY, top, bottom - height),
            ),
            width = width,
            height = height,
        )
    return RestoreResult(
        snapshot.copyWith(normalBounds = repaired, monitor = SavedMonitor(selected.id, area)),
        screensUnavailable = false,
    )
}

/** Returns the screen's work area when at least one native pixel of it can hold a window. */
private fun usableArea(screen: ScreenInfo): ScreenArea? {
    val area = screen.workArea.toScreenAreaOrNull() ?: return null
    val left = ceil(area.x)
    val top = ceil(area.y)
    val right = floor(area.x + area.width)
    val bottom = floor(area.y + area.height)
    if (right - left < 1 || bottom - top < 1) return null
    return try {
        nativeCoordinate(left, left, right - 1)
        nativeCoordinate(top, top, bottom - 1)
        area
    } catch (_: IllegalArgumentException) {
        null
    }
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
    require(native.isFinite() && native.toDouble() in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
        "No finite native position fits the usable area"
    }
    val rounded = native.roundToInt().toDouble()
    require(rounded in minimum..maximum) { "No representable native position fits the usable area" }
    return rounded
}

private fun intersection(
    bounds: WindowBounds,
    area: ScreenArea,
): Double {
    val origin = bounds.position ?: return 0.0
    val width = max(0.0, min(origin.x + bounds.width, area.x + area.width) - max(origin.x, area.x))
    val height = max(0.0, min(origin.y + bounds.height, area.y + area.height) - max(origin.y, area.y))
    return width * height
}
