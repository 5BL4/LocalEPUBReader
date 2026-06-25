package com.epubreader.app.data.sync

import com.epubreader.app.core.Syncable

/** Unified DAO operations for sync. Each entity type has an adapter. */
interface SyncableDaoOps<T : Syncable> {
    suspend fun getById(uuid: String): T?
    suspend fun upsert(entity: T)
    suspend fun getDirty(): List<T>
    suspend fun markSynced(uuids: List<String>, syncedAt: Long, fenceTs: Long)
}
