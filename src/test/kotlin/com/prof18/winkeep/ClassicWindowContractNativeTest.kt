package com.prof18.winkeep

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.awaitApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClassicWindowContractNativeTest {
    @Test
    fun `classic bounds and transition batches retain normal geometry`() =
        runBlocking {
            assertFalse(GraphicsEnvironment.isHeadless(), "Native gate requires a live display")
            val state =
                WindowState(
                    position = WindowPosition(80.dp, 100.dp),
                    size = DpSize(640.dp, 480.dp),
                )
            val visible = mutableStateOf(true)
            val open = mutableStateOf(true)
            var native: ComposeWindow? = null
            val traces = CopyOnWriteArrayList<String>()
            val app =
                launch(Dispatchers.Default) {
                    awaitApplication {
                        if (open.value) {
                            Window(
                                onCloseRequest = { open.value = false },
                                state = state,
                                visible = visible.value,
                                title = "WinKeep classic contract probe",
                            ) {
                                SideEffect { native = window }
                                Layout(
                                    content = {},
                                    modifier = Modifier.drawBehind { drawRect(Color(0xFF287D56)) },
                                ) { _, constraints ->
                                    layout(constraints.maxWidth, constraints.maxHeight) {}
                                }
                            }
                        }
                    }
                }
            var probe: Probe? = null
            var transitions: NativeTransitions? = null
            try {
                await { native?.isShowing == true }
                val window = assertNotNull(native)
                val finished = NativeTransitions(window)
                withContext(Dispatchers.Swing) {
                    transitions = finished.also { it.attach() }
                    probe = Probe(window, state, traces)
                    probe!!.attach()
                    window.setBounds(90, 110, 650, 490)
                }
                await {
                    probe!!.normal == Rectangle(90, 110, 650, 490) &&
                        abs(state.size.width.value - 650) <= 2 &&
                        abs(state.position.x.value - 90) <= 2
                }
                val normal = withContext(Dispatchers.Swing) { Rectangle(probe!!.normal) }
                val robot = Robot()
                for (placement in listOf(WindowPlacement.Maximized, WindowPlacement.Fullscreen)) {
                    val maximizedBefore = finished.maximized
                    val enteredBefore = finished.enteredFullscreen
                    withContext(Dispatchers.Swing) {
                        probe!!.transition()
                        state.placement = placement
                    }
                    await {
                        window.placement == placement &&
                            state.placement == placement &&
                            when (placement) {
                                WindowPlacement.Maximized -> finished.maximizedSince(maximizedBefore)
                                WindowPlacement.Fullscreen ->
                                    finished.enteredFullscreenSince(enteredBefore) &&
                                        probe!!.lastResizedPlacement == placement &&
                                        window.bounds == window.graphicsConfiguration.bounds &&
                                        state.size == DpSize(window.width.dp, window.height.dp) &&
                                        (finished.reportsFullscreen || fullscreenPresented(robot, window.graphicsConfiguration.bounds))
                                WindowPlacement.Floating -> false
                            }
                    }
                    drain()
                    withContext(Dispatchers.Swing) { assertEquals(normal, probe!!.normal, traces.joinToString("\n")) }
                    val exitedBefore = finished.exitedFullscreen
                    withContext(Dispatchers.Swing) {
                        probe!!.transition()
                        // Drive only WindowState, like an app: Compose applies it to the window, and setting
                        // the window as well toggles macOS fullscreen twice, re-entering it after the exit.
                        state.placement = WindowPlacement.Floating
                    }
                    await {
                        window.placement == WindowPlacement.Floating &&
                            state.placement == WindowPlacement.Floating &&
                            (placement != WindowPlacement.Fullscreen || finished.exitedFullscreenSince(exitedBefore))
                    }
                    await { window.bounds == normal }
                    drain()
                    withContext(Dispatchers.Swing) { assertEquals(normal, probe!!.normal, traces.joinToString("\n")) }
                }
                val minimizedBefore = finished.minimized
                withContext(Dispatchers.Swing) {
                    state.isMinimized = true
                }
                await { window.isMinimized && finished.minimized > minimizedBefore }
                drain()
                withContext(Dispatchers.Swing) { assertEquals(normal, probe!!.normal) }
                val restoredBefore = finished.restored
                withContext(Dispatchers.Swing) { state.isMinimized = false }
                await { !window.isMinimized && finished.restored > restoredBefore }
                withContext(Dispatchers.Swing) { visible.value = false }
                await { !window.isShowing }
                withContext(Dispatchers.Swing) { window.setBounds(1, 1, 300, 200) }
                drain()
                withContext(Dispatchers.Swing) { assertEquals(normal, probe!!.normal) }
                withContext(Dispatchers.Swing) {
                    window.setBounds(normal)
                    visible.value = true
                }
                await { window.isShowing }
                drain()
                withContext(Dispatchers.Swing) {
                    window.setBounds(120, 140, 610, 450)
                    probe!!.close()
                    assertEquals(Rectangle(120, 140, 610, 450), probe!!.normal)
                    open.value = false
                }
                withTimeout(30_000) { app.join() }
                withContext(Dispatchers.Swing) {
                    assertFalse(window.isDisplayable)
                    assertEquals(Rectangle(120, 140, 610, 450), probe!!.normal)
                }
                assertTrue(traces.isNotEmpty())
            } finally {
                withContext(Dispatchers.Swing) {
                    traces += "final native=${native?.placement} compose=${state.placement} " +
                        "bounds=${native?.bounds} size=${state.size}"
                    native?.let { window ->
                        val pixels = Robot()
                        val screen = window.graphicsConfiguration.bounds
                        traces +=
                            "presentation center=${pixels.getPixelColor(screen.x + screen.width / 2, screen.y + screen.height / 2)} " +
                            "right=${pixels.getPixelColor(screen.x + screen.width - 2, screen.y + screen.height / 2)} " +
                            "insets=${Toolkit.getDefaultToolkit().getScreenInsets(window.graphicsConfiguration)}"
                    }
                    probe?.detach()
                    transitions?.detach()
                    open.value = false
                    native?.dispose()
                }
                app.cancelAndJoin()
                println(traces.joinToString("\n"))
            }
        }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(30_000) {
            while (!withContext(Dispatchers.Swing) { condition() }) {
                drain()
                yield()
            }
        }
    }

    private suspend fun drain() {
        withContext(Dispatchers.Swing) { yield() }
        withContext(Dispatchers.Swing) { yield() }
    }

    private fun fullscreenPresented(
        robot: Robot,
        screen: Rectangle,
    ): Boolean {
        val marker = java.awt.Color(0x28, 0x7D, 0x56)
        return listOf(
            screen.x + 16 to screen.y + 48,
            screen.x + screen.width - 2 to screen.y + screen.height - 2,
        ).all { (x, y) -> robot.getPixelColor(x, y) == marker }
    }

    private class Probe(
        private val window: ComposeWindow,
        private val state: WindowState,
        private val traces: MutableList<String>,
    ) {
        var normal: Rectangle? = null
            private set
        private var generation = 0
        private var attached = false
        private var closing = false
        private var placement = WindowPlacement.Floating
        var lastResizedPlacement: WindowPlacement? = null
            private set
        private val component =
            object : ComponentAdapter() {
                override fun componentMoved(event: ComponentEvent) = sample("moved")

                override fun componentResized(event: ComponentEvent) {
                    lastResizedPlacement = window.placement
                    sample("resized")
                }

                override fun componentShown(event: ComponentEvent) = sample("shown")

                override fun componentHidden(event: ComponentEvent) = sample("hidden")
            }
        private val nativeState =
            WindowStateListener { event ->
                traces += "native-state old=${event.oldState} new=${event.newState}"
                sample("state")
            }

        fun attach() {
            attached = true
            window.addComponentListener(component)
            window.addWindowStateListener(nativeState)
            sample("attach")
        }

        fun transition() {
            generation++
        }

        fun close() {
            if (valid()) normal = Rectangle(window.bounds)
            closing = true
            generation++
            sample("close")
        }

        fun detach() {
            attached = false
            generation++
            window.removeComponentListener(component)
            window.removeWindowStateListener(nativeState)
        }

        private fun valid(): Boolean =
            attached &&
                !closing &&
                window.isVisible &&
                window.isShowing &&
                !window.isMinimized &&
                !state.isMinimized &&
                window.placement == WindowPlacement.Floating &&
                state.placement == WindowPlacement.Floating

        private fun sample(event: String) {
            traces += "$event generation=$generation native=${window.placement} compose=${state.placement} " +
                "visible=${window.isShowing} minimized=${window.isMinimized} bounds=${window.bounds} " +
                "stateSize=${state.size} statePosition=${state.position} normal=$normal"
            if (placement != window.placement) {
                placement = window.placement
                transition()
            }
            if (!valid()) return
            val candidate = Rectangle(window.bounds)
            val token = generation
            EventQueue.invokeLater {
                commit(candidate, token)
            }
        }

        private fun commit(
            candidate: Rectangle,
            token: Int,
        ) {
            if (token == generation && valid() && candidate == window.bounds) {
                normal = candidate
                traces += "commit generation=$generation normal=$normal"
            }
        }
    }
}
