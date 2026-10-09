package com.prof18.winkeep.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import com.prof18.winkeep.WinKeepEffect
import com.prof18.winkeep.rememberWinKeepState
import kotlinx.coroutines.launch

fun main() =
    application {
        var mainOpen by remember { mutableStateOf(true) }
        var settingsOpen by remember { mutableStateOf(true) }
        if (mainOpen) persistentWindow("main", "WinKeep", { mainOpen = false }, { settingsOpen = true })
        if (settingsOpen) persistentWindow("settings", "WinKeep settings", { settingsOpen = false })
        if (!mainOpen && !settingsOpen) exitApplication()
    }

@Composable
private fun persistentWindow(
    windowId: String,
    title: String,
    onClosed: () -> Unit,
    openSettings: (() -> Unit)? = null,
) {
    var error by remember { mutableStateOf<String?>(null) }
    val state =
        rememberWinKeepState(
            appId = "com.prof18.winkeep.sample",
            windowId = windowId,
            defaultSize = DpSize(800.dp, 600.dp),
            onError = { error = "${it.operation}: ${it.cause.message}" },
        )
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                state.saveOnClose()
                // Even when persistence fails, the caller may finish closing.
                onClosed()
            }
        }
    }
    if (state.isReady) {
        Window(onCloseRequest = close, state = state.windowState, title = title) {
            WinKeepEffect(state)
            MenuBar {
                Menu("Window") {
                    WindowPlacement.entries.forEach { placement ->
                        RadioButtonItem(
                            text = placement.name,
                            selected = state.windowState.placement == placement,
                            enabled = !closing,
                            onClick = { state.windowState.placement = placement },
                        )
                    }
                    if (openSettings != null) Item("Open settings", enabled = !closing, onClick = openSettings)
                    Item("Close", enabled = !closing, onClick = close)
                }
            }
            Column(Modifier.padding(24.dp)) {
                BasicText("$title — independent window ID: $windowId")
                BasicText("Move or resize this window, then close and reopen the sample.")
                BasicText("The Window menu changes placement and opens the second window.")
                error?.let { BasicText(it) }
            }
        }
    }
}
