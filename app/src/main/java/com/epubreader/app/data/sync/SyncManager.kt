package com.epubreader.app.data.sync

import com.epubreader.app.core.AppError
import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.ErrorChannel
import com.epubreader.app.core.Result
import com.epubreader.app.core.SyncCursor
import com.epubreader.app.core.sync.SyncCursorStore
import com.epubreader.app.core.sync.SyncError
import com.epubreader.app.core.sync.SyncResult
import com.epubreader.app.core.sync.SyncState
import com.epubreader.app.core.sync.SyncType
import com.epubreader.app.data.remote.RemoteDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates Offline-First sync cycles.
 *
 * Council RISK-C2: Mutex-guarded to prevent concurrent sync.
 * Council RISK-L2: Uses ApplicationScope to survive Activity destruction.
 * Council RISK-ER1: Deferred markSynced — all marks happen at END of cycle.
 * Oracle S7: FK order enforced via handler ordering.
 */
@Singleton
class SyncManager @Inject constructor(
    private val remoteDataSource: RemoteDataSource,
    private val handlers: List<@JvmSuppressWildcards SyncEntityHandler>,
    private val cursorStore: SyncCursorStore,
    private val dispatchers: DispatchersProvider,
    private val errorChannel: ErrorChannel
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.sync)

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * Triggers a sync cycle. Mutex-guarded, batch-paginated, fenced markSynced.
     *
     * Uses [Result.runCatchingAsync] to capture errors without swallowing
     * CancellationException (NEVER #26).
     */
    suspend fun sync(): Result<SyncResult> = mutex.withLock {
        withContext(dispatchers.sync) {
            Result.runCatchingAsync {
                _state.value = SyncState.Syncing
                val nowMs = System.currentTimeMillis()
                var pulled = 0
                var pushed = 0
                val errors = mutableListOf<SyncError>()

                // Sort handlers by FK order (Book=0 first, Note=2 last)
                val orderedHandlers = handlers.sortedBy { it.order }

                // Council RISK-ER1: collect markSynced calls to defer to end
                data class PendingMark(val handler: SyncEntityHandler, val uuids: List<String>, val syncedAt: Long)
                val pendingMarks = mutableListOf<PendingMark>()

                for (handler in orderedHandlers) {
                    try {
                        // ── PULL phase ──
                        val cursor = cursorStore.getCursor(handler.type)
                        var pullCursor = cursor
                        do {
                            val page = remoteDataSource.pullSince(pullCursor ?: SyncCursor(0))
                            for (record in page.items) {
                                if (handler.pullMerge(record, nowMs)) pulled++
                            }
                            pullCursor = page.nextCursor
                            cursorStore.saveCursor(handler.type, page.nextCursor)
                        } while (pullCursor != null)

                        // ── PUSH phase (batched) ──
                        val dirty = handler.getDirty()
                        for (batch in dirty.chunked(BATCH_SIZE)) {
                            val records = batch.map { handler.toRecord(it) }
                            val ack = remoteDataSource.push(records)
                            pushed += ack.ackedUuids.size
                            // Determine syncedAt: use server timestamp if available, else nowMs
                            val syncedAt = if (ack.serverUpdatedAt.isNotEmpty()) {
                                ack.serverUpdatedAt.values.maxOrNull() ?: nowMs
                            } else {
                                nowMs
                            }
                            pendingMarks.add(PendingMark(handler, ack.ackedUuids, syncedAt))
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e  // re-throw, don't swallow (NEVER #26)
                    } catch (e: Exception) {
                        errors.add(SyncError(handler.type, e))
                        errorChannel.tryEmit(AppError("Sync failed for ${handler.type}: ${e.message}", e))
                    }
                }

                // ── DEFERRED markSynced: only if no errors (Council RISK-ER1) ──
                // MF1: wrap in try-catch so a DB failure here doesn't leave SyncState
                // stuck at Syncing or silently swallow the error.
                if (errors.isEmpty()) {
                    try {
                        for (mark in pendingMarks) {
                            mark.handler.markSynced(mark.uuids, mark.syncedAt, nowMs)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e  // re-throw, don't swallow (NEVER #26)
                    } catch (e: Exception) {
                        errors.add(SyncError(SyncType.BOOK, e))
                        errorChannel.tryEmit(AppError("Deferred markSynced failed: ${e.message}", e))
                    }
                }

                val result = SyncResult(pulled = pulled, pushed = pushed, errors = errors)
                _state.value = if (errors.isEmpty()) SyncState.Success(result)
                    else SyncState.Error(result)  // S7: carry structured errors, not collapsed string
                result
            }
        }
    }

    /**
     * Launches sync on ApplicationScope (fire-and-forget for lifecycle trigger).
     * S8: skips if a sync is already in progress (mutex is locked) — fire-and-forget
     * doesn't need to queue duplicate syncs.
     */
    fun launchSync(): Job? =
        if (mutex.isLocked) null else scope.launch { sync() }

    companion object {
        private const val BATCH_SIZE = 200
    }
}
