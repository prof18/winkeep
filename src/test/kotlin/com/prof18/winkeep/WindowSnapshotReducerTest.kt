package com.prof18.winkeep

import com.prof18.winkeep.internal.Dimensions
import com.prof18.winkeep.internal.NativeObservation
import com.prof18.winkeep.internal.Rect
import com.prof18.winkeep.internal.ScreenInfo
import com.prof18.winkeep.internal.WindowSnapshotReducer
import com.prof18.winkeep.internal.copyWith
import com.prof18.winkeep.internal.restoreBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WindowSnapshotReducerTest {
    private val normal = Rect(-900.0, 70.0, 650.0, 490.0)
    private val monitorA = SavedMonitor("A", ScreenArea(-1000.0, 20.0, 1000.0, 800.0))
    private val monitorB = SavedMonitor("B", ScreenArea(0.0, 0.0, 1920.0, 1080.0))
    private val areaB = Rect(0.0, 0.0, 1920.0, 1080.0)
    private val initial = WindowSnapshot(normalBounds = normal.bounds(), monitor = monitorA)

    @Test
    fun `defaults are never offered to persistence before first valid attached showing observation`() {
        val reducer = WindowSnapshotReducer(initial)
        assertNull(reducer.snapshot)
        assertNull(reducer.observe(observation()))
        reducer.attach()
        assertNull(reducer.observe(observation().copy(showing = false)))
        assertNull(reducer.snapshot)
        commit(reducer, observation())
        assertEquals(initial, reducer.snapshot)
    }

    @Test
    fun `floating candidate is adopted only at matching end of event batch`() {
        val reducer = attached()
        val resized = normal.copy(width = 700.0)
        val token = assertNotNull(reducer.observe(observation(resized)))
        assertEquals(normal.bounds(), assertNotNull(reducer.snapshot).normalBounds)
        reducer.commit(token)
        assertEquals(resized.bounds(), assertNotNull(reducer.snapshot).normalBounds)
    }

    @Test
    fun `placement transition discards stale deferred candidates and retains normal geometry`() {
        val reducer = attached()
        val token = assertNotNull(reducer.observe(observation(normal.copy(width = 720.0))))
        reducer.transition()
        reducer.observe(observation(areaB, SavedPlacement.Maximized, monitorB))
        assertNull(reducer.commit(token))
        assertEquals(initial.copyWith(placement = SavedPlacement.Maximized), reducer.snapshot)
    }

    @Test
    fun `either nonfloating source rejects transient geometry mismatches`() {
        val reducer = attached()
        val expanded = observation(areaB).copy(composePlacement = SavedPlacement.Maximized)
        assertNull(reducer.observe(expanded))
        assertEquals(normal.bounds(), assertNotNull(reducer.snapshot).normalBounds)
        assertNull(reducer.observe(expanded.copy(composePlacement = SavedPlacement.Floating, nativePlacement = SavedPlacement.Fullscreen)))
        assertEquals(normal.bounds(), assertNotNull(reducer.snapshot).normalBounds)
    }

    @Test
    fun `hidden minimized and detached events never replace geometry or placement`() {
        val reducer = attached()
        for (event in listOf(
            observation(areaB).copy(showing = false),
            observation(areaB).copy(visible = false),
            observation(areaB).copy(minimized = true),
        )) {
            assertNull(reducer.observe(event))
            assertEquals(initial, reducer.snapshot)
        }
        reducer.detach()
        assertNull(reducer.observe(observation(areaB, SavedPlacement.Fullscreen, monitorB)))
        assertEquals(initial, reducer.snapshot)
    }

    @Test
    fun `fullscreen close and disposal preserve final placement and normal monitor pair`() {
        val reducer = attached()
        reducer.observe(observation(areaB, SavedPlacement.Fullscreen, monitorB))
        val closed = reducer.close(observation(areaB, SavedPlacement.Fullscreen, monitorB))
        reducer.detach()
        reducer.observe(observation(areaB).copy(showing = false))
        assertEquals(initial.copyWith(placement = SavedPlacement.Fullscreen), closed)
        assertEquals(closed, reducer.snapshot)
    }

    @Test
    fun `close freezes latest coherent floating tuple before queued candidates or disposal`() {
        val reducer = attached()
        val token = assertNotNull(reducer.observe(observation(normal.copy(width = 700.0))))
        val final = normal.copy(x = -850.0, width = 610.0, height = 450.0)
        val closed = reducer.close(observation(final))
        assertEquals(initial.copyWith(normalBounds = final.bounds()), closed)
        assertNull(reducer.commit(token))
        assertNull(reducer.observe(observation(normal)))
        assertEquals(closed, reducer.close(observation(normal)))
    }

    @Test
    fun `moving maximized window to B never changes retained bounds monitor A`() {
        val reducer = attached()
        reducer.observe(observation(areaB, SavedPlacement.Maximized, monitorB))
        val snapshot = assertNotNull(reducer.close(observation(areaB, SavedPlacement.Maximized, monitorB)))
        assertEquals(normal.bounds(), snapshot.normalBounds)
        assertEquals(monitorA, snapshot.monitor)
        val movedA = Rect(300.0, -200.0, 1000.0, 800.0)
        val restored = restoreBounds(snapshot, Dimensions(800.0, 600.0), listOf(ScreenInfo("A", movedA)))
        assertEquals(
            400.0,
            restored.snapshot.normalBounds.position
                ?.x,
        )
        assertEquals(
            -150.0,
            restored.snapshot.normalBounds.position
                ?.y,
        )
        val removedA = restoreBounds(snapshot, Dimensions(800.0, 600.0), listOf(ScreenInfo("B", areaB, true)))
        assertEquals("B", removedA.snapshot.monitor?.id)
        assertEquals(
            0.0,
            removedA.snapshot.normalBounds.position
                ?.x,
        )
    }

    @Test
    fun `floating lookup failure clears monitor metadata instead of carrying unrelated screen`() {
        val reducer = attached()
        val moved = normal.copy(x = 20.0, y = 50.0)
        commit(reducer, observation(moved, monitor = null))
        assertEquals(moved.bounds(), assertNotNull(reducer.snapshot).normalBounds)
        assertNull(assertNotNull(reducer.snapshot).monitor)
    }

    @Test
    fun `serialized native trace preserves floating tuple through maximize and fullscreen`() {
        val reducer = attached()
        for (placement in listOf(SavedPlacement.Maximized, SavedPlacement.Fullscreen)) {
            reducer.transition()
            reducer.observe(observation(areaB, placement, monitorB))
            assertEquals(normal.bounds(), assertNotNull(reducer.snapshot).normalBounds)
            reducer.transition()
            commit(reducer, observation())
            assertEquals(initial, reducer.snapshot)
        }
    }

    @Test
    fun `explicit capture adopts current floating tuple without closing`() {
        val reducer = attached()
        val moved = normal.copy(x = 10.0, width = 700.0)
        val captured = reducer.capture(observation(moved, monitor = monitorB))
        assertEquals(WindowSnapshot(moved.bounds(), monitor = monitorB), captured)
        assertNotNull(reducer.observe(observation(normal)))
        val maximized = reducer.capture(observation(areaB, SavedPlacement.Maximized, monitorB))
        assertEquals(WindowSnapshot(moved.bounds(), SavedPlacement.Maximized, monitorB), maximized)
        assertEquals(WindowSnapshot(moved.bounds(), SavedPlacement.Maximized, monitorB), reducer.close())
        assertEquals(reducer.close(), reducer.capture(observation(normal)))
    }

    @Test
    fun `degenerate native bounds are never adopted`() {
        val reducer = attached()
        assertNull(reducer.observe(observation(normal.copy(width = 0.0))))
        assertEquals(initial, reducer.capture(observation(normal.copy(height = Double.NaN))))
        assertEquals(initial, reducer.close(observation(normal.copy(x = Double.POSITIVE_INFINITY))))
    }

    @Test
    fun `leaving a presentation holds the earlier normal bounds until the hold ends`() {
        val reducer = attached(restoresNormal = true)
        reducer.observe(observation(areaB, SavedPlacement.Fullscreen, monitorB))
        assertNull(reducer.restoreTarget)
        val settled = Rect(67.0, 32.0, 1221.0, 694.0)
        assertNull(reducer.observe(observation(settled)))
        assertEquals(normal.bounds(), reducer.restoreTarget)
        assertEquals(normal.bounds(), assertNotNull(reducer.snapshot).normalBounds)
        assertEquals(normal.bounds(), assertNotNull(reducer.capture(observation(settled))).normalBounds)
        commit(reducer, observation(normal))
        assertEquals(normal.bounds(), reducer.restoreTarget)
        assertNull(reducer.observe(observation(normal.copy(height = 453.0))))
        assertEquals(normal.bounds(), assertNotNull(reducer.snapshot).normalBounds)
        reducer.endRestore()
        commit(reducer, observation(normal.copy(width = 700.0)))
        assertEquals(700.0, assertNotNull(reducer.snapshot).normalBounds.width)
    }

    @Test
    fun `ending a hold accepts the bounds the window manager kept`() {
        val reducer = attached(restoresNormal = true)
        reducer.observe(observation(areaB, SavedPlacement.Maximized, monitorB))
        val kept = Rect(67.0, 32.0, 650.0, 527.0)
        assertNull(reducer.observe(observation(kept)))
        reducer.endRestore()
        assertNull(reducer.restoreTarget)
        commit(reducer, observation(kept))
        assertEquals(kept.bounds(), assertNotNull(reducer.snapshot).normalBounds)
    }

    @Test
    fun `without the Linux hold the first floating bounds after a presentation are adopted`() {
        val reducer = attached()
        reducer.observe(observation(areaB, SavedPlacement.Fullscreen, monitorB))
        val settled = Rect(67.0, 32.0, 1221.0, 694.0)
        commit(reducer, observation(settled))
        assertNull(reducer.restoreTarget)
        assertEquals(settled.bounds(), assertNotNull(reducer.snapshot).normalBounds)
    }

    private fun Rect.bounds() = WindowBounds(WindowOrigin(x, y), width, height)

    private fun attached(restoresNormal: Boolean = false) =
        WindowSnapshotReducer(initial, restoresNormal).also {
            it.attach()
            commit(it, observation())
        }

    private fun commit(
        reducer: WindowSnapshotReducer,
        observation: NativeObservation,
    ) {
        reducer.commit(assertNotNull(reducer.observe(observation)))
    }

    private fun observation(
        bounds: Rect = normal,
        placement: SavedPlacement = SavedPlacement.Floating,
        monitor: SavedMonitor? = monitorA,
    ) = NativeObservation(bounds, placement, placement, monitor = monitor)
}
