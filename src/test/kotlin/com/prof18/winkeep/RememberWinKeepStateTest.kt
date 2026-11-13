package com.prof18.winkeep

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RememberWinKeepStateTest {
    @Test
    fun `same identity retains controller and initialization inputs and new composition selects new inputs`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val frameClock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext)
            val runner = backgroundScope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(NoNodes(), recomposer)
            val appId = "remember-${UUID.randomUUID()}"
            val id = mutableStateOf("main")
            val store = mutableStateOf<WindowStateStore>(FakeStore())
            val size = mutableStateOf(DpSize(640.dp, 480.dp))
            val seen = mutableListOf<WinKeepState>()
            val firstStore = store.value as FakeStore
            try {
                composition.setContent {
                    val state = rememberWinKeepState(appId, id.value, size.value, store = store.value)
                    androidx.compose.runtime.SideEffect { seen += state }
                }
                runCurrent()
                val first = seen.last()
                awaitRealIo { first.isReady }
                assertTrue(first.isReady)
                val secondStore = FakeStore()
                store.value = secondStore
                size.value = DpSize(900.dp, 700.dp)
                Snapshot.sendApplyNotifications()
                runCurrent()
                frameClock.sendFrame(1L)
                runCurrent()
                assertSame(first, seen.last())
                assertEquals(DpSize(640.dp, 480.dp), first.windowState.size)
                assertEquals(1, firstStore.loads)
                assertEquals(0, secondStore.loads)
                id.value = "second"
                Snapshot.sendApplyNotifications()
                runCurrent()
                frameClock.sendFrame(2L)
                runCurrent()
                val changed = seen.last()
                assertNotSame(first, changed)
                awaitRealIo { changed.isReady }
                assertEquals(DpSize(900.dp, 700.dp), changed.windowState.size)
                assertEquals(1, secondStore.loads)
                composition.dispose()
                val fresh = Composition(NoNodes(), recomposer)
                try {
                    fresh.setContent {
                        val state = rememberWinKeepState(appId, "second", size.value, store = store.value)
                        androidx.compose.runtime.SideEffect { seen += state }
                    }
                    runCurrent()
                    assertNotSame(changed, seen.last())
                    awaitRealIo { seen.last().isReady }
                    assertEquals(2, secondStore.loads)
                } finally {
                    fresh.dispose()
                }
            } finally {
                composition.dispose()
                recomposer.close()
                runner.cancelAndJoin()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `callback replacement is used when delayed initialization reports an error`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val frameClock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext)
            val runner = backgroundScope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(NoNodes(), recomposer)
            val release = CompletableDeferred<Unit>()
            val store =
                object : WindowStateStore {
                    override suspend fun load(): WindowSnapshot? {
                        release.await()
                        error("load failure")
                    }

                    override suspend fun save(snapshot: WindowSnapshot) = Unit

                    override suspend fun flush() = Unit

                    override suspend fun clear() = Unit
                }
            var oldCalls = 0
            var newCalls = 0
            val callback = mutableStateOf<(WindowPersistenceError) -> Unit>({ oldCalls++ })
            try {
                composition.setContent { rememberWinKeepState("callback-${hashCode()}", "main", store = store, onError = callback.value) }
                runCurrent()
                callback.value = { newCalls++ }
                Snapshot.sendApplyNotifications()
                runCurrent()
                frameClock.sendFrame(1L)
                runCurrent()
                release.complete(Unit)
                runCurrent()
                assertEquals(0, oldCalls)
                awaitRealIo { newCalls >= 1 }
                assertTrue(newCalls >= 1)
            } finally {
                composition.dispose()
                recomposer.close()
                runner.cancelAndJoin()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `minimum size is a captured initialization input that raises defaults`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val frameClock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext)
            val runner = backgroundScope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(NoNodes(), recomposer)
            val minimum = mutableStateOf(DpSize(700.dp, 500.dp))
            val appId = "minimum-${UUID.randomUUID()}"
            val seen = mutableListOf<WinKeepState>()
            try {
                composition.setContent {
                    val state =
                        rememberWinKeepState(
                            appId,
                            "main",
                            defaultSize = DpSize(640.dp, 480.dp),
                            minimumSize = minimum.value,
                            store = FakeStore(),
                        )
                    androidx.compose.runtime.SideEffect { seen += state }
                }
                runCurrent()
                awaitRealIo { seen.last().isReady }
                assertEquals(DpSize(700.dp, 500.dp), seen.last().windowState.size)
                minimum.value = DpSize(900.dp, 800.dp)
                Snapshot.sendApplyNotifications()
                runCurrent()
                frameClock.sendFrame(1L)
                runCurrent()
                assertEquals(1, seen.distinct().size)
                assertEquals(DpSize(700.dp, 500.dp), seen.last().windowState.size)
                kotlin.test.assertFailsWith<IllegalArgumentException> {
                    Composition(NoNodes(), recomposer).setContent {
                        rememberWinKeepState("minimum-invalid", "main", minimumSize = DpSize(0.dp, 10.dp), store = FakeStore())
                    }
                }
            } finally {
                composition.dispose()
                recomposer.close()
                runner.cancelAndJoin()
                Dispatchers.resetMain()
            }
        }

    private suspend fun TestScope.awaitRealIo(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(30_000) {
                while (!condition()) {
                    runCurrent()
                    yield()
                }
            }
        }
    }

    private class FakeStore : WindowStateStore {
        var loads = 0

        override suspend fun load(): WindowSnapshot? {
            loads++
            return null
        }

        override suspend fun save(snapshot: WindowSnapshot) = Unit

        override suspend fun flush() = Unit

        override suspend fun clear() = Unit
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(
            index: Int,
            instance: Unit,
        ) = Unit

        override fun insertBottomUp(
            index: Int,
            instance: Unit,
        ) = Unit

        override fun remove(
            index: Int,
            count: Int,
        ) = Unit

        override fun move(
            from: Int,
            to: Int,
            count: Int,
        ) = Unit

        override fun onClear() = Unit
    }
}
