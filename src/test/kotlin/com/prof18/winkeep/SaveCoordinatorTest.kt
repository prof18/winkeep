package com.prof18.winkeep

import com.prof18.winkeep.internal.SaveCoordinator
import com.prof18.winkeep.internal.SaveRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SaveCoordinatorTest {
    private val first = WindowSnapshot(normalBounds = WindowBounds(WindowOrigin(20.0, 30.0), 600.0, 400.0))
    private val final = WindowSnapshot(normalBounds = WindowBounds(WindowOrigin(20.0, 30.0), 700.0, 400.0))
    private val later = WindowSnapshot(normalBounds = WindowBounds(WindowOrigin(40.0, 30.0), 700.0, 400.0))

    @Test
    fun `burst saves only latest after fixed debounce`() =
        runTest {
            val store = FakeStore()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val coordinator = SaveCoordinator(backgroundScope, store, dispatcher, dispatcher)
            coordinator.offer(first)
            advanceTimeBy(200)
            coordinator.offer(final)
            advanceTimeBy(249)
            runCurrent()
            assertTrue(store.saved.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(final), store.saved)
            assertEquals(0, store.flushes)
        }

    @Test
    fun `terminal close freezes native tuple before suspension and joins repeated calls`() =
        runTest {
            val store = FakeStore()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val coordinator = SaveCoordinator(backgroundScope, store, dispatcher, dispatcher)
            coordinator.offer(first)
            var captures = 0
            val close =
                async {
                    coordinator.saveOnClose {
                        captures++
                        SaveRequest.Write(final)
                    }
                }
            runCurrent()
            coordinator.offer(first)
            val again = async { coordinator.saveOnClose { error("must not capture twice") } }
            assertEquals(WindowSaveResult.Saved, close.await())
            assertEquals(WindowSaveResult.Saved, again.await())
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(1, captures)
            assertEquals(listOf(final), store.saved)
            assertEquals(1, store.flushes)
        }

    @Test
    fun `in flight old save finishes before final save and is never canceled by timer or close`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store =
                FakeStore(beforeSave = {
                    if (it == first) {
                        entered.complete(Unit)
                        release.await()
                    }
                })
            val dispatcher = StandardTestDispatcher(testScheduler)
            val coordinator = SaveCoordinator(backgroundScope, store, dispatcher, dispatcher)
            coordinator.offer(first)
            advanceTimeBy(250)
            runCurrent()
            entered.await()
            coordinator.offer(final)
            advanceTimeBy(250)
            runCurrent()
            val close = async { coordinator.saveOnClose { SaveRequest.Write(final) } }
            runCurrent()
            assertFalse(close.isCompleted)
            release.complete(Unit)
            assertEquals(WindowSaveResult.Saved, close.await())
            assertEquals(listOf(first, final), store.saved)
            assertEquals(1, store.flushes)
        }

    @Test
    fun `failed background save reports once and next changed snapshot retries`() =
        runTest {
            val errors = mutableListOf<WindowPersistenceError>()
            var attempts = 0
            val store = FakeStore(beforeSave = { if (++attempts == 1) error("disk unavailable") })
            val dispatcher = StandardTestDispatcher(testScheduler)
            val coordinator = SaveCoordinator(backgroundScope, store, dispatcher, dispatcher, errors::add)
            coordinator.offer(first)
            advanceTimeBy(250)
            runCurrent()
            assertEquals(WindowPersistenceOperation.Save, errors.single().operation)
            coordinator.offer(final)
            advanceTimeBy(250)
            runCurrent()
            assertEquals(listOf(final), store.saved)
            assertEquals(1, errors.size)
        }

    @Test
    fun `save or flush failure returns failed and throwing callback cannot break cleanup`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            for (failSave in listOf(true, false)) {
                val store =
                    FakeStore(
                        beforeSave = { if (failSave) error("save") },
                        beforeFlush = { if (!failSave) error("flush") },
                    )
                var errors = 0
                val coordinator =
                    SaveCoordinator(backgroundScope, store, dispatcher, dispatcher, {
                        errors++
                        error("callback")
                    })
                assertEquals(WindowSaveResult.Failed, coordinator.saveOnClose { SaveRequest.Write(final) })
                assertEquals(WindowSaveResult.Failed, coordinator.saveOnClose { error("second capture") })
                coordinator.offer(first)
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(1, errors)
            }
        }

    @Test
    fun `store cancellation propagates without a corruption callback`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var errors = 0
            val store = FakeStore(beforeSave = { throw CancellationException("cancel") })
            val coordinator = SaveCoordinator(backgroundScope, store, dispatcher, dispatcher, { errors++ })
            val close = async { coordinator.saveOnClose { SaveRequest.Write(final) } }
            try {
                close.await()
                error("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertEquals(0, errors)
            }
        }

    @Test
    fun `owner cancellation cancels in flight write without orphan work`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val canceled = CompletableDeferred<Unit>()
            val owner =
                launch {
                    val dispatcher = StandardTestDispatcher(testScheduler)
                    val store =
                        FakeStore(beforeSave = {
                            entered.complete(Unit)
                            try {
                                CompletableDeferred<Unit>().await()
                            } finally {
                                canceled.complete(Unit)
                            }
                        })
                    val coordinator = SaveCoordinator(this, store, dispatcher, dispatcher)
                    coordinator.offer(first)
                }
            advanceTimeBy(250)
            runCurrent()
            entered.await()
            owner.cancelAndJoin()
            assertTrue(canceled.isCompleted)
            advanceUntilIdle()
        }

    @Test
    fun `skipped close reports its reason without touching the store`() =
        runTest {
            val store = FakeStore()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val coordinator = SaveCoordinator(backgroundScope, store, dispatcher, dispatcher)
            assertEquals(WindowSaveResult.ReadOnly, coordinator.saveOnClose { SaveRequest.Skip(WindowSaveResult.ReadOnly) })
            assertEquals(WindowSaveResult.ReadOnly, coordinator.saveOnClose { error("joined") })
            assertTrue(store.saved.isEmpty())
            assertEquals(0, store.flushes)
        }

    @Test
    fun `explicit save supersedes pending debounce and keeps observing`() =
        runTest {
            val store = FakeStore()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val coordinator = SaveCoordinator(backgroundScope, store, dispatcher, dispatcher)
            coordinator.offer(first)
            advanceTimeBy(100)
            assertEquals(WindowSaveResult.Saved, coordinator.save { SaveRequest.Write(final) })
            assertEquals(listOf(final), store.saved)
            assertEquals(1, store.flushes)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(listOf(final), store.saved)
            coordinator.offer(final)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(listOf(final), store.saved)
            coordinator.offer(later)
            advanceTimeBy(250)
            runCurrent()
            assertEquals(listOf(final, later), store.saved)
            assertEquals(1, store.flushes)
        }

    @Test
    fun `explicit save waits for an in flight debounced write`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store =
                FakeStore(beforeSave = {
                    if (it == first) {
                        entered.complete(Unit)
                        release.await()
                    }
                })
            val dispatcher = StandardTestDispatcher(testScheduler)
            val coordinator = SaveCoordinator(backgroundScope, store, dispatcher, dispatcher)
            coordinator.offer(first)
            advanceTimeBy(250)
            runCurrent()
            entered.await()
            val save = async { coordinator.save { SaveRequest.Write(final) } }
            runCurrent()
            assertFalse(save.isCompleted)
            release.complete(Unit)
            assertEquals(WindowSaveResult.Saved, save.await())
            assertEquals(listOf(first, final), store.saved)
        }

    @Test
    fun `close started before a queued explicit save owns the final write`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store =
                FakeStore(beforeSave = {
                    if (it == first) {
                        entered.complete(Unit)
                        release.await()
                    }
                })
            val dispatcher = StandardTestDispatcher(testScheduler)
            val coordinator = SaveCoordinator(backgroundScope, store, dispatcher, dispatcher)
            coordinator.offer(first)
            advanceTimeBy(250)
            runCurrent()
            entered.await()
            val save = async { coordinator.save { SaveRequest.Write(final) } }
            runCurrent()
            val close = async { coordinator.saveOnClose { SaveRequest.Write(later) } }
            runCurrent()
            release.complete(Unit)
            assertEquals(WindowSaveResult.Saved, close.await())
            assertEquals(WindowSaveResult.Saved, save.await())
            assertEquals(listOf(first, later), store.saved)
            assertEquals(1, store.flushes)
        }

    @Test
    fun `explicit save after close is a no-op and skipped saves report their reason`() =
        runTest {
            val store = FakeStore()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val coordinator = SaveCoordinator(backgroundScope, store, dispatcher, dispatcher)
            assertEquals(WindowSaveResult.NothingToSave, coordinator.save { SaveRequest.Skip(WindowSaveResult.NothingToSave) })
            assertEquals(WindowSaveResult.ReadOnly, coordinator.save { SaveRequest.Skip(WindowSaveResult.ReadOnly) })
            assertEquals(WindowSaveResult.Saved, coordinator.saveOnClose { SaveRequest.Write(final) })
            assertEquals(WindowSaveResult.NothingToSave, coordinator.save { error("must not capture after close") })
            assertEquals(listOf(final), store.saved)
        }

    @Test
    fun `explicit save failure returns failed and reports once`() =
        runTest {
            val errors = mutableListOf<WindowPersistenceError>()
            val store = FakeStore(beforeFlush = { error("flush") })
            val dispatcher = StandardTestDispatcher(testScheduler)
            val coordinator = SaveCoordinator(backgroundScope, store, dispatcher, dispatcher, errors::add)
            assertEquals(WindowSaveResult.Failed, coordinator.save { SaveRequest.Write(final) })
            assertEquals(WindowPersistenceOperation.Flush, errors.single().operation)
        }

    private class FakeStore(
        val beforeSave: suspend (WindowSnapshot) -> Unit = {},
        val beforeFlush: suspend () -> Unit = {},
    ) : WindowStateStore {
        val saved = mutableListOf<WindowSnapshot>()
        var flushes = 0

        override suspend fun load(): WindowSnapshot? = null

        override suspend fun save(snapshot: WindowSnapshot) {
            beforeSave(snapshot)
            saved += snapshot
        }

        override suspend fun flush() {
            beforeFlush()
            flushes++
        }

        override suspend fun clear() = Unit
    }
}
