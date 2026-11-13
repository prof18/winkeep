package com.prof18.winkeep.internal

import com.prof18.winkeep.SavedMonitor
import com.prof18.winkeep.SavedPlacement
import com.prof18.winkeep.WindowBounds
import com.prof18.winkeep.WindowSnapshot

internal data class NativeObservation(
    val bounds: Rect,
    val composePlacement: SavedPlacement,
    val nativePlacement: SavedPlacement,
    val showing: Boolean = true,
    val minimized: Boolean = false,
    val visible: Boolean = true,
    val monitor: SavedMonitor? = null,
)

/**
 * [restoresNormal] holds the earlier normal bounds after a window leaves maximized or fullscreen.
 * Window managers on Linux X11 can return such a window at another size, often the usable screen
 * area, which would otherwise replace the bounds the user chose. While the hold lasts, other
 * floating bounds are ignored, including ones the app sets itself.
 */
internal class WindowSnapshotReducer(
    initial: WindowSnapshot,
    private val restoresNormal: Boolean = false,
) {
    private var retained = initial
    private var observed = false
    private var attached = false
    private var closing = false
    private var generation = 0L
    private var nativePlacement: SavedPlacement? = null
    private var latest: NativeObservation? = null
    private var pending: Candidate? = null
    private var frozen: WindowSnapshot? = null

    /** Normal bounds the floating window must return to before other floating bounds count. */
    var restoreTarget: WindowBounds? = null
        private set

    val snapshot: WindowSnapshot?
        get() = if (closing) frozen else retained.takeIf { observed }

    fun attach() {
        check(!attached && !closing) { "Window controller is already attached or closing" }
        attached = true
    }

    fun observe(observation: NativeObservation): Long? {
        if (!attached || closing) return null
        latest = observation
        val previous = nativePlacement
        if (previous != observation.nativePlacement) {
            nativePlacement = observation.nativePlacement
            transition()
            restoreTarget =
                retained.normalBounds.takeIf {
                    restoresNormal &&
                        previous != null &&
                        previous != SavedPlacement.Floating &&
                        observation.nativePlacement == SavedPlacement.Floating
                }
        }
        val bounds = eligibleBounds(observation)
        if (bounds == null) {
            transition()
            return null
        }
        observed = true
        retained = retained.copyWith(placement = observation.nativePlacement)
        if (observation.nativePlacement != SavedPlacement.Floating || held(bounds)) return null
        pending = Candidate(bounds, observation.monitor, generation)
        return generation
    }

    fun commit(generation: Long): WindowSnapshot? {
        val candidate = pending ?: return null
        val observation = latest ?: return null
        if (!attached ||
            closing ||
            generation != this.generation ||
            candidate.generation != generation ||
            observation.nativePlacement != SavedPlacement.Floating ||
            candidate.bounds != eligibleBounds(observation)
        ) {
            return null
        }
        retained = retained.copyWith(normalBounds = candidate.bounds, monitor = candidate.monitor)
        pending = null
        return snapshot
    }

    /** Stops holding, so the bounds the window manager kept count from the next observation. */
    fun endRestore() {
        restoreTarget = null
        transition()
    }

    fun transition() {
        generation++
        pending = null
    }

    /** Adopts a coherent current tuple immediately, without closing; used by explicit saves. */
    fun capture(observation: NativeObservation? = null): WindowSnapshot? {
        if (closing) return frozen
        if (attached && observation != null) adopt(observation)
        return snapshot
    }

    fun close(observation: NativeObservation? = null): WindowSnapshot? {
        if (closing) return frozen
        closing = true
        transition()
        if (attached && observation != null) adopt(observation)
        frozen = retained.takeIf { observed }
        return frozen
    }

    fun detach() {
        attached = false
        latest = null
        transition()
    }

    private fun adopt(observation: NativeObservation) {
        val bounds = eligibleBounds(observation) ?: return
        observed = true
        retained = retained.copyWith(placement = observation.nativePlacement)
        if (observation.nativePlacement == SavedPlacement.Floating && !held(bounds)) {
            retained = retained.copyWith(normalBounds = bounds, monitor = observation.monitor)
        }
    }

    /**
     * Whether floating [bounds] are rejected by the hold. Window managers settle in several steps
     * and can move the window again after it first reaches the target, so only [endRestore] ends it.
     */
    private fun held(bounds: WindowBounds): Boolean {
        val target = restoreTarget ?: return false
        // A target without a position was placed by the platform, so only its size must match.
        return if (target.position == null) bounds.width != target.width || bounds.height != target.height else bounds != target
    }

    /** Valid concrete bounds of a showing, visible, non-minimized window whose placements agree. */
    private fun eligibleBounds(observation: NativeObservation): WindowBounds? {
        if (!observation.showing ||
            !observation.visible ||
            observation.minimized ||
            observation.composePlacement != observation.nativePlacement
        ) {
            return null
        }
        return observation.bounds.toWindowBoundsOrNull()
    }

    private data class Candidate(
        val bounds: WindowBounds,
        val monitor: SavedMonitor?,
        val generation: Long,
    )
}
