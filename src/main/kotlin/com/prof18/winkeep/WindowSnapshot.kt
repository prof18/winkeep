package com.prof18.winkeep

/** Persisted outer-window geometry and presentation in classic Compose coordinate units. */
public data class WindowSnapshot(
    public val schemaVersion: Int = 1,
    public val normalBounds: WindowBounds,
    public val placement: SavedPlacement = SavedPlacement.Floating,
    public val monitor: SavedMonitor? = null,
)

/** A decorated frame; the position is either a complete pair or unspecified. */
public data class WindowBounds(
    public val x: Double?,
    public val y: Double?,
    public val width: Double,
    public val height: Double,
)

/** Usable area associated with the retained floating rectangle. */
public data class SavedMonitor(
    public val id: String,
    public val workArea: WindowBounds,
)

/** Minimized presentation is deliberately never persisted. */
public enum class SavedPlacement {
    Floating,
    Maximized,
    Fullscreen,
}

/** A future record must be preserved until a compatible reader can restore it. */
public class UnsupportedSchemaException(
    public val version: String,
) : Exception("Unsupported window snapshot schema: $version")
