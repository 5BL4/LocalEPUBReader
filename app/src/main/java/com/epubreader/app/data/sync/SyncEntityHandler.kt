package com.epubreader.app.data.sync

import com.epubreader.app.core.Syncable
import com.epubreader.app.core.sync.MergeDecision
import com.epubreader.app.core.sync.SyncRecord
import com.epubreader.app.core.sync.SyncType
import com.epubreader.app.core.sync.lwwMerge

/** Handles sync for one entity type. Encapsulates DAO ops + mapping + FK order. */
interface SyncEntityHandler {
    val type: SyncType
    val order: Int  // FK order: 0=Book, 1=Progress/Bookmark/Highlight, 2=Note

    /** Get locally dirty records. */
    suspend fun getDirty(): List<Syncable>

    /** Map entity to sync record for push. */
    fun toRecord(entity: Syncable): SyncRecord

    /** Pull-merge a remote record: LWW merge with local, upsert if needed. Returns true if upserted. */
    suspend fun pullMerge(remote: SyncRecord, nowMs: Long): Boolean

    /** Mark records as synced (fenced). */
    suspend fun markSynced(uuids: List<String>, syncedAt: Long, fenceTs: Long)
}
