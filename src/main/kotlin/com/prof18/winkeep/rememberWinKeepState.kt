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
import com.prof18.winkeep.internal.ActiveWindowIdentities
import com.prof18.winkeep.internal.AwtScreenProvider
import com.prof18.winkeep.internal.validateDefaults
import com.prof18.winkeep.internal.validateIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Remembers a controller by identity. Store and defaultSize are captured initialization
 * inputs; onError updates live. Use distinct window IDs and remember custom stores.
 */
@Composable
public fun rememberWinKeepState(
    appId: String,
    windowId: String,
    defaultSize: DpSize = DpSize(800.dp, 600.dp),
    store: WindowStateStore? = null,
    onError: (WindowPersistenceError) -> Unit = {},
): WinKeepState {
    validateIdentity(appId, windowId)
    validateDefaults(WindowBounds(null, null, defaultSize.width.value.toDouble(), defaultSize.height.value.toDouble()))
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
