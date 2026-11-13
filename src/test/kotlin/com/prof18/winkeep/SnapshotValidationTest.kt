package com.prof18.winkeep

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.prof18.winkeep.internal.Dimensions
import com.prof18.winkeep.internal.validateDimensions
import com.prof18.winkeep.internal.validateIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SnapshotValidationTest {
    private val origin = WindowOrigin(-1200.0, 40.0)

    @Test
    fun `valid positions preserve negative coordinates and unspecified position`() {
        val bounds = WindowBounds(origin, 640.0, 480.0)
        assertEquals(-1200.0, bounds.position?.x)
        assertEquals(40.0, bounds.position?.y)
        assertEquals(null, WindowBounds(null, 640.0, 480.0).position)
        assertEquals(SavedPlacement.Fullscreen, WindowSnapshot(bounds, SavedPlacement.Fullscreen).placement)
    }

    @Test
    fun `nonfinite coordinates cannot be constructed`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { WindowOrigin(value, 0.0) }
            assertFailsWith<IllegalArgumentException> { WindowOrigin(0.0, value) }
            assertFailsWith<IllegalArgumentException> { ScreenArea(value, 0.0, 100.0, 100.0) }
        }
    }

    @Test
    fun `dimensions must remain finite positive and nonzero after native rounding`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0, 0.49)) {
            val widthError = assertFailsWith<IllegalArgumentException> { WindowBounds(origin, value, 480.0) }
            assertTrue(widthError.message.orEmpty().contains("width"), widthError.message)
            val heightError = assertFailsWith<IllegalArgumentException> { WindowBounds(origin, 640.0, value) }
            assertTrue(heightError.message.orEmpty().contains("height"), heightError.message)
            assertFailsWith<IllegalArgumentException> { ScreenArea(0.0, 0.0, value, 100.0) }
            assertFailsWith<IllegalArgumentException> { ScreenArea(0.0, 0.0, 100.0, value) }
        }
        assertEquals(0.5, WindowBounds(origin, 0.5, 0.5).width)
    }

    @Test
    fun `float and native integer overflow never reach setters`() {
        for (value in listOf(Double.MAX_VALUE, Int.MAX_VALUE.toDouble(), Int.MAX_VALUE.toDouble() + 1, Int.MIN_VALUE.toDouble() - 256)) {
            assertFailsWith<IllegalArgumentException> { WindowOrigin(value, 0.0) }
        }
        assertFailsWith<IllegalArgumentException> { WindowBounds(origin, Int.MAX_VALUE.toDouble(), 480.0) }
        val safe = WindowBounds(WindowOrigin(Int.MIN_VALUE.toDouble(), 40.0), 2147483520.0, 480.0)
        assertEquals(2147483520.0, safe.width)
    }

    @Test
    fun `public model types use value equality and readable strings`() {
        val first = WindowSnapshot(WindowBounds(origin, 640.0, 480.0), monitor = SavedMonitor("a", ScreenArea(0.0, 0.0, 10.0, 10.0)))
        val second =
            WindowSnapshot(
                WindowBounds(WindowOrigin(-1200.0, 40.0), 640.0, 480.0),
                monitor = SavedMonitor("a", ScreenArea(0.0, 0.0, 10.0, 10.0)),
            )
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, WindowSnapshot(WindowBounds(null, 640.0, 480.0)))
        assertEquals(
            "WindowSnapshot(normalBounds=WindowBounds(position=WindowOrigin(x=-1200.0, y=40.0), width=640.0, height=480.0), " +
                "placement=Floating, monitor=SavedMonitor(id=a, workArea=ScreenArea(x=0.0, y=0.0, width=10.0, height=10.0)))",
            first.toString(),
        )
        val cause = IllegalStateException("boom")
        assertEquals(
            WindowPersistenceError(WindowPersistenceOperation.Save, cause),
            WindowPersistenceError(WindowPersistenceOperation.Save, cause),
        )
        assertNotEquals(
            WindowPersistenceError(WindowPersistenceOperation.Save, cause),
            WindowPersistenceError(WindowPersistenceOperation.Flush, cause),
        )
    }

    @Test
    fun `invalid caller dimensions fail with the caller label`() {
        for (value in listOf(0.0, -2.0, Double.NaN, Double.POSITIVE_INFINITY, Int.MAX_VALUE.toDouble(), 0.49)) {
            val error =
                assertFailsWith<IllegalArgumentException> { validateDimensions(DpSize(value.toFloat().dp, 600.dp), "minimum size") }
            assertTrue(error.message.orEmpty().startsWith("Invalid minimum size"), error.message)
            assertFailsWith<IllegalArgumentException> { validateDimensions(DpSize(800.dp, value.toFloat().dp), "default size") }
        }
        assertEquals(Dimensions(800.0, 600.0), validateDimensions(DpSize(800.dp, 600.dp), "default size"))
    }

    @Test
    fun `identities reject blank and count unicode code points rather than UTF16 units`() {
        for (id in listOf("", " \t\n", "a".repeat(257), "😀".repeat(257))) {
            assertFailsWith<IllegalArgumentException> { validateIdentity(id, "main") }
            assertFailsWith<IllegalArgumentException> { validateIdentity("app", id) }
        }
        validateIdentity("😀".repeat(256), "folder/main")
        validateIdentity("a".repeat(256), "main")
    }
}
