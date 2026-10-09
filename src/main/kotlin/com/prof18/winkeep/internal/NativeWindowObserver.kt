package com.prof18.winkeep.internal

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.awt.ComposeWindow
import com.prof18.winkeep.SavedMonitor
import com.prof18.winkeep.WinKeepState
import com.prof18.winkeep.WindowBounds
import com.prof18.winkeep.toSavedPlacement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.EventQueue
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import java.beans.PropertyChangeListener
import java.util.concurrent.atomic.AtomicInteger

internal class NativeWindowObserver(
    private val window: ComposeWindow,
    private val state: WinKeepState,
    private val scope: CoroutineScope,
) {
    private var attached = false
    private var detachState: (() -> Unit)? = null
    private var observationJob: Job? = null
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
        active.incrementAndGet()
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
        val generation = state.observe(candidate) ?: return
        EventQueue.invokeLater {
            // Refresh the tuple first: closing, hidden state and placement cancel stale candidates.
            if (attached && capture() == candidate) state.commit(generation)
        }
    }

    private fun capture(): NativeObservation {
        check(EventQueue.isDispatchThread()) { "Native observation must run on EDT" }
        val rectangle = window.bounds
        val monitor = state.refreshScreens().firstOrNull { it.id == window.graphicsConfiguration?.device?.iDstring }
        return NativeObservation(
            WindowBounds(rectangle.x.toDouble(), rectangle.y.toDouble(), rectangle.width.toDouble(), rectangle.height.toDouble()),
            state.windowState.placement.toSavedPlacement(),
            window.placement.toSavedPlacement(),
            showing = window.isShowing,
            minimized = window.isMinimized || state.windowState.isMinimized,
            visible = window.isVisible,
            monitor = monitor?.let { SavedMonitor(it.id, it.workArea) },
        )
    }

    private fun detach() {
        check(EventQueue.isDispatchThread()) { "Native observation must detach on EDT" }
        if (!attached) return
        attached = false
        observationJob?.cancel()
        observationJob = null
        window.removeComponentListener(components)
        window.removeWindowStateListener(placement)
        window.removePropertyChangeListener("graphicsConfiguration", configuration)
        detachState?.invoke()
        detachState = null
        active.decrementAndGet()
    }

    companion object {
        private val active = AtomicInteger()
        val activeCount: Int get() = active.get()
    }
}
