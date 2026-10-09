package com.prof18.winkeep.internal

import com.prof18.winkeep.WindowBounds
import com.prof18.winkeep.WindowSnapshot

internal data class ScreenInfo(
    val id: String,
    val workArea: WindowBounds,
    val isDefault: Boolean = false,
)

internal data class RestoreResult(
    val snapshot: WindowSnapshot,
    val screensUnavailable: Boolean,
)
