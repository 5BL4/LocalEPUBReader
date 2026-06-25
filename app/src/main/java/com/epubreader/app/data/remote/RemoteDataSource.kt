package com.epubreader.app.data.remote

import com.epubreader.app.core.PushAck
import com.epubreader.app.core.SyncCursor
import com.epubreader.app.core.SyncPage
import com.epubreader.app.core.sync.SyncRecord

/**
 * Offline-First sync transport. Local data source is always primary; this remote
 * transport pulls server changes since [cursor] and pushes locally-dirty records.
 *
 * Last-Write-Wins merge (comparing updatedAt, isDeleted=1 wins) is a LOCAL decision
 * orchestrated by SyncManager in Phase 7 — NOT this transport's responsibility.
 */
interface RemoteDataSource {
    suspend fun pullSince(cursor: SyncCursor): SyncPage<SyncRecord>
    suspend fun push(dirty: List<SyncRecord>): PushAck
}
