package com.epubreader.app.data.sync

import com.epubreader.app.data.local.dao.HighlightDao
import com.epubreader.app.data.local.entity.HighlightEntity

class HighlightDaoSyncAdapter(
    private val dao: HighlightDao
) : SyncableDaoOps<HighlightEntity> {
    override suspend fun getById(uuid: String) = dao.getById(uuid)
    override suspend fun upsert(entity: HighlightEntity) = dao.upsert(entity)
    override suspend fun getDirty() = dao.getDirty()
    override suspend fun markSynced(uuids: List<String>, syncedAt: Long, fenceTs: Long) =
        dao.markSynced(uuids, syncedAt, fenceTs)
}
