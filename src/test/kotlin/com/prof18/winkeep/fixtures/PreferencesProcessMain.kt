package com.prof18.winkeep.fixtures

import com.prof18.winkeep.PreferencesWindowStateStore
import com.prof18.winkeep.SavedMonitor
import com.prof18.winkeep.SavedPlacement
import com.prof18.winkeep.ScreenArea
import com.prof18.winkeep.UnsupportedSchemaException
import com.prof18.winkeep.WindowBounds
import com.prof18.winkeep.WindowOrigin
import com.prof18.winkeep.WindowSnapshot
import com.prof18.winkeep.internal.SnapshotDecodeException
import kotlinx.coroutines.runBlocking

public object PreferencesProcessMain {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 3) { "Expected mode, app ID, and window ID" }
        val (mode, appId, windowId) = args
        val store = PreferencesWindowStateStore(appId, windowId)
        runBlocking {
            when (mode) {
                "writer" -> {
                    store.save(expectedSnapshot)
                    store.flush()
                }
                "reader" -> check(store.load() == expectedSnapshot) { "Separate process did not restore the complete snapshot" }
                "corrupt" -> {
                    val failure = runCatching { store.load() }.exceptionOrNull()
                    check(failure is SnapshotDecodeException) { "Expected SnapshotDecodeException, got $failure" }
                }
                "future" -> {
                    val failure = runCatching { store.load() }.exceptionOrNull()
                    check(failure is UnsupportedSchemaException) { "Expected UnsupportedSchemaException, got $failure" }
                }
                else -> error("Unknown fixture mode: $mode")
            }
        }
    }

    private val expectedSnapshot =
        WindowSnapshot(
            normalBounds = WindowBounds(WindowOrigin(-134.5, 72.25), 1112.0, 748.5),
            placement = SavedPlacement.Fullscreen,
            monitor = SavedMonitor("process-display/primary", ScreenArea(-1920.0, 0.0, 1920.0, 1080.0)),
        )
}
