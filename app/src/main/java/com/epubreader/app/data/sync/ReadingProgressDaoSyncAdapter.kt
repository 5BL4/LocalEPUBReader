package com.epubreader.app.data.sync

import com.epubreader.app.data.local.dao.ReadingProgressDao
import com.epubreader.app.data.local.entity.ReadingProgressEntity

class ReadingProgressDaoSyncAdapter(
    private val dao: ReadingProgressDao
) : SyncableDaoOps<ReadingProgressEntity> {
    override suspend fun getById(uuid: String) = dao.getById(uuid)
    override suspend fun upsert(entity: ReadingProgressEntity) = dao.upsert(entity)
    override suspend fun getDirty() = dao.getDirty()
    override suspend fun markSynced(uuids: List<String>, syncedAt: Long, fenceTs: Long) =
        dao.markSynced(uuids, syncedAt, fenceTs)
}
