package com.prof18.winkeep

import com.prof18.winkeep.internal.SnapshotCodec
import com.prof18.winkeep.internal.SnapshotDecodeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SnapshotCodecTest {
    private val normal = WindowBounds(-900.0, 70.0, 650.0, 490.0)
    private val workArea = WindowBounds(-1000.0, 20.0, 1000.0, 800.0)
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
        val value = WindowSnapshot(normalBounds = WindowBounds(null, null, 800.0, 600.0))
        assertEquals(value, SnapshotCodec.decode(SnapshotCodec.encode(value, APP_ID, WINDOW_ID), APP_ID, WINDOW_ID))
    }

    @Test
    fun `rejects malformed json wrong types and incomplete position pair`() {
        val invalid =
            listOf(
                "{",
                "[]",
                base(schemaVersion = "\"1\""),
                base(normalBounds = "\"wrong\""),
                base(normalBounds = "{\"x\":1,\"y\":null,\"width\":2,\"height\":3}"),
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
        assertEquals(snapshot.copy(placement = SavedPlacement.Floating, monitor = null), SnapshotCodec.decode(raw, APP_ID, WINDOW_ID))
    }

    private fun base(
        schemaVersion: String = "1",
        normalBounds: String = "{\"x\":-900,\"y\":70,\"width\":650,\"height\":490}",
        placement: String = "\"Floating\"",
    ) =
        """{"schemaVersion":$schemaVersion,"appId":"$APP_ID","windowId":"$WINDOW_ID","normalBounds":$normalBounds,"placement":$placement,"monitor":null}"""

    private companion object {
        const val APP_ID = "codec-test-app/one"
        const val WINDOW_ID = "codec-test-window/two"
    }
}
