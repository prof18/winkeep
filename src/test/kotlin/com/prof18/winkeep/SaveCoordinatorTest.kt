package com.prof18.winkeep

import com.prof18.winkeep.internal.SaveCoordinator
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
    private val first = WindowSnapshot(normalBounds = WindowBounds(20.0, 30.0, 600.0, 400.0))
    private val final = first.copy(normalBounds = first.normalBounds.copy(width = 700.0))

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
                        final
                    }
                }
            runCurrent()
            coordinator.offer(first)
            val again = async { coordinator.saveOnClose { error("must not capture twice") } }
            assertTrue(close.await())
            assertTrue(again.await())
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
            val close = async { coordinator.saveOnClose { final } }
            runCurrent()
            assertFalse(close.isCompleted)
            release.complete(Unit)
            assertTrue(close.await())
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
    fun `save or flush failure returns false and throwing callback cannot break cleanup`() =
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
                assertFalse(coordinator.saveOnClose { final })
                assertFalse(coordinator.saveOnClose { error("second capture") })
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
            val close = async { coordinator.saveOnClose { final } }
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
    }
}
