package com.prof18.winkeep

import com.prof18.winkeep.internal.SnapshotDecodeException
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.runBlocking
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreferencesWindowStateStoreTest {
    private val snapshot =
        WindowSnapshot(
            normalBounds = WindowBounds(WindowOrigin(-120.0, 55.0), 700.0, 500.0),
            placement = SavedPlacement.Maximized,
            monitor = SavedMonitor("display/A", ScreenArea(-300.0, 0.0, 1200.0, 900.0)),
        )

    @Test
    fun `map settings store round trips value and flushes`() =
        runBlocking {
            val settings = MapSettings()
            var flushes = 0
            val store = PreferencesWindowStateStore(APP_ID, WINDOW_ID, settings) { flushes++ }

            store.save(snapshot)
            assertEquals(snapshot, store.load())
            store.flush()

            assertEquals(1, flushes)
            assertTrue(settings.hasKey(PreferencesWindowStateStore.RECORD_KEY))
        }

    @Test
    fun `identities remain isolated and lazy factory initializes once`() =
        runBlocking {
            val backing = MapSettings()
            var creations = 0
            val first =
                PreferencesWindowStateStore(APP_ID, WINDOW_ID) {
                    creations++
                    PreferencesBacking(backing) {}
                }
            val otherWindow = PreferencesWindowStateStore(APP_ID, "other-window", MapSettings()) {}

            assertEquals(0, creations)
            assertEquals(null, first.load())
            first.save(snapshot)
            otherWindow.save(WindowSnapshot(snapshot.normalBounds, SavedPlacement.Fullscreen, snapshot.monitor))
            assertEquals(snapshot, first.load())
            assertEquals(1, creations)
        }

    @Test
    fun `fresh real adapter reads flushed record and exact node is cleaned up`() =
        runBlocking {
            val appId = "$APP_ID-real-${System.nanoTime()}"
            val windowId = "$WINDOW_ID-real-${System.nanoTime()}"
            val otherAppId = "$APP_ID-other-${System.nanoTime()}"
            val nodePath = PreferencesWindowStateStore.nodePath(appId, windowId)
            val otherNodePath = PreferencesWindowStateStore.nodePath(otherAppId, windowId)
            val node = Preferences.userRoot().node(nodePath)
            val otherNode = Preferences.userRoot().node(otherNodePath)
            try {
                val writer = PreferencesWindowStateStore(appId, windowId)
                writer.save(snapshot)
                writer.flush()

                val freshReader = PreferencesWindowStateStore(appId, windowId)
                assertEquals(snapshot, freshReader.load())
                assertEquals(null, PreferencesWindowStateStore(otherAppId, windowId).load())
            } finally {
                node.removeNode()
                otherNode.removeNode()
                Preferences.userRoot().flush()
            }
        }

    @Test
    fun `decode failure leaves malformed raw record untouched`() =
        runBlocking {
            val settings = MapSettings().also { it.putString(PreferencesWindowStateStore.RECORD_KEY, "not-json") }
            val store = PreferencesWindowStateStore(APP_ID, WINDOW_ID, settings) {}

            assertFailsWith<SnapshotDecodeException> { store.load() }
            assertEquals("not-json", settings.getString(PreferencesWindowStateStore.RECORD_KEY, ""))
        }

    @Test
    fun `save and flush surface backing failures`() =
        runBlocking {
            val failingSettings =
                object : Settings by MapSettings() {
                    override fun putString(
                        key: String,
                        value: String,
                    ) {
                        error("write failed")
                    }
                }
            val store = PreferencesWindowStateStore(APP_ID, WINDOW_ID, failingSettings) {}

            assertFalse(runCatching { store.save(snapshot) }.isSuccess)
            assertTrue(
                runCatching { PreferencesWindowStateStore(APP_ID, WINDOW_ID, MapSettings()) { error("flush failed") }.flush() }.isFailure,
            )
        }

    @Test
    fun `future version leaves raw record untouched and initialization failure propagates`() =
        runBlocking {
            val raw = "{\"schemaVersion\":9999999999999999999999999999,\"unknown\":true}"
            val settings = MapSettings().also { it.putString(PreferencesWindowStateStore.RECORD_KEY, raw) }
            val store = PreferencesWindowStateStore(APP_ID, WINDOW_ID, settings) {}
            assertFailsWith<UnsupportedSchemaException> { store.load() }
            assertEquals(raw, settings.getString(PreferencesWindowStateStore.RECORD_KEY, ""))
            var calls = 0
            val unavailable =
                PreferencesWindowStateStore(APP_ID, WINDOW_ID) {
                    calls++
                    error("backing unavailable")
                }
            assertEquals(0, calls)
            assertFailsWith<IllegalStateException> { unavailable.load() }
            assertEquals(1, calls)
        }

    @Test
    fun `hashed identity distinguishes slash paths and satisfies node limits`() {
        val first = PreferencesWindowStateStore.nodePath("a/b", "c")
        val second = PreferencesWindowStateStore.nodePath("a", "b/c")
        assertTrue(first != second)
        assertTrue(first.split('/').all { it.length <= Preferences.MAX_NAME_LENGTH })
        assertFailsWith<IllegalArgumentException> { PreferencesWindowStateStore(" ", WINDOW_ID) }
    }

    @Test
    fun `value limit counts string characters and oversized record never replaces existing value`() =
        runBlocking {
            val settings = MapSettings()
            val store = PreferencesWindowStateStore(APP_ID, WINDOW_ID, settings) {}
            val unicode = withMonitorId("界".repeat(3000))
            store.save(unicode)
            assertEquals(unicode, store.load())
            val raw = settings.getString(PreferencesWindowStateStore.RECORD_KEY, "")
            assertFailsWith<IllegalArgumentException> {
                store.save(withMonitorId("x".repeat(Preferences.MAX_VALUE_LENGTH)))
            }
            assertEquals(raw, settings.getString(PreferencesWindowStateStore.RECORD_KEY, ""))
        }

    @Test
    fun `clear removes only this record and flushes`() =
        runBlocking {
            val settings = MapSettings().also { it.putString("unrelated", "kept") }
            var flushes = 0
            val store = PreferencesWindowStateStore(APP_ID, WINDOW_ID, settings) { flushes++ }
            store.save(snapshot)

            store.clear()

            assertEquals(null, store.load())
            assertFalse(settings.hasKey(PreferencesWindowStateStore.RECORD_KEY))
            assertEquals("kept", settings.getString("unrelated", ""))
            assertEquals(1, flushes)
            store.clear()
            assertEquals(2, flushes)
        }

    @Test
    fun `clear on the real adapter is visible to a fresh reader and leaves other identities alone`() =
        runBlocking {
            val appId = "$APP_ID-clear-${System.nanoTime()}"
            val windowId = "$WINDOW_ID-clear-${System.nanoTime()}"
            val node = Preferences.userRoot().node(PreferencesWindowStateStore.nodePath(appId, windowId))
            val otherNode = Preferences.userRoot().node(PreferencesWindowStateStore.nodePath(appId, "$windowId-other"))
            try {
                val writer = PreferencesWindowStateStore(appId, windowId)
                val other = PreferencesWindowStateStore(appId, "$windowId-other")
                writer.save(snapshot)
                other.save(snapshot)
                writer.flush()
                other.flush()

                PreferencesWindowStateStore(appId, windowId).clear()

                assertEquals(null, PreferencesWindowStateStore(appId, windowId).load())
                assertEquals(snapshot, PreferencesWindowStateStore(appId, "$windowId-other").load())
            } finally {
                node.removeNode()
                otherNode.removeNode()
                Preferences.userRoot().flush()
            }
        }

    private fun withMonitorId(id: String) =
        WindowSnapshot(snapshot.normalBounds, snapshot.placement, SavedMonitor(id, requireNotNull(snapshot.monitor).workArea))

    private companion object {
        const val APP_ID = "store-test-app/isolated"
        const val WINDOW_ID = "store-test-window/main"
    }
}
