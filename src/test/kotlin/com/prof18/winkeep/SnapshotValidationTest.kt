package com.prof18.winkeep

import com.prof18.winkeep.internal.InvalidSnapshotException
import com.prof18.winkeep.internal.validateDefaults
import com.prof18.winkeep.internal.validateIdentity
import com.prof18.winkeep.internal.validateSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SnapshotValidationTest {
    private val valid = WindowSnapshot(normalBounds = WindowBounds(-1200.0, 40.0, 640.0, 480.0))

    @Test
    fun `valid positions preserve negative coordinates and null pairs`() {
        assertEquals(valid, validateSnapshot(valid))
        val unspecified = valid.copy(normalBounds = valid.normalBounds.copy(x = null, y = null))
        assertEquals(unspecified, validateSnapshot(unspecified))
        assertEquals(valid.copy(placement = SavedPlacement.Fullscreen), validateSnapshot(valid.copy(placement = SavedPlacement.Fullscreen)))
    }

    @Test
    fun `partial position and nonfinite coordinates are corrupt`() {
        for (bounds in listOf(
            valid.normalBounds.copy(x = null),
            valid.normalBounds.copy(y = null),
            valid.normalBounds.copy(x = Double.NaN),
            valid.normalBounds.copy(y = Double.POSITIVE_INFINITY),
            valid.normalBounds.copy(x = Double.NEGATIVE_INFINITY),
        )) {
            assertFailsWith<InvalidSnapshotException> { validateSnapshot(valid.copy(normalBounds = bounds)) }
        }
    }

    @Test
    fun `dimensions must remain finite positive and nonzero after native rounding`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0, 0.49)) {
            assertFailsWith<InvalidSnapshotException> {
                validateSnapshot(valid.copy(normalBounds = valid.normalBounds.copy(width = value)))
            }
            assertFailsWith<InvalidSnapshotException> {
                validateSnapshot(valid.copy(normalBounds = valid.normalBounds.copy(height = value)))
            }
        }
        val smallest = valid.copy(normalBounds = valid.normalBounds.copy(width = 0.5, height = 0.5))
        assertEquals(smallest, validateSnapshot(smallest))
    }

    @Test
    fun `float and native integer overflow never reach setters`() {
        for (value in listOf(Double.MAX_VALUE, Int.MAX_VALUE.toDouble(), Int.MAX_VALUE.toDouble() + 1, Int.MIN_VALUE.toDouble() - 256)) {
            assertFailsWith<InvalidSnapshotException> {
                validateSnapshot(valid.copy(normalBounds = valid.normalBounds.copy(x = value)))
            }
        }
        assertFailsWith<InvalidSnapshotException> {
            validateSnapshot(valid.copy(normalBounds = valid.normalBounds.copy(width = Int.MAX_VALUE.toDouble())))
        }
        val safe = valid.copy(normalBounds = valid.normalBounds.copy(x = Int.MIN_VALUE.toDouble(), width = 2147483520.0))
        assertEquals(safe, validateSnapshot(safe))
    }

    @Test
    fun `unsupported schema is distinguished from malformed current records`() {
        assertEquals("2", assertFailsWith<UnsupportedSchemaException> { validateSnapshot(valid.copy(schemaVersion = 2)) }.version)
        for (version in listOf(0, -1)) {
            assertFailsWith<InvalidSnapshotException> { validateSnapshot(valid.copy(schemaVersion = version)) }
        }
    }

    @Test
    fun `monitor requires concrete valid usable bounds`() {
        val monitor = SavedMonitor("secondary", WindowBounds(-1920.0, 25.0, 1920.0, 1055.0))
        val snapshot = valid.copy(monitor = monitor)
        assertEquals(snapshot, validateSnapshot(snapshot))
        for (workArea in listOf(
            monitor.workArea.copy(x = null, y = null),
            monitor.workArea.copy(width = 0.0),
            monitor.workArea.copy(height = Double.NaN),
        )) {
            assertFailsWith<InvalidSnapshotException> { validateSnapshot(valid.copy(monitor = monitor.copy(workArea = workArea))) }
        }
    }

    @Test
    fun `invalid caller dimensions fail before screen fallback`() {
        for (value in listOf(0.0, -2.0, Double.NaN, Double.POSITIVE_INFINITY, Int.MAX_VALUE.toDouble(), 0.49)) {
            assertFailsWith<IllegalArgumentException> { validateDefaults(WindowBounds(null, null, value, 600.0)) }
            assertFailsWith<IllegalArgumentException> { validateDefaults(WindowBounds(null, null, 800.0, value)) }
        }
        assertEquals(WindowBounds(null, null, 800.0, 600.0), validateDefaults(WindowBounds(null, null, 800.0, 600.0)))
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
