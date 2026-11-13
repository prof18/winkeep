package com.prof18.winkeep

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.prof18.winkeep.internal.NativeObservation
import com.prof18.winkeep.internal.Rect
import com.prof18.winkeep.internal.ScreenInfo
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WinKeepStateTest {
    @Test
    fun `delayed load keeps defaults unapplied and persistence disabled until restoration completes`() =
        runTest {
            val loaded = CompletableDeferred<WindowSnapshot?>()
            lateinit var store: FakeStore
            store = FakeStore(beforeLoad = { store.loaded = loaded.await() })
            val ui = StandardTestDispatcher(testScheduler)
            val io = StandardTestDispatcher(testScheduler)
            val state = controller(backgroundScope, store, ui, io)
            val beforeRestore = state.windowState

            val initialization = async { state.initialize() }
            runCurrent()
            assertFalse(state.isReady)
            assertSame(beforeRestore, state.windowState)
            assertEquals(0, store.saves.size)

            loaded.complete(snapshot(width = 777.0))
            advanceUntilIdle()

            assertTrue(state.isReady)
            assertEquals(777.dp, state.windowState.size.width)
            assertEquals(0, store.saves.size)
            initialization.await()
        }

    @Test
    fun `load failure reports load error and makes validated defaults ready`() =
        runTest {
            val errors = mutableListOf<WindowPersistenceError>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val state =
                controller(backgroundScope, FakeStore(beforeLoad = { error("store unavailable") }), dispatcher, dispatcher, errors::add)

            state.initialize()

            assertTrue(state.isReady)
            assertEquals(WindowPersistenceOperation.Load, errors.single().operation)
            assertEquals(640.dp, state.windowState.size.width)
        }

    @Test
    fun `future schema opens defaults and terminal close preserves record without writes`() =
        runTest {
            val raw = "{\"schemaVersion\":9999999999999999999999999999,\"unknown\":true}"
            val settings = MapSettings().also { it.putString(PreferencesWindowStateStore.RECORD_KEY, raw) }
            val backing = PreferencesWindowStateStore(APP_ID, WINDOW_ID, settings) {}
            val errors = mutableListOf<WindowPersistenceError>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val state = controller(backgroundScope, backing, dispatcher, dispatcher, errors::add)
            state.initialize()

            assertTrue(state.isReady)
            assertEquals(WindowPersistenceOperation.Load, errors.single().operation)
            state.attach { observation(width = 888.0) }
            state.commit(requireNotNull(state.observe(observation())))
            assertEquals(WindowSaveResult.ReadOnly, state.save())
            assertEquals(WindowSaveResult.ReadOnly, state.saveOnClose())
            assertEquals(raw, settings.getString(PreferencesWindowStateStore.RECORD_KEY, ""))
        }

    @Test
    fun `custom store that cannot build a valid snapshot reports load and falls back to defaults`() =
        runTest {
            val errors = mutableListOf<WindowPersistenceError>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val state =
                controller(
                    backgroundScope,
                    FakeStore(beforeLoad = { snapshot(width = Double.NaN) }),
                    dispatcher,
                    dispatcher,
                    errors::add,
                )

            state.initialize()

            assertTrue(state.isReady)
            assertEquals(WindowPersistenceOperation.Load, errors.single().operation)
            assertTrue(errors.single().cause is IllegalArgumentException)
            assertEquals(640.dp, state.windowState.size.width)
        }

    @Test
    fun `malformed default-store record reports decode and falls back to defaults`() =
        runTest {
            val settings = MapSettings().also { it.putString(PreferencesWindowStateStore.RECORD_KEY, "not-json") }
            val errors = mutableListOf<WindowPersistenceError>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val state =
                controller(
                    backgroundScope,
                    PreferencesWindowStateStore(APP_ID, WINDOW_ID, settings) {},
                    dispatcher,
                    dispatcher,
                    errors::add,
                )

            state.initialize()

            assertTrue(state.isReady)
            assertEquals(WindowPersistenceOperation.Decode, errors.single().operation)
            assertEquals(640.dp, state.windowState.size.width)
        }

    @Test
    fun `minimum size raises a tiny saved record at restoration`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val tiny = WindowSnapshot(normalBounds = WindowBounds(WindowOrigin(20.0, 30.0), 1.0, 1.0))
            val state =
                controller(backgroundScope, FakeStore(loaded = tiny), dispatcher, dispatcher, minimumSize = DpSize(360.dp, 294.dp))

            state.initialize()

            assertEquals(DpSize(360.dp, 294.dp), state.windowState.size)
            assertEquals(WindowBounds(WindowOrigin(20.0, 30.0), 360.0, 294.0), state.restored?.normalBounds)
        }

    @Test
    fun `invalid minimum size fails at construction`() {
        for (size in listOf(DpSize(0.dp, 100.dp), DpSize(100.dp, (-1).dp), DpSize(androidx.compose.ui.unit.Dp.Infinity, 100.dp))) {
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                WinKeepState(
                    CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
                    DpSize(640.dp, 480.dp),
                    FakeStore(),
                    { emptyList() },
                    kotlinx.coroutines.Dispatchers.Unconfined,
                    kotlinx.coroutines.Dispatchers.Unconfined,
                    onError = {},
                    minimumSize = size,
                )
            }
        }
    }

    @Test
    fun `screen enumeration failure reports screens and still opens defaults`() =
        runTest {
            val errors = mutableListOf<WindowPersistenceError>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val state =
                controller(
                    backgroundScope,
                    FakeStore(),
                    dispatcher,
                    dispatcher,
                    errors::add,
                    screenProvider = { error("screen lookup unavailable") },
                )

            state.initialize()

            assertTrue(state.isReady)
            assertEquals(WindowPersistenceOperation.Screens, errors.single().operation)
            assertEquals(640.dp, state.windowState.size.width)
        }

    @Test
    fun `cancellation during load propagates and does not mark controller ready`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val state =
                controller(
                    backgroundScope,
                    FakeStore(beforeLoad = {
                        entered.complete(Unit)
                        release.await()
                    }),
                    dispatcher,
                    dispatcher,
                )
            val initialization = async { state.initialize() }
            runCurrent()
            assertTrue(entered.isCompleted)

            initialization.cancel()
            runCurrent()

            assertTrue(initialization.isCancelled)
            assertFalse(state.isReady)
        }

    @Test
    fun `latest error callback is used when persistence reports after replacement`() =
        runTest {
            val enteredSave = CompletableDeferred<Unit>()
            val releaseSave = CompletableDeferred<Unit>()
            val firstErrors = mutableListOf<WindowPersistenceError>()
            val latestErrors = mutableListOf<WindowPersistenceError>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store =
                FakeStore(beforeSave = {
                    enteredSave.complete(Unit)
                    releaseSave.await()
                    error("save failed")
                })
            val state = controller(backgroundScope, store, dispatcher, dispatcher, firstErrors::add)
            state.initialize()
            state.onError = latestErrors::add
            state.attach { observation() }
            val generation = state.observe(observation())
            state.commit(requireNotNull(generation))

            val closing = async { state.saveOnClose() }
            runCurrent()
            assertTrue(enteredSave.isCompleted)
            releaseSave.complete(Unit)
            runCurrent()

            assertEquals(WindowSaveResult.Failed, closing.await())
            assertTrue(firstErrors.isEmpty())
            assertEquals(WindowPersistenceOperation.Save, latestErrors.single().operation)
        }

    @Test
    fun `terminal close captures final observation and flushes only after saving it`() =
        runTest {
            val events = mutableListOf<String>()
            val finalObservation = observation(width = 888.0)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = FakeStore(beforeSave = { events += "save:${it.normalBounds.width}" }, beforeFlush = { events += "flush" })
            val state = controller(backgroundScope, store, dispatcher, dispatcher)
            state.initialize()
            state.attach {
                events += "capture"
                finalObservation
            }
            val generation = state.observe(observation())
            state.commit(requireNotNull(generation))

            assertEquals(WindowSaveResult.Saved, state.saveOnClose())

            assertEquals(listOf("capture", "save:888.0", "flush"), events)
            assertEquals(
                888.0,
                store.saves
                    .single()
                    .normalBounds.width,
            )
            assertEquals(null, state.observe(observation(width = 999.0)))
        }

    @Test
    fun `close before any observation has nothing to save`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = FakeStore()
            val state = controller(backgroundScope, store, dispatcher, dispatcher)
            assertEquals(WindowSaveResult.NothingToSave, state.save())
            state.initialize()
            assertEquals(WindowSaveResult.NothingToSave, state.save())
            assertEquals(WindowSaveResult.NothingToSave, state.saveOnClose())
            assertEquals(WindowSaveResult.NothingToSave, state.saveOnClose())
            assertTrue(store.saves.isEmpty())
            assertEquals(0, store.flushes)
        }

    @Test
    fun `explicit save captures current geometry, flushes and keeps observing`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = FakeStore()
            val state = controller(backgroundScope, store, dispatcher, dispatcher)
            state.initialize()
            var current = observation(width = 810.0)
            state.attach { current }

            assertEquals(WindowSaveResult.Saved, state.save())
            assertEquals(
                810.0,
                store.saves
                    .single()
                    .normalBounds.width,
            )
            assertEquals(1, store.flushes)

            current = observation(width = 820.0)
            state.commit(requireNotNull(state.observe(current)))
            advanceTimeBy(250)
            runCurrent()
            assertEquals(listOf(810.0, 820.0), store.saves.map { it.normalBounds.width })

            current = observation(width = 830.0)
            assertEquals(WindowSaveResult.Saved, state.saveOnClose())
            assertEquals(WindowSaveResult.NothingToSave, state.save())
            assertEquals(listOf(810.0, 820.0, 830.0), store.saves.map { it.normalBounds.width })
            assertEquals(2, store.flushes)
        }

    @Test
    fun `cancelled storage operation is rethrown`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val state =
                controller(backgroundScope, FakeStore(beforeLoad = { throw CancellationException("cancel") }), dispatcher, dispatcher)
            val initialization = async { state.initialize() }

            runCurrent()

            assertTrue(initialization.isCancelled)
            assertFalse(state.isReady)
        }

    @Test
    fun `default store factory runs off EDT and screens run on EDT`() =
        kotlinx.coroutines.runBlocking {
            var backingCreated = false
            val backing =
                PreferencesWindowStateStore(APP_ID, WINDOW_ID) {
                    assertFalse(java.awt.EventQueue.isDispatchThread())
                    backingCreated = true
                    PreferencesBacking(MapSettings()) {}
                }
            assertFalse(backingCreated)
            val state =
                WinKeepState(this, DpSize(640.dp, 480.dp), backing, {
                    assertTrue(java.awt.EventQueue.isDispatchThread())
                    emptyList()
                }, kotlinx.coroutines.Dispatchers.Main.immediate, kotlinx.coroutines.Dispatchers.IO, {}, DpSize.Unspecified)
            state.initialize()
            assertTrue(backingCreated)
            assertTrue(state.isReady)
        }

    @Test
    fun `duplicate attachments fail clearly and owner cancellation reaches close write`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val ownerJob = kotlinx.coroutines.Job()
            val owner = CoroutineScope(dispatcher + ownerJob)
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val store =
                FakeStore(beforeSave = {
                    entered.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        cancelled.complete(Unit)
                    }
                })
            val state = controller(owner, store, dispatcher, dispatcher)
            state.initialize()
            state.attach { observation() }
            kotlin.test.assertFailsWith<IllegalStateException> { state.attach { observation() } }
            val close = async { state.saveOnClose() }
            runCurrent()
            assertTrue(entered.isCompleted)
            ownerJob.cancel()
            runCurrent()
            assertTrue(cancelled.isCompleted)
            kotlin.test.assertFailsWith<CancellationException> { close.await() }
        }

    @Test
    fun `duplicate live identities fail and released identity can be reused`() {
        val release =
            com.prof18.winkeep.internal.ActiveWindowIdentities
                .acquire(APP_ID, WINDOW_ID)
        try {
            kotlin.test.assertFailsWith<IllegalStateException> {
                com.prof18.winkeep.internal.ActiveWindowIdentities
                    .acquire(APP_ID, WINDOW_ID)
            }
        } finally {
            release()
        }
        com.prof18.winkeep.internal.ActiveWindowIdentities
            .acquire(APP_ID, WINDOW_ID)
            .invoke()
    }

    private fun controller(
        scope: CoroutineScope,
        store: WindowStateStore,
        ui: kotlinx.coroutines.CoroutineDispatcher,
        io: kotlinx.coroutines.CoroutineDispatcher,
        onError: (WindowPersistenceError) -> Unit = {},
        screenProvider: () -> List<ScreenInfo> = { listOf(screen) },
        minimumSize: DpSize = DpSize.Unspecified,
    ): WinKeepState =
        WinKeepState(
            scope = scope,
            defaultSize = DpSize(640.dp, 480.dp),
            store = store,
            screenProvider = screenProvider,
            uiDispatcher = ui,
            ioDispatcher = io,
            onError = onError,
            minimumSize = minimumSize,
        )

    private fun observation(width: Double = 700.0) =
        NativeObservation(
            bounds = Rect(20.0, 30.0, width, 480.0),
            composePlacement = SavedPlacement.Floating,
            nativePlacement = SavedPlacement.Floating,
            monitor = SavedMonitor("screen", ScreenArea(0.0, 0.0, 1600.0, 1000.0)),
        )

    private fun snapshot(width: Double = 700.0) = WindowSnapshot(normalBounds = WindowBounds(WindowOrigin(20.0, 30.0), width, 480.0))

    private class FakeStore(
        var loaded: WindowSnapshot? = null,
        private val beforeLoad: suspend () -> Unit = {},
        private val beforeSave: suspend (WindowSnapshot) -> Unit = {},
        private val beforeFlush: suspend () -> Unit = {},
    ) : WindowStateStore {
        val saves = mutableListOf<WindowSnapshot>()
        var flushes = 0

        override suspend fun load(): WindowSnapshot? {
            beforeLoad()
            return loaded
        }

        override suspend fun save(snapshot: WindowSnapshot) {
            beforeSave(snapshot)
            saves += snapshot
        }

        override suspend fun flush() {
            beforeFlush()
            flushes++
        }

        override suspend fun clear() = Unit
    }

    private companion object {
        const val APP_ID = "controller-test-app"
        const val WINDOW_ID = "controller-test-window"
        val screen = ScreenInfo("screen", Rect(0.0, 0.0, 1600.0, 1000.0), isDefault = true)
    }
}
