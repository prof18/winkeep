package com.prof18.winkeep.internal

import androidx.compose.ui.unit.DpSize
import kotlin.math.roundToInt

/** Positive caller-supplied dimensions in classic Compose units. */
internal data class Dimensions(
    val width: Double,
    val height: Double,
) {
    init {
        requireNativeDimension(width, "width")
        requireNativeDimension(height, "height")
    }
}

internal fun validateDimensions(
    size: DpSize,
    label: String,
): Dimensions {
    try {
        return Dimensions(size.width.value.toDouble(), size.height.value.toDouble())
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid $label: ${error.message}", error)
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

/** A coordinate must be finite and survive conversion through Float to a native Int. */
internal fun requireNativeCoordinate(
    value: Double,
    name: String,
) {
    val nativeValue = value.toFloat().toDouble()
    require(
        value.isFinite() &&
            nativeValue.isFinite() &&
            nativeValue >= Int.MIN_VALUE.toDouble() &&
            nativeValue <= Int.MAX_VALUE.toDouble(),
    ) { "Window $name is outside the finite native coordinate range: $value" }
}

/** A dimension follows the coordinate rule and must also round to at least one native unit. */
internal fun requireNativeDimension(
    value: Double,
    name: String,
) {
    requireNativeCoordinate(value, name)
    require(value > 0 && value.toFloat().roundToInt() >= 1) {
        "Window $name must round to at least one native coordinate unit: $value"
    }
}
