package com.epubreader.app.core.sync

import com.epubreader.app.core.SyncCursor

/**
 * Persists sync cursors per type for incremental sync.
 * Council RISK-MG1: interface designed now, Noop impl for Phase 7 (NoopRemoteDataSource
 * returns null cursors). DataStore impl added in Phase 8 when real backend produces non-null cursors.
 */
interface SyncCursorStore {
    fun getCursor(type: SyncType): SyncCursor?
    suspend fun saveCursor(type: SyncType, cursor: SyncCursor?)
}

/** No-op implementation — no cursor persistence (Phase 7, no real backend). */
class NoopSyncCursorStore : SyncCursorStore {
    override fun getCursor(type: SyncType): SyncCursor? = null
    override suspend fun saveCursor(type: SyncType, cursor: SyncCursor?) { /* no-op */ }
}
