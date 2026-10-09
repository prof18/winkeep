package com.prof18.winkeep

/** Persisted outer-window geometry and presentation in classic Compose coordinate units. */
public data class WindowSnapshot(
    /** Record schema version; v1 is the currently supported schema. */
    public val schemaVersion: Int = 1,
    /** Last coherent floating rectangle, retained while maximized or fullscreen. */
    public val normalBounds: WindowBounds,
    /** Presentation to restore; minimization is never stored. */
    public val placement: SavedPlacement = SavedPlacement.Floating,
    /** Monitor paired with [normalBounds], or null when lookup was unavailable. */
    public val monitor: SavedMonitor? = null,
)

/** A decorated frame; the position is either a complete pair or unspecified. */
public data class WindowBounds(
    /** Horizontal outer-frame origin; null only when [y] is also null. */
    public val x: Double?,
    /** Vertical outer-frame origin; null only when [x] is also null. */
    public val y: Double?,
    public val width: Double,
    public val height: Double,
)

/** Usable area associated with the retained floating rectangle. */
public data class SavedMonitor(
    /** AWT device identifier; identity availability may change between sessions. */
    public val id: String,
    /** Usable monitor rectangle with concrete coordinates, excluding desktop insets. */
    public val workArea: WindowBounds,
)

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
