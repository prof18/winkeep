package com.prof18.winkeep

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import com.prof18.winkeep.internal.NativeWindowObserver
import com.prof18.winkeep.internal.attachLinuxInsetCorrection

/** Attach inside the caller-owned Window. Cleanup removes observation; await close first. */
@Composable
public fun FrameWindowScope.WinKeepEffect(state: WinKeepState) {
    val scope = rememberCoroutineScope()
    DisposableEffect(window, state) {
        val restored = checkNotNull(state.restored) { "Compose the Window only after WinKeepState.isReady" }
        // Attached first so its size fix runs before the observer samples the same resize event.
        val detachCorrection =
            attachLinuxInsetCorrection(
                window = window,
                restoredWidth = restored.normalBounds.width.toInt(),
                restoredHeight = restored.normalBounds.height.toInt(),
                restoredPlacement = restored.placement,
                canRestore = {
                    window.placement == WindowPlacement.Floating && state.windowState.placement == WindowPlacement.Floating
                },
            )
        val detach = NativeWindowObserver(window, state, scope).attach()
        onDispose {
            detach()
            detachCorrection()
        }
    }
}
