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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import com.prof18.winkeep.WinKeepEffect
import com.prof18.winkeep.WinKeepState
import com.prof18.winkeep.rememberWinKeepState
import kotlinx.coroutines.launch

@Composable
fun PersistentWindow(
    windowId: String,
    title: String,
    onClosed: () -> Unit,
    content: @Composable FrameWindowScope.(WinKeepState) -> Unit = {},
) {
    val state = rememberWinKeepState(appId = "com.prof18.winkeep.sample", windowId = windowId)
    val scope = rememberCoroutineScope()
    if (state.isReady) {
        Window(
            onCloseRequest = {
                scope.launch {
                    state.saveOnClose() // Await before the window leaves composition.
                    onClosed()
                }
            },
            state = state.windowState,
            title = title,
        ) {
            WinKeepEffect(state)
            content(state)
        }
    }
}

/** Two independent persistent windows with a menu for placement changes and explicit saves. */
fun main() =
    application {
        var mainOpen by remember { mutableStateOf(true) }
        var settingsOpen by remember { mutableStateOf(true) }
        if (mainOpen) {
            PersistentWindow("main", "WinKeep", onClosed = { mainOpen = false }) { state ->
                DemoContent(state, "main", openSettings = { settingsOpen = true })
            }
        }
        if (settingsOpen) {
            PersistentWindow("settings", "WinKeep settings", onClosed = { settingsOpen = false }) { state ->
                DemoContent(state, "settings")
            }
        }
        if (!mainOpen && !settingsOpen) exitApplication()
    }

@Composable
private fun FrameWindowScope.DemoContent(
    state: WinKeepState,
    windowId: String,
    openSettings: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var lastSave by remember { mutableStateOf<String?>(null) }
    MenuBar {
        Menu("Window") {
            WindowPlacement.entries.forEach { placement ->
                RadioButtonItem(
                    text = placement.name,
                    selected = state.windowState.placement == placement,
                    onClick = { state.windowState.placement = placement },
                )
            }
            Item("Save now", onClick = { scope.launch { lastSave = state.save().name } })
            if (openSettings != null) Item("Open settings", onClick = openSettings)
        }
    }
    Column(Modifier.padding(24.dp)) {
        BasicText("Window ID: $windowId")
        BasicText("Move or resize this window, then close and reopen the sample.")
        BasicText("The Window menu changes placement, saves explicitly and opens the second window.")
        lastSave?.let { BasicText("Last explicit save: $it") }
    }
}
