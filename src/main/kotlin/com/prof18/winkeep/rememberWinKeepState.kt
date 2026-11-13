@file:Suppress("ktlint:standard:filename")

package com.prof18.winkeep

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.prof18.winkeep.internal.ActiveWindowIdentities
import com.prof18.winkeep.internal.AwtScreenProvider
import com.prof18.winkeep.internal.validateDimensions
import com.prof18.winkeep.internal.validateIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Remembers a controller by identity. Store, defaultSize and minimumSize are captured
 * initialization inputs; onError updates live. Use distinct window IDs and remember custom stores.
 *
 * When [minimumSize] is specified, restored and default sizes are raised to at least that size
 * before being clamped to the usable screen area (a smaller screen wins). WinKeep does not set the
 * AWT minimum size on the caller-owned window; set `window.minimumSize` there as well.
 */
@Composable
public fun rememberWinKeepState(
    appId: String,
    windowId: String,
    defaultSize: DpSize = DpSize(800.dp, 600.dp),
    minimumSize: DpSize = DpSize.Unspecified,
    store: WindowStateStore? = null,
    onError: (WindowPersistenceError) -> Unit = {},
): WinKeepState {
    validateIdentity(appId, windowId)
    validateDimensions(defaultSize, "default size")
    if (minimumSize.isSpecified) validateDimensions(minimumSize, "minimum size")
    val latestError = rememberUpdatedState(onError)
    val parentScope = rememberCoroutineScope()
    val controllerScope =
        remember(appId, windowId) {
            CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        }
    val state =
        remember(appId, windowId) {
            WinKeepState(
                controllerScope,
                defaultSize,
                store ?: PreferencesWindowStateStore(appId, windowId),
                AwtScreenProvider::screens,
                Dispatchers.Main.immediate,
                Dispatchers.IO,
                { latestError.value(it) },
                minimumSize,
            )
        }
    DisposableEffect(state) {
        val release = ActiveWindowIdentities.acquire(appId, windowId)
        onDispose {
            controllerScope.cancel()
            release()
        }
    }
    LaunchedEffect(state) { state.initialize() }
    return state
}
