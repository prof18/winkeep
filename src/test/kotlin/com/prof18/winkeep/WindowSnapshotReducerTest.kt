package com.prof18.winkeep

import com.prof18.winkeep.internal.NativeObservation
import com.prof18.winkeep.internal.ScreenInfo
import com.prof18.winkeep.internal.WindowSnapshotReducer
import com.prof18.winkeep.internal.restoreBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WindowSnapshotReducerTest {
    private val normal = WindowBounds(-900.0, 70.0, 650.0, 490.0)
    private val monitorA = SavedMonitor("A", WindowBounds(-1000.0, 20.0, 1000.0, 800.0))
    private val monitorB = SavedMonitor("B", WindowBounds(0.0, 0.0, 1920.0, 1080.0))
    private val initial = WindowSnapshot(normalBounds = normal, monitor = monitorA)

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
        assertEquals(normal, assertNotNull(reducer.snapshot).normalBounds)
        reducer.commit(token)
        assertEquals(resized, assertNotNull(reducer.snapshot).normalBounds)
    }

    @Test
    fun `placement transition discards stale deferred candidates and retains normal geometry`() {
        val reducer = attached()
        val token = assertNotNull(reducer.observe(observation(normal.copy(width = 720.0))))
        reducer.transition()
        reducer.observe(observation(monitorB.workArea, SavedPlacement.Maximized, monitorB))
        assertNull(reducer.commit(token))
        assertEquals(initial.copy(placement = SavedPlacement.Maximized), reducer.snapshot)
    }

    @Test
    fun `either nonfloating source rejects transient geometry mismatches`() {
        val reducer = attached()
        val expanded = observation(monitorB.workArea).copy(composePlacement = SavedPlacement.Maximized)
        assertNull(reducer.observe(expanded))
        assertEquals(normal, assertNotNull(reducer.snapshot).normalBounds)
        assertNull(reducer.observe(expanded.copy(composePlacement = SavedPlacement.Floating, nativePlacement = SavedPlacement.Fullscreen)))
        assertEquals(normal, assertNotNull(reducer.snapshot).normalBounds)
    }

    @Test
    fun `hidden minimized and detached events never replace geometry or placement`() {
        val reducer = attached()
        for (event in listOf(
            observation(monitorB.workArea).copy(showing = false),
            observation(monitorB.workArea).copy(visible = false),
            observation(monitorB.workArea).copy(minimized = true),
        )) {
            assertNull(reducer.observe(event))
            assertEquals(initial, reducer.snapshot)
        }
        reducer.detach()
        assertNull(reducer.observe(observation(monitorB.workArea, SavedPlacement.Fullscreen, monitorB)))
        assertEquals(initial, reducer.snapshot)
    }

    @Test
    fun `fullscreen close and disposal preserve final placement and normal monitor pair`() {
        val reducer = attached()
        reducer.observe(observation(monitorB.workArea, SavedPlacement.Fullscreen, monitorB))
        val closed = reducer.close(observation(monitorB.workArea, SavedPlacement.Fullscreen, monitorB))
        reducer.detach()
        reducer.observe(observation(monitorB.workArea).copy(showing = false))
        assertEquals(initial.copy(placement = SavedPlacement.Fullscreen), closed)
        assertEquals(closed, reducer.snapshot)
    }

    @Test
    fun `close freezes latest coherent floating tuple before queued candidates or disposal`() {
        val reducer = attached()
        val token = assertNotNull(reducer.observe(observation(normal.copy(width = 700.0))))
        val final = normal.copy(x = -850.0, width = 610.0, height = 450.0)
        val closed = reducer.close(observation(final))
        assertEquals(initial.copy(normalBounds = final), closed)
        assertNull(reducer.commit(token))
        assertNull(reducer.observe(observation(normal)))
        assertEquals(closed, reducer.close(observation(normal)))
    }

    @Test
    fun `moving maximized window to B never changes retained bounds monitor A`() {
        val reducer = attached()
        reducer.observe(observation(monitorB.workArea, SavedPlacement.Maximized, monitorB))
        val snapshot = assertNotNull(reducer.close(observation(monitorB.workArea, SavedPlacement.Maximized, monitorB)))
        assertEquals(normal, snapshot.normalBounds)
        assertEquals(monitorA, snapshot.monitor)
        val movedA = monitorA.workArea.copy(x = 300.0, y = -200.0)
        val restored = restoreBounds(snapshot, WindowBounds(null, null, 800.0, 600.0), listOf(ScreenInfo("A", movedA)))
        assertEquals(400.0, restored.snapshot.normalBounds.x)
        assertEquals(-150.0, restored.snapshot.normalBounds.y)
        val removedA = restoreBounds(snapshot, WindowBounds(null, null, 800.0, 600.0), listOf(ScreenInfo("B", monitorB.workArea, true)))
        assertEquals("B", removedA.snapshot.monitor?.id)
        assertEquals(0.0, removedA.snapshot.normalBounds.x)
    }

    @Test
    fun `floating lookup failure clears monitor metadata instead of carrying unrelated screen`() {
        val reducer = attached()
        val moved = normal.copy(x = 20.0, y = 50.0)
        commit(reducer, observation(moved, monitor = null))
        assertEquals(moved, assertNotNull(reducer.snapshot).normalBounds)
        assertNull(assertNotNull(reducer.snapshot).monitor)
    }

    @Test
    fun `serialized native trace preserves floating tuple through maximize and fullscreen`() {
        val reducer = attached()
        for (placement in listOf(SavedPlacement.Maximized, SavedPlacement.Fullscreen)) {
            reducer.transition()
            reducer.observe(observation(monitorB.workArea, placement, monitorB))
            assertEquals(normal, assertNotNull(reducer.snapshot).normalBounds)
            reducer.transition()
            commit(reducer, observation())
            assertEquals(initial, reducer.snapshot)
        }
    }

    private fun attached() =
        WindowSnapshotReducer(initial).also {
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
        bounds: WindowBounds = normal,
        placement: SavedPlacement = SavedPlacement.Floating,
        monitor: SavedMonitor? = monitorA,
    ) = NativeObservation(bounds, placement, placement, monitor = monitor)
}
