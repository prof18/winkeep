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
import com.prof18.winkeep.internal.isLinuxDesktop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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
                var transitions: NativeTransitions? = null
                try {
                    await { windows.all { it?.isShowing == true } }
                    val main = assertNotNull(windows[0])
                    val secondWindow = assertNotNull(windows[1])
                    val finished = NativeTransitions(main)
                    withContext(Dispatchers.Swing) { transitions = finished.also { it.attach() } }
                    await { attached(winKeepListeners(main)) && attached(winKeepListeners(secondWindow)) }
                    val state = assertNotNull(controllers[0])
                    val second = assertNotNull(controllers[1])
                    assertEquals(WindowSaveResult.Saved, second.saveOnClose())
                    withContext(Dispatchers.Swing) { secondOpen.value = false }
                    await { winKeepListeners(secondWindow) == 0 }
                    assertTrue(attached(withContext(Dispatchers.Swing) { winKeepListeners(main) }))
                    val normal = Rectangle(90, 110, 650, 490)
                    withContext(Dispatchers.Swing) { main.setBounds(normal) }
                    await { stores[0].saved.any { it.normalBounds == normal.toBounds() } }
                    if (cycle == 0) {
                        val minimizedBefore = finished.minimized
                        withContext(Dispatchers.Swing) { state.windowState.isMinimized = true }
                        await { main.isMinimized && finished.minimized > minimizedBefore }
                        val restoredBefore = finished.restored
                        withContext(Dispatchers.Swing) { state.windowState.isMinimized = false }
                        await { !main.isMinimized && finished.restored > restoredBefore }
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
                            assertEquals(WindowSaveResult.Saved, state.saveOnClose())
                        }
                        assertEquals(final.toBounds(), stores[0].saved.last().normalBounds)
                    } else {
                        val placement = if (cycle == 1) WindowPlacement.Maximized else WindowPlacement.Fullscreen
                        val robot = screenReader(main)

                        suspend fun present() {
                            val maximizedBefore = finished.maximized
                            val enteredBefore = finished.enteredFullscreen
                            withContext(Dispatchers.Swing) { state.windowState.placement = placement }
                            await {
                                main.placement == placement &&
                                    state.windowState.placement == placement &&
                                    if (placement == WindowPlacement.Maximized) {
                                        finished.maximizedSince(maximizedBefore)
                                    } else {
                                        finished.enteredFullscreenSince(enteredBefore) &&
                                            if (simulatesFullscreen()) {
                                                main.bounds == main.graphicsConfiguration.bounds
                                            } else {
                                                finished.reportsFullscreen || robot == null || presented(robot, main)
                                            }
                                    }
                            }
                        }
                        present()
                        // Some Linux window managers bring the window back at another size; WinKeep puts the normal bounds back.
                        val exitedBefore = finished.exitedFullscreen
                        withContext(Dispatchers.Swing) { state.windowState.placement = WindowPlacement.Floating }
                        val seen = mutableListOf<Rectangle>()
                        try {
                            await {
                                val bounds = main.bounds
                                if (seen.lastOrNull() != bounds) seen += bounds
                                main.placement == WindowPlacement.Floating &&
                                    (placement != WindowPlacement.Fullscreen || finished.exitedFullscreenSince(exitedBefore)) &&
                                    bounds == normal
                            }
                        } catch (timeout: TimeoutCancellationException) {
                            throw AssertionError("bounds after leaving $placement: $seen", timeout)
                        }
                        delay(2_000)
                        assertEquals(normal, withContext(Dispatchers.Swing) { main.bounds })
                        assertEquals(WindowSaveResult.Saved, state.save())
                        assertEquals(normal.toBounds(), stores[0].saved.last().normalBounds)
                        present()
                        assertEquals(WindowSaveResult.Saved, state.saveOnClose())
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
                    assertEquals(WindowSaveResult.Saved, state.saveOnClose())
                    assertTrue(errors.isEmpty(), errors.toString())
                    withContext(Dispatchers.Swing) { open.value = false }
                    await { winKeepListeners(main) == 0 }
                    // Presentation cycles also save once explicitly after returning to floating.
                    assertEquals(if (cycle == 0) 1 else 2, stores[0].flushes)
                    assertEquals(1, stores[1].flushes)
                    println("effect cycle=$cycle final=${stores[0].saved.last()}")
                } finally {
                    withContext(Dispatchers.Swing) {
                        transitions?.detach()
                        open.value = false
                        secondOpen.value = false
                    }
                    app.cancelAndJoin()
                    withContext(Dispatchers.Swing) { windows.filterNotNull().forEach { it.dispose() } }
                }
                assertEquals(0, withContext(Dispatchers.Swing) { windows.filterNotNull().sumOf(::winKeepListeners) })
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
        return robot.getPixelColor(right, screen.y + screen.height / 2) == nativeTestMarker &&
            robot.getPixelColor(screen.x + screen.width / 2, bottom) == nativeTestMarker
    }

    /**
     * Whether [count] is what WinKeep keeps on an attached window: one component, one window-state
     * and one graphicsConfiguration listener from the observer. On Linux the one-shot inset
     * correction adds a window and a component listener until it finishes, which can happen at any
     * resize, so both counts are valid there.
     */
    private fun attached(count: Int) = count == 3 || (isLinuxDesktop() && count == 5)

    /**
     * Counts listeners registered by library code on [window]. Test helpers such as
     * [NativeTransitions] live in the same package, so only classes loaded from the library's own
     * output count.
     */
    private fun winKeepListeners(window: ComposeWindow): Int {
        val library = WinKeepState::class.java.protectionDomain.codeSource.location

        fun Any.ours() = javaClass.protectionDomain?.codeSource?.location == library
        return window.componentListeners.count { it.ours() } +
            window.windowStateListeners.count { it.ours() } +
            window.windowListeners.count { it.ours() } +
            window.getPropertyChangeListeners("graphicsConfiguration").count { it.ours() }
    }

    private fun Rectangle.toBounds() = WindowBounds(WindowOrigin(x.toDouble(), y.toDouble()), width.toDouble(), height.toDouble())

    private class RecordingStore(
        x: Double,
    ) : WindowStateStore {
        val saved = CopyOnWriteArrayList<WindowSnapshot>()

        @Volatile var flushes = 0
        private val initial = WindowSnapshot(normalBounds = WindowBounds(WindowOrigin(x, 100.0), 640.0, 480.0))

        override suspend fun load(): WindowSnapshot = initial

        override suspend fun save(snapshot: WindowSnapshot) {
            saved += snapshot
        }

        override suspend fun flush() {
            flushes++
        }

        override suspend fun clear() = Unit
    }
}
