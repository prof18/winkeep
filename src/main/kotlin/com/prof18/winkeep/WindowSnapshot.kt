package com.prof18.winkeep

import com.prof18.winkeep.internal.requireNativeCoordinate
import com.prof18.winkeep.internal.requireNativeDimension

/** Persisted outer-window geometry and presentation in classic Compose coordinate units. */
public class WindowSnapshot(
    /** Last coherent floating rectangle, retained while maximized or fullscreen. */
    public val normalBounds: WindowBounds,
    /** Presentation to restore; minimization is never stored. */
    public val placement: SavedPlacement = SavedPlacement.Floating,
    /** Monitor paired with [normalBounds], or null when lookup was unavailable. */
    public val monitor: SavedMonitor? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is WindowSnapshot &&
            normalBounds == other.normalBounds &&
            placement == other.placement &&
            monitor == other.monitor

    override fun hashCode(): Int = (normalBounds.hashCode() * 31 + placement.hashCode()) * 31 + monitor.hashCode()

    override fun toString(): String = "WindowSnapshot(normalBounds=$normalBounds, placement=$placement, monitor=$monitor)"
}

/**
 * A decorated outer frame. A null [position] lets the platform place the window.
 *
 * @throws IllegalArgumentException when [width] or [height] is not finite, not positive,
 * outside the native Float/Int range, or rounds to less than one native unit.
 */
public class WindowBounds(
    public val position: WindowOrigin?,
    public val width: Double,
    public val height: Double,
) {
    init {
        requireNativeDimension(width, "width")
        requireNativeDimension(height, "height")
    }

    override fun equals(other: Any?): Boolean =
        other is WindowBounds && position == other.position && width == other.width && height == other.height

    override fun hashCode(): Int = (position.hashCode() * 31 + width.hashCode()) * 31 + height.hashCode()

    override fun toString(): String = "WindowBounds(position=$position, width=$width, height=$height)"
}

/**
 * A concrete outer-frame origin. Coordinates may be negative on monitors left of or above the primary.
 *
 * @throws IllegalArgumentException when a coordinate is not finite or outside the native Float/Int range.
 */
public class WindowOrigin(
    public val x: Double,
    public val y: Double,
) {
    init {
        requireNativeCoordinate(x, "x")
        requireNativeCoordinate(y, "y")
    }

    override fun equals(other: Any?): Boolean = other is WindowOrigin && x == other.x && y == other.y

    override fun hashCode(): Int = x.hashCode() * 31 + y.hashCode()

    override fun toString(): String = "WindowOrigin(x=$x, y=$y)"
}

/** Usable area associated with the retained floating rectangle. */
public class SavedMonitor(
    /** AWT device identifier; identity availability may change between sessions. */
    public val id: String,
    /** Usable monitor rectangle, excluding desktop insets. */
    public val workArea: ScreenArea,
) {
    override fun equals(other: Any?): Boolean = other is SavedMonitor && id == other.id && workArea == other.workArea

    override fun hashCode(): Int = id.hashCode() * 31 + workArea.hashCode()

    override fun toString(): String = "SavedMonitor(id=$id, workArea=$workArea)"
}

/**
 * A concrete screen rectangle in classic Compose coordinate units.
 *
 * @throws IllegalArgumentException with the same numeric rules as [WindowOrigin] and [WindowBounds].
 */
public class ScreenArea(
    public val x: Double,
    public val y: Double,
    public val width: Double,
    public val height: Double,
) {
    init {
        requireNativeCoordinate(x, "x")
        requireNativeCoordinate(y, "y")
        requireNativeDimension(width, "width")
        requireNativeDimension(height, "height")
    }

    override fun equals(other: Any?): Boolean =
        other is ScreenArea && x == other.x && y == other.y && width == other.width && height == other.height

    override fun hashCode(): Int = ((x.hashCode() * 31 + y.hashCode()) * 31 + width.hashCode()) * 31 + height.hashCode()

    override fun toString(): String = "ScreenArea(x=$x, y=$y, width=$width, height=$height)"
}

/** Minimized presentation is deliberately never persisted. */
public enum class SavedPlacement {
    Floating,

    /** Maximized presentation with separate retained floating bounds. */
    Maximized,

    /** Fullscreen presentation with separate retained floating bounds. */
    Fullscreen,
}

/** A future record must be preserved until a compatible reader can restore it. */
public class UnsupportedSchemaException(
    /** Exact positive-integer schema token, including values beyond Long range. */
    public val version: String,
) : Exception("Unsupported window snapshot schema: $version")
