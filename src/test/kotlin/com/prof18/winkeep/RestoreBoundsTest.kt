package com.prof18.winkeep

import com.prof18.winkeep.internal.ScreenInfo
import com.prof18.winkeep.internal.restoreBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestoreBoundsTest {
    @Test
    fun `preserves legitimate negative origin on matching screen`() {
        val screen = screen("left", -1200.0, 0.0, 1200.0, 900.0, default = true)
        val saved = snapshot(bounds(-1100.0, 80.0, 700.0, 500.0), monitor = savedMonitor(screen))

        val result = restoreBounds(saved, bounds(0.0, 0.0, 800.0, 600.0), listOf(screen))

        assertEquals(-1100.0, result.snapshot.normalBounds.x)
        assertEquals(80.0, result.snapshot.normalBounds.y)
        assertEquals("left", result.snapshot.monitor?.id)
        assertFits(result.snapshot.normalBounds, screen.workArea)
    }

    @Test
    fun `saved monitor id wins over another overlapping default screen`() {
        val preferred = screen("saved", 0.0, 0.0, 1200.0, 900.0)
        val other = screen("other", 500.0, 0.0, 1200.0, 900.0, default = true)
        val saved = snapshot(bounds(550.0, 100.0, 500.0, 400.0), monitor = savedMonitor(preferred))

        val result = restoreBounds(saved, bounds(0.0, 0.0, 700.0, 500.0), listOf(other, preferred))

        assertEquals("saved", result.snapshot.monitor?.id)
        assertEquals(550.0, result.snapshot.normalBounds.x)
        assertFits(result.snapshot.normalBounds, preferred.workArea)
    }

    @Test
    fun `greatest positive intersection wins and ties prefer default then id`() {
        val a = screen("a", 0.0, 0.0, 900.0, 700.0)
        val b = screen("b", 400.0, 0.0, 900.0, 700.0, default = true)
        val saved = snapshot(bounds(550.0, 80.0, 500.0, 400.0))

        val byArea = restoreBounds(saved, bounds(0.0, 0.0, 640.0, 480.0), listOf(a, b))
        assertEquals("b", byArea.snapshot.monitor?.id)

        val tieLeft = screen("left", 0.0, 0.0, 600.0, 700.0)
        val tieRight = screen("right", 400.0, 0.0, 600.0, 700.0)
        val tieDefault = screen("primary", 0.0, 0.0, 600.0, 700.0, default = true)
        val tieSaved = snapshot(bounds(200.0, 50.0, 600.0, 400.0))
        val byDefault = restoreBounds(tieSaved, bounds(0.0, 0.0, 640.0, 480.0), listOf(tieRight, tieLeft, tieDefault))
        assertEquals("primary", byDefault.snapshot.monitor?.id)

        val lexicalTie = restoreBounds(tieSaved, bounds(0.0, 0.0, 640.0, 480.0), listOf(tieRight, tieLeft))
        assertEquals("left", lexicalTie.snapshot.monitor?.id)
    }

    @Test
    fun `no intersection falls back to default then lexicographically first screen`() {
        val z = screen("z", 1600.0, 0.0, 1000.0, 800.0)
        val a = screen("a", 2800.0, 0.0, 1000.0, 800.0)
        val saved = snapshot(bounds(-900.0, 30.0, 500.0, 350.0))

        val byId = restoreBounds(saved, bounds(0.0, 0.0, 700.0, 500.0), listOf(z, a))
        assertEquals("a", byId.snapshot.monitor?.id)
        assertFits(byId.snapshot.normalBounds, a.workArea)

        val primary = screen("primary", 4000.0, 0.0, 1000.0, 800.0, default = true)
        val byDefault = restoreBounds(saved, bounds(0.0, 0.0, 700.0, 500.0), listOf(z, primary, a))
        assertEquals("primary", byDefault.snapshot.monitor?.id)
        assertFits(byDefault.snapshot.normalBounds, primary.workArea)
    }

    @Test
    fun `moved matching monitor preserves relative offset from old work area origin`() {
        val old = screen("main", -1000.0, 20.0, 1000.0, 800.0)
        val moved = screen("main", 300.0, -200.0, 1000.0, 800.0)
        val saved = snapshot(bounds(-900.0, 70.0, 600.0, 400.0), monitor = savedMonitor(old))

        val result = restoreBounds(saved, bounds(0.0, 0.0, 700.0, 500.0), listOf(moved))

        assertEquals(400.0, result.snapshot.normalBounds.x)
        assertEquals(-150.0, result.snapshot.normalBounds.y)
        assertFits(result.snapshot.normalBounds, moved.workArea)
    }

    @Test
    fun `vanished monitor uses reachable remaining screen`() {
        val vanished = screen("gone", -1200.0, 0.0, 1000.0, 800.0)
        val remaining = screen("primary", 0.0, 0.0, 900.0, 700.0, default = true)
        val saved = snapshot(bounds(-1100.0, 80.0, 600.0, 450.0), monitor = savedMonitor(vanished))

        val result = restoreBounds(saved, bounds(0.0, 0.0, 700.0, 500.0), listOf(remaining))

        assertEquals("primary", result.snapshot.monitor?.id)
        assertFits(result.snapshot.normalBounds, remaining.workArea)
    }

    @Test
    fun `tiny screens are discarded and dock inset is already excluded from work area`() {
        val tiny = screen("tiny", 0.0, 0.0, 0.4, 0.4, default = true)
        val usable = screen("usable", 100.0, 0.0, 800.0, 560.0)
        val saved = snapshot(bounds(150.0, 40.0, 600.0, 800.0))

        val result = restoreBounds(saved, bounds(0.0, 0.0, 700.0, 500.0), listOf(tiny, usable))

        assertEquals("usable", result.snapshot.monitor?.id)
        assertEquals(560.0, result.snapshot.normalBounds.height)
        assertEquals(0.0, result.snapshot.normalBounds.y)
        assertFits(result.snapshot.normalBounds, usable.workArea)
    }

    @Test
    fun `window can shrink below caller defaults to fit smaller work area`() {
        val screen = screen("small", 0.0, 0.0, 320.0, 240.0, default = true)
        val saved = snapshot(bounds(50.0, 40.0, 1000.0, 800.0))

        val result = restoreBounds(saved, bounds(0.0, 0.0, 900.0, 700.0), listOf(screen))

        assertEquals(320.0, result.snapshot.normalBounds.width)
        assertEquals(240.0, result.snapshot.normalBounds.height)
        assertFits(result.snapshot.normalBounds, screen.workArea)
    }

    @Test
    fun `missing position is centered in selected work area`() {
        val screen = screen("secondary", -1000.0, 100.0, 1000.0, 800.0, default = true)
        val saved = snapshot(bounds(null, null, 400.0, 300.0))

        val result = restoreBounds(saved, bounds(0.0, 0.0, 700.0, 500.0), listOf(screen))

        assertEquals(-700.0, result.snapshot.normalBounds.x)
        assertEquals(350.0, result.snapshot.normalBounds.y)
        assertFits(result.snapshot.normalBounds, screen.workArea)
    }

    @Test
    fun `no screens returns valid defaults with platform default placement`() {
        val defaults = bounds(null, null, 640.0, 480.0)
        val saved = snapshot(bounds(-900.0, 200.0, 500.0, 300.0), placement = SavedPlacement.Maximized)

        val result = restoreBounds(saved, defaults, emptyList())

        assertTrue(result.screensUnavailable)
        assertNull(result.snapshot.normalBounds.x)
        assertNull(result.snapshot.normalBounds.y)
        assertEquals(640.0, result.snapshot.normalBounds.width)
        assertEquals(480.0, result.snapshot.normalBounds.height)
        assertEquals(SavedPlacement.Floating, result.snapshot.placement)
        assertNull(result.snapshot.monitor)
    }

    @Test
    fun `coordinates retain classic units without monitor scale multiplication`() {
        val screen = screen("scaled", 1000.0, 0.0, 2000.0, 1600.0, default = true)
        val saved = snapshot(bounds(1100.0, 100.0, 700.0, 500.0), monitor = savedMonitor(screen))

        val result = restoreBounds(saved, bounds(0.0, 0.0, 800.0, 600.0), listOf(screen))

        assertEquals(1100.0, result.snapshot.normalBounds.x)
        assertEquals(100.0, result.snapshot.normalBounds.y)
        assertEquals(700.0, result.snapshot.normalBounds.width)
        assertEquals(500.0, result.snapshot.normalBounds.height)
    }

    @Test
    fun `result reports available screens and every restored rectangle fits`() {
        val screen = screen("main", -300.0, 50.0, 640.0, 480.0, default = true)
        val saved = snapshot(bounds(-500.0, -100.0, 900.0, 700.0))

        val result = restoreBounds(saved, bounds(0.0, 0.0, 600.0, 400.0), listOf(screen))

        assertFalse(result.screensUnavailable)
        assertFits(result.snapshot.normalBounds, screen.workArea)
    }

    @Test
    fun `centering near integer limit cannot produce a saturated or unrepresentable native origin`() {
        val screen = screen("edge", 2147483520.0, 0.0, 1920.0, 1080.0, default = true)
        val result = restoreBounds(null, bounds(null, null, 800.0, 600.0), listOf(screen))
        val rectangle = result.snapshot.normalBounds
        assertTrue(requireNotNull(rectangle.x).toFloat().toDouble() <= Int.MAX_VALUE.toDouble())
        assertEquals(2147483520.0, rectangle.x)
        assertFits(rectangle, screen.workArea)
    }

    @Test
    fun `coarse Float origin precision shrinks inward so a full area window still fits`() {
        val screen = screen("coarse", 100000003.0, 0.0, 1000.0, 800.0, default = true)
        val result = restoreBounds(null, bounds(null, null, 1000.0, 800.0), listOf(screen))
        assertEquals(100000008.0, result.snapshot.normalBounds.x)
        assertEquals(995.0, result.snapshot.normalBounds.width)
        assertFits(result.snapshot.normalBounds, screen.workArea)
    }

    private fun snapshot(
        normalBounds: WindowBounds,
        placement: SavedPlacement = SavedPlacement.Floating,
        monitor: SavedMonitor? = null,
    ) = WindowSnapshot(normalBounds = normalBounds, placement = placement, monitor = monitor)

    private fun savedMonitor(screen: ScreenInfo) = SavedMonitor(screen.id, screen.workArea)

    private fun screen(
        id: String,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        default: Boolean = false,
    ) = ScreenInfo(id, bounds(x, y, width, height), default)

    private fun bounds(
        x: Double?,
        y: Double?,
        width: Double,
        height: Double,
    ) = WindowBounds(x, y, width, height)

    private fun assertFits(
        rectangle: WindowBounds,
        usable: WindowBounds,
    ) {
        val x = rectangle.x ?: error("Restored rectangle must have a concrete x position")
        val y = rectangle.y ?: error("Restored rectangle must have a concrete y position")
        val left = usable.x ?: error("Usable area must have a concrete x position")
        val top = usable.y ?: error("Usable area must have a concrete y position")
        assertTrue(x >= left, "left edge $x is before usable edge $left")
        assertTrue(y >= top, "top edge $y is before usable edge $top")
        assertTrue(x + rectangle.width <= left + usable.width, "right edge exceeds usable area")
        assertTrue(y + rectangle.height <= top + usable.height, "bottom edge exceeds usable area")
    }
}
