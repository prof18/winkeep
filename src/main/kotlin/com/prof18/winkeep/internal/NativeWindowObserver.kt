package com.prof18.winkeep.internal

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import com.prof18.winkeep.WinKeepState
import com.prof18.winkeep.WindowBounds
import com.prof18.winkeep.toSavedPlacement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.EventQueue
import java.awt.GraphicsConfiguration
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import java.beans.PropertyChangeListener

internal class NativeWindowObserver(
    private val window: ComposeWindow,
    private val state: WinKeepState,
    private val scope: CoroutineScope,
    screenOf: (GraphicsConfiguration?) -> ScreenInfo? = AwtScreenProvider::screenOf,
) {
    private var attached = false
    private var detachState: (() -> Unit)? = null
    private var observationJob: Job? = null
    private var restoring: WindowBounds? = null
    private val monitors = MonitorLookup({ screenOf(window.graphicsConfiguration) }, state::reportScreensError)
    private val components =
        object : ComponentAdapter() {
            override fun componentMoved(event: ComponentEvent) = sample()

            override fun componentResized(event: ComponentEvent) = sample()

            override fun componentShown(event: ComponentEvent) = sample()

            override fun componentHidden(event: ComponentEvent) = sample()
        }
    private val placement = WindowStateListener { sample() }
    private val configuration = PropertyChangeListener { sample() }

    fun attach(): () -> Unit {
        check(EventQueue.isDispatchThread()) { "Native observation must attach on EDT" }
        check(!attached) { "Native observer already attached" }
        detachState = state.attach(::capture)
        attached = true
        window.addComponentListener(components)
        window.addWindowStateListener(placement)
        window.addPropertyChangeListener("graphicsConfiguration", configuration)
        observationJob =
            scope.launch(Dispatchers.Main.immediate) {
                snapshotFlow { state.windowState.placement to state.windowState.isMinimized }.collect { sample() }
            }
        sample()
        return ::detach
    }

    private fun sample() {
        if (!attached) return
        val candidate = capture()
        val generation = state.observe(candidate)
        restore()
        if (generation == null) return
        EventQueue.invokeLater {
            // Refresh the tuple first: closing, hidden state and placement cancel stale candidates.
            if (attached && capture() == candidate) state.commit(generation)
        }
    }

    /**
     * Puts the held normal bounds back on a window that left maximized or fullscreen at other
     * bounds. Window managers can ignore a request made while they finish the transition, so it
     * is repeated every [RESTORE_RETRY_MS] as well as on every window event. A window manager that
     * insists on its own bounds wins after [RESTORE_DEADLINE_MS].
     */
    private fun restore() {
        val target = state.restoreTarget ?: return
        if (target !== restoring) {
            restoring = target
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    repeat((RESTORE_DEADLINE_MS / RESTORE_RETRY_MS).toInt()) {
                        delay(RESTORE_RETRY_MS)
                        if (!attached || state.restoreTarget !== target) return@launch
                        apply(target)
                    }
                    if (attached && state.restoreTarget === target) {
                        state.endRestore()
                        sample()
                    }
                } finally {
                    if (restoring === target) restoring = null
                }
            }
        }
        apply(target)
    }

    private fun apply(target: WindowBounds) {
        val floating = window.placement == WindowPlacement.Floating && state.windowState.placement == WindowPlacement.Floating
        if (!floating || window.isMinimized || !window.isShowing) return
        val width = target.width.toInt()
        val height = target.height.toInt()
        val position = target.position
        if (position == null) {
            if (window.width != width || window.height != height) window.setSize(width, height)
        } else if (window.x != position.x.toInt() || window.y != position.y.toInt() || window.width != width || window.height != height) {
            window.setBounds(position.x.toInt(), position.y.toInt(), width, height)
        }
    }

    private fun capture(): NativeObservation {
        check(EventQueue.isDispatchThread()) { "Native observation must run on EDT" }
        val rectangle = window.bounds
        return NativeObservation(
            Rect(rectangle.x.toDouble(), rectangle.y.toDouble(), rectangle.width.toDouble(), rectangle.height.toDouble()),
            state.windowState.placement.toSavedPlacement(),
            window.placement.toSavedPlacement(),
            showing = window.isShowing,
            minimized = window.isMinimized || state.windowState.isMinimized,
            visible = window.isVisible,
            monitor = monitors.current(),
        )
    }

    private fun detach() {
        check(EventQueue.isDispatchThread()) { "Native observation must detach on EDT" }
        if (!attached) return
        attached = false
        observationJob?.cancel()
        observationJob = null
        restoring = null
        window.removeComponentListener(components)
        window.removeWindowStateListener(placement)
        window.removePropertyChangeListener("graphicsConfiguration", configuration)
        detachState?.invoke()
        detachState = null
    }
}

private const val RESTORE_DEADLINE_MS = 1_500L
private const val RESTORE_RETRY_MS = 100L
