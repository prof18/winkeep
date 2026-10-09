package com.prof18.winkeep.internal

import com.prof18.winkeep.SavedMonitor
import com.prof18.winkeep.SavedPlacement
import com.prof18.winkeep.WindowBounds
import com.prof18.winkeep.WindowSnapshot

internal data class NativeObservation(
    val bounds: WindowBounds,
    val composePlacement: SavedPlacement,
    val nativePlacement: SavedPlacement,
    val showing: Boolean = true,
    val minimized: Boolean = false,
    val visible: Boolean = true,
    val monitor: SavedMonitor? = null,
)

internal class WindowSnapshotReducer(
    initial: WindowSnapshot,
) {
    private var retained = validateSnapshot(initial)
    private var observed = false
    private var attached = false
    private var closing = false
    private var generation = 0L
    private var nativePlacement: SavedPlacement? = null
    private var latest: NativeObservation? = null
    private var pending: Candidate? = null
    private var frozen: WindowSnapshot? = null

    val snapshot: WindowSnapshot?
        get() = if (closing) frozen else retained.takeIf { observed }

    fun attach() {
        check(!attached && !closing) { "Window controller is already attached or closing" }
        attached = true
    }

    fun observe(observation: NativeObservation): Long? {
        if (!attached || closing) return null
        latest = observation
        if (nativePlacement != observation.nativePlacement) {
            nativePlacement = observation.nativePlacement
            transition()
        }
        if (!eligible(observation)) {
            transition()
            return null
        }
        observed = true
        retained = retained.copy(placement = observation.nativePlacement)
        if (observation.nativePlacement != SavedPlacement.Floating) return null
        pending = Candidate(observation.bounds, validMonitor(observation.monitor), generation)
        return generation
    }

    fun commit(generation: Long): WindowSnapshot? {
        val candidate = pending ?: return null
        val observation = latest ?: return null
        if (!attached ||
            closing ||
            generation != this.generation ||
            candidate.generation != generation ||
            !eligible(observation) ||
            observation.nativePlacement != SavedPlacement.Floating ||
            candidate.bounds != observation.bounds
        ) {
            return null
        }
        retained = retained.copy(normalBounds = candidate.bounds, monitor = candidate.monitor)
        pending = null
        return snapshot
    }

    fun transition() {
        generation++
        pending = null
    }

    fun close(observation: NativeObservation? = null): WindowSnapshot? {
        if (closing) return frozen
        closing = true
        transition()
        if (attached && observation != null && eligible(observation)) {
            observed = true
            retained = retained.copy(placement = observation.nativePlacement)
            if (observation.nativePlacement == SavedPlacement.Floating) {
                retained = retained.copy(normalBounds = observation.bounds, monitor = validMonitor(observation.monitor))
            }
        }
        frozen = retained.takeIf { observed }
        return frozen
    }

    fun detach() {
        attached = false
        latest = null
        transition()
    }

    private fun eligible(observation: NativeObservation): Boolean {
        if (!observation.showing ||
            !observation.visible ||
            observation.minimized ||
            observation.composePlacement != observation.nativePlacement
        ) {
            return false
        }
        return try {
            validateBounds(observation.bounds, requirePosition = true)
            true
        } catch (_: InvalidSnapshotException) {
            false
        }
    }

    private fun validMonitor(monitor: SavedMonitor?): SavedMonitor? =
        monitor?.takeIf {
            try {
                validateBounds(it.workArea, requirePosition = true)
                true
            } catch (_: InvalidSnapshotException) {
                false
            }
        }

    private data class Candidate(
        val bounds: WindowBounds,
        val monitor: SavedMonitor?,
        val generation: Long,
    )
}
