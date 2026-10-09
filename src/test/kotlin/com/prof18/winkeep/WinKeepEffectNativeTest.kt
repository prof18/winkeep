package com.prof18.winkeep

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.awaitApplication
import com.prof18.winkeep.internal.NativeWindowObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WinKeepEffectNativeTest {
    @Test
    fun `actual effect retains normal bounds across presentation and captures close before disposal`() =
        runBlocking {
            assertFalse(GraphicsEnvironment.isHeadless())
            val baseline = NativeWindowObserver.activeCount
            repeat(3) { cycle ->
                val open = mutableStateOf(true)
                val secondOpen = mutableStateOf(true)
                val visible = mutableStateOf(true)
                val stores = listOf(RecordingStore(80.0), RecordingStore(780.0))
                val controllers = arrayOfNulls<WinKeepState>(2)
                val windows = arrayOfNulls<ComposeWindow>(2)
                val errors = CopyOnWriteArrayList<WindowPersistenceError>()
                val identity = "effect-${UUID.randomUUID()}"
                val app =
                    launch(Dispatchers.Default) {
                        awaitApplication {
                            if (open.value) {
                                val state = rememberWinKeepState(identity, "main", store = stores[0], onError = errors::add)
                                SideEffect { controllers[0] = state }
                                if (state.isReady) {
                                    Window({}, state = state.windowState, visible = visible.value, title = "WinKeep effect $cycle") {
                                        WinKeepEffect(state)
                                        SideEffect { windows[0] = window }
                                        Layout({}, Modifier.drawBehind { drawRect(Color(0xFF287D56)) }) { _, constraints ->
                                            layout(constraints.maxWidth, constraints.maxHeight) {}
                                        }
                                    }
                                }
                            }
                            if (secondOpen.value) {
                                val state = rememberWinKeepState(identity, "secondary", store = stores[1], onError = errors::add)
                                SideEffect { controllers[1] = state }
                                if (state.isReady) {
                                    Window({}, state = state.windowState, title = "WinKeep independent window") {
                                        WinKeepEffect(state)
                                        SideEffect { windows[1] = window }
                                    }
                                }
                            }
                        }
                    }
                try {
                    await { windows.all { it?.isShowing == true } }
                    assertEquals(baseline + 2, NativeWindowObserver.activeCount)
                    val main = assertNotNull(windows[0])
                    val state = assertNotNull(controllers[0])
                    val second = assertNotNull(controllers[1])
                    assertTrue(second.saveOnClose())
                    withContext(Dispatchers.Swing) { secondOpen.value = false }
                    await { NativeWindowObserver.activeCount == baseline + 1 }
                    val normal = Rectangle(90, 110, 650, 490)
                    withContext(Dispatchers.Swing) { main.setBounds(normal) }
                    await { stores[0].saved.any { it.normalBounds == normal.toBounds() } }
                    if (cycle == 0) {
                        withContext(Dispatchers.Swing) { state.windowState.isMinimized = true }
                        await { main.isMinimized }
                        withContext(Dispatchers.Swing) { state.windowState.isMinimized = false }
                        await { !main.isMinimized }
                        withContext(Dispatchers.Swing) { visible.value = false }
                        await { !main.isShowing }
                        withContext(Dispatchers.Swing) { main.setBounds(1, 1, 300, 200) }
                        delay(300)
                        assertEquals(normal.toBounds(), stores[0].saved.last().normalBounds)
                        withContext(Dispatchers.Swing) {
                            main.setBounds(normal)
                            visible.value = true
                        }
                        await { main.isShowing && main.bounds == normal }
                        val final = Rectangle(120, 140, 610, 450)
                        withContext(Dispatchers.Swing) {
                            main.setBounds(final)
                            assertTrue(state.saveOnClose())
                        }
                        assertEquals(final.toBounds(), stores[0].saved.last().normalBounds)
                    } else {
                        val placement = if (cycle == 1) WindowPlacement.Maximized else WindowPlacement.Fullscreen
                        withContext(Dispatchers.Swing) { state.windowState.placement = placement }
                        val robot = Robot()
                        await {
                            main.placement == placement && state.windowState.placement == placement && presented(robot, main)
                        }
                        assertTrue(state.saveOnClose())
                        assertEquals(normal.toBounds(), stores[0].saved.last().normalBounds)
                        assertEquals(
                            if (cycle ==
                                1
                            ) {
                                SavedPlacement.Maximized
                            } else {
                                SavedPlacement.Fullscreen
                            },
                            stores[0].saved.last().placement,
                        )
                    }
                    assertTrue(state.saveOnClose())
                    assertTrue(errors.isEmpty(), errors.toString())
                    withContext(Dispatchers.Swing) { open.value = false }
                    await { NativeWindowObserver.activeCount == baseline }
                    assertEquals(1, stores[0].flushes)
                    assertEquals(1, stores[1].flushes)
                    println("effect cycle=$cycle final=${stores[0].saved.last()} observers=${NativeWindowObserver.activeCount}")
                } finally {
                    withContext(Dispatchers.Swing) {
                        open.value = false
                        secondOpen.value = false
                    }
                    app.cancelAndJoin()
                    withContext(Dispatchers.Swing) { windows.filterNotNull().forEach { it.dispose() } }
                }
                assertEquals(baseline, NativeWindowObserver.activeCount)
            }
        }

    private suspend fun await(condition: () -> Boolean) =
        withTimeout(30_000) {
            while (!withContext(Dispatchers.Swing) { condition() }) delay(10)
        }

    private fun presented(
        robot: Robot,
        window: ComposeWindow,
    ): Boolean {
        val screen = window.graphicsConfiguration.bounds
        val insets =
            java.awt.Toolkit
                .getDefaultToolkit()
                .getScreenInsets(window.graphicsConfiguration)
        val fullscreen = window.placement == WindowPlacement.Fullscreen
        val right = screen.x + screen.width - (if (fullscreen) 0 else insets.right) - 2
        val bottom = screen.y + screen.height - (if (fullscreen) 0 else insets.bottom) - 2
        val marker = java.awt.Color(0x28, 0x7D, 0x56)
        return robot.getPixelColor(right, screen.y + screen.height / 2) == marker &&
            robot.getPixelColor(screen.x + screen.width / 2, bottom) == marker
    }

    private fun Rectangle.toBounds() = WindowBounds(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())

    private class RecordingStore(
        x: Double,
    ) : WindowStateStore {
        val saved = CopyOnWriteArrayList<WindowSnapshot>()

        @Volatile var flushes = 0
        private val initial = WindowSnapshot(normalBounds = WindowBounds(x, 100.0, 640.0, 480.0))

        override suspend fun load(): WindowSnapshot = initial

        override suspend fun save(snapshot: WindowSnapshot) {
            saved += snapshot
        }

        override suspend fun flush() {
            flushes++
        }
    }
}
