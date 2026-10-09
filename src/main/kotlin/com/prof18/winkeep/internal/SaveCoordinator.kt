package com.prof18.winkeep.internal

import com.prof18.winkeep.WindowPersistenceError
import com.prof18.winkeep.WindowPersistenceOperation
import com.prof18.winkeep.WindowSnapshot
import com.prof18.winkeep.WindowStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.logging.Level
import java.util.logging.Logger

/** UI-confined observations; writer jobs belong to the controller, never to a debounce timer. */
internal class SaveCoordinator(
    private val scope: CoroutineScope,
    private val store: WindowStateStore,
    private val uiDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    private val onError: (WindowPersistenceError) -> Unit = {},
) {
    private val writerMutex = Mutex()
    private var sequence = 0L
    private var latest: WindowSnapshot? = null
    private var timer: Job? = null
    private var closing = false
    private var closeOperation: Deferred<Boolean>? = null

    fun offer(snapshot: WindowSnapshot) {
        if (closing || snapshot == latest) return
        latest = snapshot
        val offeredSequence = ++sequence
        timer?.cancel()
        timer =
            scope.launch(uiDispatcher) {
                delay(250)
                // This sibling remains active if another observation cancels the timer.
                scope.launch(uiDispatcher) {
                    writerMutex.withLock {
                        if (!closing && offeredSequence == sequence) save(snapshot)
                    }
                }
            }
    }

    suspend fun saveOnClose(freeze: () -> WindowSnapshot?): Boolean {
        val operation =
            withContext(uiDispatcher) {
                closeOperation ?: run {
                    closing = true
                    val snapshot = freeze()
                    ++sequence
                    val pendingTimer = timer
                    pendingTimer?.cancel()
                    timer = null
                    scope
                        .async(uiDispatcher) {
                            pendingTimer?.cancelAndJoin()
                            writerMutex.withLock {
                                if (snapshot == null) return@withLock false
                                if (!save(snapshot)) return@withLock false
                                attempt(WindowPersistenceOperation.Flush) { store.flush() }
                            }
                        }.also { closeOperation = it }
                }
            }
        return operation.await()
    }

    private suspend fun save(snapshot: WindowSnapshot): Boolean = attempt(WindowPersistenceOperation.Save) { store.save(snapshot) }

    private suspend fun attempt(
        operation: WindowPersistenceOperation,
        action: suspend () -> Unit,
    ): Boolean {
        try {
            withContext(ioDispatcher) { action() }
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportPersistenceError(uiDispatcher, onError, WindowPersistenceError(operation, error))
            return false
        }
    }
}

internal suspend fun reportPersistenceError(
    uiDispatcher: CoroutineDispatcher,
    onError: (WindowPersistenceError) -> Unit,
    error: WindowPersistenceError,
) {
    withContext(uiDispatcher) {
        try {
            onError(error)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (callbackFailure: Exception) {
            Logger.getLogger("com.prof18.winkeep").log(Level.WARNING, "Window persistence error callback failed", callbackFailure)
        }
    }
}
