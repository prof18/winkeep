package com.prof18.winkeep

import com.prof18.winkeep.internal.MonitorLookup
import com.prof18.winkeep.internal.Rect
import com.prof18.winkeep.internal.ScreenInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MonitorLookupTest {
    @Test
    fun `current screen becomes the observed monitor`() {
        val lookup = MonitorLookup({ ScreenInfo("display", Rect(-1920.0, 25.0, 1920.0, 1055.0)) }, { error("unexpected report") })
        assertEquals(SavedMonitor("display", ScreenArea(-1920.0, 25.0, 1920.0, 1055.0)), lookup.current())
    }

    @Test
    fun `missing or degenerate screen yields no monitor`() {
        assertNull(MonitorLookup({ null }, { error("unexpected report") }).current())
        assertNull(MonitorLookup({ ScreenInfo("tiny", Rect(0.0, 0.0, 0.0, 10.0)) }, { error("unexpected report") }).current())
    }

    @Test
    fun `failing lookup is reported once per attachment and continues without a monitor`() {
        val reports = mutableListOf<Exception>()
        var fail = true
        val lookup =
            MonitorLookup({
                if (fail) error("display server unavailable")
                ScreenInfo("display", Rect(0.0, 0.0, 100.0, 100.0))
            }, reports::add)

        repeat(5) { assertNull(lookup.current()) }
        assertEquals(1, reports.size)
        fail = false
        assertEquals("display", lookup.current()?.id)
        fail = true
        assertNull(lookup.current())
        assertEquals(1, reports.size)

        val nextAttachment = MonitorLookup({ error("still unavailable") }, reports::add)
        nextAttachment.current()
        assertEquals(2, reports.size)
    }
}
