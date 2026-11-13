package com.prof18.winkeep.internal

import com.prof18.winkeep.SavedMonitor
import com.prof18.winkeep.SavedPlacement
import com.prof18.winkeep.ScreenArea
import com.prof18.winkeep.WindowBounds
import com.prof18.winkeep.WindowOrigin
import com.prof18.winkeep.WindowSnapshot

/** Raw native rectangle as reported by AWT; it may be degenerate until converted. */
internal data class Rect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    fun toScreenAreaOrNull(): ScreenArea? =
        try {
            ScreenArea(x, y, width, height)
        } catch (_: IllegalArgumentException) {
            null
        }

    fun toWindowBoundsOrNull(): WindowBounds? =
        try {
            WindowBounds(WindowOrigin(x, y), width, height)
        } catch (_: IllegalArgumentException) {
            null
        }
}

internal data class ScreenInfo(
    val id: String,
    val workArea: Rect,
    val isDefault: Boolean = false,
)

internal data class RestoreResult(
    val snapshot: WindowSnapshot,
    val screensUnavailable: Boolean,
)

internal fun WindowSnapshot.copyWith(
    normalBounds: WindowBounds = this.normalBounds,
    placement: SavedPlacement = this.placement,
    monitor: SavedMonitor? = this.monitor,
): WindowSnapshot = WindowSnapshot(normalBounds, placement, monitor)
