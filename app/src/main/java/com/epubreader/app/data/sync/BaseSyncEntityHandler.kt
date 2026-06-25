package com.epubreader.app.data.sync

import com.epubreader.app.core.Syncable
import com.epubreader.app.core.sync.MergeDecision
import com.epubreader.app.core.sync.SyncRecord
import com.epubreader.app.core.sync.SyncType
import com.epubreader.app.core.sync.lwwMerge

/**
 * Base class reducing boilerplate for entity-level sync handlers.
 * Council RISK-MG2: eliminates 5× duplication via handler abstraction.
 */
abstract class BaseSyncEntityHandler<T : Syncable>(
    override val type: SyncType,
    override val order: Int,
    private val daoOps: SyncableDaoOps<T>,
    protected val mapper: SyncRecordMapper
) : SyncEntityHandler {

    abstract fun mapFromRecord(record: SyncRecord): T
    abstract fun mapToRecord(entity: T): SyncRecord

    @Suppress("UNCHECKED_CAST")
    override fun toRecord(entity: Syncable): SyncRecord = mapToRecord(entity as T)

    override suspend fun getDirty(): List<Syncable> = daoOps.getDirty()

    override suspend fun pullMerge(remote: SyncRecord, nowMs: Long): Boolean {
        val remoteEntity = mapFromRecord(remote)
        val local = daoOps.getById(remote.meta.uuid)
        val decision = lwwMerge(remoteEntity, local, nowMs)
        return when (decision) {
            is MergeDecision.Upsert -> {
                daoOps.upsert(decision.record)
                true
            }
            MergeDecision.Skip -> false
        }
    }

    override suspend fun markSynced(uuids: List<String>, syncedAt: Long, fenceTs: Long) =
        daoOps.markSynced(uuids, syncedAt, fenceTs)
}
