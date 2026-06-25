package com.epubreader.app.data.sync

import com.epubreader.app.data.local.dao.BookDao
import com.epubreader.app.data.local.entity.BookEntity

class BookDaoSyncAdapter(
    private val dao: BookDao
) : SyncableDaoOps<BookEntity> {
    override suspend fun getById(uuid: String) = dao.getById(uuid)
    override suspend fun upsert(entity: BookEntity) = dao.upsert(entity)
    override suspend fun getDirty() = dao.getDirty()
    override suspend fun markSynced(uuids: List<String>, syncedAt: Long, fenceTs: Long) =
        dao.markSynced(uuids, syncedAt, fenceTs)
}
