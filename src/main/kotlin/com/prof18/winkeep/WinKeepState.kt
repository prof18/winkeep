package com.prof18.winkeep

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.prof18.winkeep.internal.NativeObservation
import com.prof18.winkeep.internal.SaveCoordinator
import com.prof18.winkeep.internal.SaveRequest
import com.prof18.winkeep.internal.ScreenInfo
import com.prof18.winkeep.internal.SnapshotDecodeException
import com.prof18.winkeep.internal.WindowSnapshotReducer
import com.prof18.winkeep.internal.isLinuxDesktop
import com.prof18.winkeep.internal.reportPersistenceError
import com.prof18.winkeep.internal.restoreBounds
import com.prof18.winkeep.internal.validateDimensions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A remembered controller for one caller-owned classic Compose window. */
public class WinKeepState internal constructor(
    private val scope: CoroutineScope,
    defaultSize: DpSize,
    private val store: WindowStateStore,
    private val screenProvider: () -> List<ScreenInfo>,
    private val uiDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    internal var onError: (WindowPersistenceError) -> Unit,
    minimumSize: DpSize,
) {
    private val defaults = validateDimensions(defaultSize, "default size")
    private val minimum = minimumSize.takeIf { it.isSpecified }?.let { validateDimensions(it, "minimum size") }

    /** Stable, mutable Compose state, restored before [isReady] becomes true. */
    public val windowState: WindowState = WindowState(size = defaultSize)

    /** Compose the Window only after this becomes true. */
    public val isReady: Boolean get() = ready
    private var ready: Boolean by mutableStateOf(false)
    private var initialized = false
    private var closing = false
    private var readOnly = false
    private var reducer: WindowSnapshotReducer? = null
    private var capture: (() -> NativeObservation)? = null
    private val coordinator = SaveCoordinator(scope, store, uiDispatcher, ioDispatcher) { onError(it) }

    /** The geometry applied to [windowState] at initialization; null until ready. */
    internal var restored: WindowSnapshot? = null
        private set

    internal suspend fun initialize() {
        check(!initialized) { "Window controller already initialized" }
        initialized = true
        val saved =
            try {
                withContext(ioDispatcher) { store.load() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is UnsupportedSchemaException) readOnly = true
                val operation = if (error is SnapshotDecodeException) WindowPersistenceOperation.Decode else WindowPersistenceOperation.Load
                reportPersistenceError(uiDispatcher, { onError(it) }, WindowPersistenceError(operation, error))
                null
            }
        withContext(uiDispatcher) {
            if (closing) return@withContext
            val screens =
                try {
                    screenProvider()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    reportPersistenceError(uiDispatcher, { onError(it) }, WindowPersistenceError(WindowPersistenceOperation.Screens, error))
                    emptyList()
                }
            val snapshot = restoreBounds(saved, defaults, screens, minimum).snapshot
            val bounds = snapshot.normalBounds
            windowState.size = DpSize(bounds.width.toFloat().dp, bounds.height.toFloat().dp)
            windowState.position = bounds.position?.let { WindowPosition(it.x.toFloat().dp, it.y.toFloat().dp) }
                ?: WindowPosition.PlatformDefault
            windowState.placement = snapshot.placement.toComposePlacement()
            windowState.isMinimized = false
            reducer = WindowSnapshotReducer(snapshot, restoresNormal = isLinuxDesktop())
            restored = snapshot
            ready = true
        }
    }

    internal fun attach(capture: () -> NativeObservation): () -> Unit {
        check(isReady && !closing && this.capture == null) { "Window controller is not ready, closing, or already attached" }
        this.capture = capture
        requireNotNull(reducer).attach()
        return {
            this.capture = null
            reducer?.detach()
        }
    }

    internal fun observe(observation: NativeObservation): Long? {
        if (!isReady || closing) return null
        val generation = reducer?.observe(observation)
        if (generation == null) reducer?.snapshot?.let { if (!readOnly) coordinator.offer(it) }
        return generation
    }

    internal fun commit(generation: Long) {
        if (closing) return
        reducer?.commit(generation)?.let { if (!readOnly) coordinator.offer(it) }
    }

    /** Normal bounds to put back on the window after it left maximized or fullscreen; see [WindowSnapshotReducer]. */
    internal val restoreTarget: WindowBounds? get() = reducer?.restoreTarget

    internal fun endRestore() {
        reducer?.endRestore()
    }

    internal fun reportScreensError(error: Exception) {
        scope.launch(uiDispatcher) {
            reportPersistenceError(uiDispatcher, { onError(it) }, WindowPersistenceError(WindowPersistenceOperation.Screens, error))
        }
    }

    /**
     * Non-terminal save: captures the current coherent native geometry, writes it through the
     * same serial writer as debounced saves and flushes, while observation continues. Use it when
     * a window is hidden instead of closed, or from an application-level quit handler. Once
     * [saveOnClose] has started this returns [WindowSaveResult.NothingToSave] (or the close
     * result if the close overtook this write). Cancellation propagates.
     */
    public suspend fun save(): WindowSaveResult =
        coordinator.save {
            val current = if (readOnly) null else reducer?.capture(capture?.invoke())
            when {
                readOnly -> SaveRequest.Skip(WindowSaveResult.ReadOnly)
                current == null -> SaveRequest.Skip(WindowSaveResult.NothingToSave)
                else -> SaveRequest.Write(current)
            }
        }

    /**
     * Terminal close hook: freezes current native geometry, saves it and awaits durability.
     * Call before hiding/disposal. Repeated calls join the same result; cancellation propagates.
     */
    public suspend fun saveOnClose(): WindowSaveResult =
        coordinator.saveOnClose {
            closing = true
            reducer?.transition()
            val frozen = reducer?.close(capture?.invoke())
            when {
                readOnly -> SaveRequest.Skip(WindowSaveResult.ReadOnly)
                frozen == null -> SaveRequest.Skip(WindowSaveResult.NothingToSave)
                else -> SaveRequest.Write(frozen)
            }
        }
}

internal fun SavedPlacement.toComposePlacement(): WindowPlacement =
    when (this) {
        SavedPlacement.Floating -> WindowPlacement.Floating
        SavedPlacement.Maximized -> WindowPlacement.Maximized
        SavedPlacement.Fullscreen -> WindowPlacement.Fullscreen
    }

internal fun WindowPlacement.toSavedPlacement(): SavedPlacement =
    when (this) {
        WindowPlacement.Floating -> SavedPlacement.Floating
        WindowPlacement.Maximized -> SavedPlacement.Maximized
        WindowPlacement.Fullscreen -> SavedPlacement.Fullscreen
    }
