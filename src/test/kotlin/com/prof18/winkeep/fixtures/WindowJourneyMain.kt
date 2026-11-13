package com.prof18.winkeep.fixtures

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.awaitApplication
import com.prof18.winkeep.WinKeepEffect
import com.prof18.winkeep.WindowSaveResult
import com.prof18.winkeep.rememberWinKeepState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicBoolean

public object WindowJourneyMain {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 3) { "Expected mode, app ID, and window ID" }
        val (mode, appId, windowId) = args
        require(mode == "writer" || mode == "reader") { "Unknown journey mode: $mode" }
        runBlocking {
            awaitApplication {
                WindowJourney(mode, appId, windowId)
            }
        }
    }
}

@Composable
private fun ApplicationScope.WindowJourney(
    mode: String,
    appId: String,
    windowId: String,
) {
    val state = rememberWinKeepState(appId, windowId, defaultSize = DpSize(800.dp, 600.dp))
    val scope = rememberCoroutineScope()
    val visible = remember { mutableStateOf(true) }
    val nativeWindow = remember { mutableStateOf<ComposeWindow?>(null) }
    val journeyStarted = remember { AtomicBoolean(false) }

    suspend fun closeAfterFlush() {
        val result = state.saveOnClose()
        check(result == WindowSaveResult.Saved) { "WinKeep failed to save and flush before close: $result" }
        println("PHASE mode=$mode closed flushed=true")
        visible.value = false
        exitApplication()
    }

    if (state.isReady && visible.value) {
        Window(
            onCloseRequest = { scope.launch { closeAfterFlush() } },
            state = state.windowState,
            visible = visible.value,
            title = "WinKeep restart journey $mode",
        ) {
            WinKeepEffect(state)
            SideEffect { nativeWindow.value = window }
        }
    }

    LaunchedEffect(state.isReady) {
        if (!state.isReady || !journeyStarted.compareAndSet(false, true)) return@LaunchedEffect
        println("PHASE mode=$mode ready=true")
        awaitNative("showing window") { nativeWindow.value?.isShowing == true }
        val window = requireNotNull(nativeWindow.value)

        if (mode == "writer") {
            withContext(Dispatchers.Swing) { window.setBounds(WRITTEN_BOUNDS) }
            awaitNative("Compose floating bounds") {
                val position = state.windowState.position
                val size = state.windowState.size
                state.windowState.placement == WindowPlacement.Floating &&
                    close(position.x.value, WRITTEN_BOUNDS.x) &&
                    close(position.y.value, WRITTEN_BOUNDS.y) &&
                    close(size.width.value, WRITTEN_BOUNDS.width) &&
                    close(size.height.value, WRITTEN_BOUNDS.height)
            }
            val bounds = withContext(Dispatchers.Swing) { Rectangle(window.bounds) }
            println("PHASE mode=writer native-bounds=${bounds.x},${bounds.y},${bounds.width},${bounds.height}")
            closeAfterFlush()
        } else {
            awaitNative("restored native bounds") {
                withContext(Dispatchers.Swing) {
                    window.bounds.within(WRITTEN_BOUNDS, GEOMETRY_TOLERANCE)
                }
            }
            val restored = withContext(Dispatchers.Swing) { Rectangle(window.bounds) }
            check(restored.within(WRITTEN_BOUNDS, GEOMETRY_TOLERANCE)) {
                "Restored native bounds $restored differ from $WRITTEN_BOUNDS by more than $GEOMETRY_TOLERANCE"
            }
            check(window.placement == WindowPlacement.Floating && state.windowState.placement == WindowPlacement.Floating)
            println("PHASE mode=reader restored-bounds=${restored.x},${restored.y},${restored.width},${restored.height}")
            closeAfterFlush()
        }
    }
}

private suspend fun awaitNative(
    description: String,
    condition: suspend () -> Boolean,
) {
    withTimeout(NATIVE_TIMEOUT_MILLIS) {
        while (!condition()) delay(POLL_MILLIS)
    }
    println("PHASE awaited=$description")
}

private fun close(
    actual: Float,
    expected: Int,
): Boolean = kotlin.math.abs(actual - expected) <= GEOMETRY_TOLERANCE

private fun Rectangle.within(
    expected: Rectangle,
    tolerance: Int,
): Boolean =
    kotlin.math.abs(x - expected.x) <= tolerance &&
        kotlin.math.abs(y - expected.y) <= tolerance &&
        kotlin.math.abs(width - expected.width) <= tolerance &&
        kotlin.math.abs(height - expected.height) <= tolerance

private const val NATIVE_TIMEOUT_MILLIS = 30_000L
private const val POLL_MILLIS = 20L
private const val GEOMETRY_TOLERANCE = 2
private val WRITTEN_BOUNDS = Rectangle(120, 140, 610, 450)
