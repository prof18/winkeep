package com.prof18.winkeep.internal

import com.prof18.winkeep.WindowPersistenceError
import com.prof18.winkeep.WindowPersistenceOperation
import com.prof18.winkeep.WindowSaveResult
import com.prof18.winkeep.WindowSnapshot
import com.prof18.winkeep.WindowStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

/** What an explicit save should do, decided on the UI dispatcher. */
internal sealed interface SaveRequest {
    class Write(
        val snapshot: WindowSnapshot,
    ) : SaveRequest

    class Skip(
        val result: WindowSaveResult,
    ) : SaveRequest
}

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
    private var closeOperation: Deferred<WindowSaveResult>? = null

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
                        if (!closing && offeredSequence == sequence) attempt(WindowPersistenceOperation.Save) { store.save(snapshot) }
                    }
                }
            }
    }

    /**
     * Writes and flushes the captured snapshot through the serial writer without closing.
     * It supersedes any pending debounced write. When a close starts before this write runs,
     * the close owns the newer final snapshot and this call returns the close result.
     */
    suspend fun save(capture: () -> SaveRequest): WindowSaveResult {
        val operation =
            withContext(uiDispatcher) {
                if (closing) return@withContext null
                val snapshot =
                    when (val request = capture()) {
                        is SaveRequest.Skip -> return@withContext CompletableDeferred(request.result)
                        is SaveRequest.Write -> request.snapshot
                    }
                ++sequence
                latest = snapshot
                timer?.cancel()
                timer = null
                scope.async(uiDispatcher) {
                    writerMutex.withLock { if (closing) null else writeAndFlush(snapshot) }
                        ?: requireNotNull(closeOperation).await()
                }
            } ?: return WindowSaveResult.NothingToSave
        return operation.await()
    }

    /** Terminal: freezes the final request, waits for earlier writes, then writes and flushes once. */
    suspend fun saveOnClose(freeze: () -> SaveRequest): WindowSaveResult {
        val operation =
            withContext(uiDispatcher) {
                closeOperation ?: run {
                    closing = true
                    val request = freeze()
                    ++sequence
                    val pendingTimer = timer
                    pendingTimer?.cancel()
                    timer = null
                    scope
                        .async(uiDispatcher) {
                            pendingTimer?.cancelAndJoin()
                            writerMutex.withLock {
                                when (request) {
                                    is SaveRequest.Skip -> request.result
                                    is SaveRequest.Write -> writeAndFlush(request.snapshot)
                                }
                            }
                        }.also { closeOperation = it }
                }
            }
        return operation.await()
    }

    private suspend fun writeAndFlush(snapshot: WindowSnapshot): WindowSaveResult =
        if (attempt(WindowPersistenceOperation.Save) { store.save(snapshot) } &&
            attempt(WindowPersistenceOperation.Flush) { store.flush() }
        ) {
            WindowSaveResult.Saved
        } else {
            WindowSaveResult.Failed
        }

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
