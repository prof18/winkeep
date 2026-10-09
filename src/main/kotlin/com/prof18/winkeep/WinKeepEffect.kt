package com.prof18.winkeep

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.FrameWindowScope
import com.prof18.winkeep.internal.NativeWindowObserver

/** Attach inside the caller-owned Window. Cleanup removes observation; await close first. */
@Composable
public fun FrameWindowScope.WinKeepEffect(state: WinKeepState) {
    val scope = rememberCoroutineScope()
    DisposableEffect(window, state) {
        val detach = NativeWindowObserver(window, state, scope).attach()
        onDispose { detach() }
    }
}
