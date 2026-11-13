package com.prof18.winkeep

import com.prof18.winkeep.internal.SnapshotCodec
import com.prof18.winkeep.internal.SnapshotDecodeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnapshotCodecTest {
    private val normal = WindowBounds(WindowOrigin(-900.0, 70.0), 650.0, 490.0)
    private val workArea = ScreenArea(-1000.0, 20.0, 1000.0, 800.0)
    private val snapshot =
        WindowSnapshot(
            normalBounds = normal,
            placement = SavedPlacement.Fullscreen,
            monitor = SavedMonitor("display/one", workArea),
        )

    @Test
    fun `round trip preserves fullscreen geometry and monitor`() {
        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot, APP_ID, WINDOW_ID), APP_ID, WINDOW_ID))
    }

    @Test
    fun `round trip preserves unspecified position and absent monitor`() {
        val value = WindowSnapshot(normalBounds = WindowBounds(null, 800.0, 600.0))
        assertEquals(value, SnapshotCodec.decode(SnapshotCodec.encode(value, APP_ID, WINDOW_ID), APP_ID, WINDOW_ID))
    }

    @Test
    fun `writes schema version one in the stored record`() {
        val encoded = SnapshotCodec.encode(snapshot, APP_ID, WINDOW_ID)
        assertTrue(encoded.startsWith("{\"schemaVersion\":1,"), encoded)
        assertTrue(encoded.contains("\"workArea\":{\"x\":-1000.0,\"y\":20.0,\"width\":1000.0,\"height\":800.0}"), encoded)
    }

    @Test
    fun `rejects malformed json wrong types incomplete position pair and invalid geometry`() {
        val invalid =
            listOf(
                "{",
                "[]",
                base(schemaVersion = "\"1\""),
                base(normalBounds = "\"wrong\""),
                base(normalBounds = "{\"x\":1,\"y\":null,\"width\":2,\"height\":3}"),
                base(normalBounds = "{\"x\":1,\"y\":1,\"width\":0,\"height\":3}"),
                base(normalBounds = "{\"x\":1,\"y\":1,\"width\":2,\"height\":0.2}"),
                base(normalBounds = "{\"x\":3000000000,\"y\":1,\"width\":2,\"height\":3}"),
                base(monitor = "{\"id\":\"m\",\"workArea\":{\"x\":null,\"y\":null,\"width\":2,\"height\":3}}"),
                base(monitor = "{\"id\":\"m\",\"workArea\":{\"x\":0,\"y\":0,\"width\":0,\"height\":3}}"),
            )
        invalid.forEach { raw -> assertFailsWith<SnapshotDecodeException> { SnapshotCodec.decode(raw, APP_ID, WINDOW_ID) } }
    }

    @Test
    fun `rejects numeric overflow`() {
        val raw = base(normalBounds = "{\"x\":1e9999,\"y\":1,\"width\":2,\"height\":3}")
        assertFailsWith<SnapshotDecodeException> { SnapshotCodec.decode(raw, APP_ID, WINDOW_ID) }
    }

    @Test
    fun `rejects mismatched identity including slash-bearing values`() {
        val encoded = SnapshotCodec.encode(snapshot, "app/one", "window/two")
        assertFailsWith<SnapshotDecodeException> { SnapshotCodec.decode(encoded, "app/one", "window/other") }
        assertEquals(snapshot, SnapshotCodec.decode(encoded, "app/one", "window/two"))
    }

    @Test
    fun `future schema is detected before version-specific fields and accepts huge integer`() {
        val raw = "{\"schemaVersion\":999999999999999999999999999999999999999,\"futureLayout\":true}"
        val error = assertFailsWith<UnsupportedSchemaException> { SnapshotCodec.decode(raw, APP_ID, WINDOW_ID) }
        assertEquals("999999999999999999999999999999999999999", error.version)
    }

    @Test
    fun `rejects nonpositive fractional and exponent schema versions`() {
        listOf("0", "-1", "1.0", "1e0").forEach { version ->
            assertFailsWith<SnapshotDecodeException> { SnapshotCodec.decode(base(schemaVersion = version), APP_ID, WINDOW_ID) }
        }
    }

    @Test
    fun `ignores unknown fields and normalizes unknown placement`() {
        val raw = base(placement = "\"Docked\"").replaceFirst("{", "{\"ignored\":{\"nested\":true},")
        assertEquals(WindowSnapshot(normal, SavedPlacement.Floating, monitor = null), SnapshotCodec.decode(raw, APP_ID, WINDOW_ID))
    }

    private fun base(
        schemaVersion: String = "1",
        normalBounds: String = "{\"x\":-900,\"y\":70,\"width\":650,\"height\":490}",
        placement: String = "\"Floating\"",
        monitor: String = "null",
    ) =
        """{"schemaVersion":$schemaVersion,"appId":"$APP_ID","windowId":"$WINDOW_ID","normalBounds":$normalBounds,"placement":$placement,"monitor":$monitor}"""

    private companion object {
        const val APP_ID = "codec-test-app/one"
        const val WINDOW_ID = "codec-test-window/two"
    }
}
