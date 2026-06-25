package com.epubreader.app.data.sync

import com.epubreader.app.data.local.dao.BookmarkDao
import com.epubreader.app.data.local.entity.BookmarkEntity

class BookmarkDaoSyncAdapter(
    private val dao: BookmarkDao
) : SyncableDaoOps<BookmarkEntity> {
    override suspend fun getById(uuid: String) = dao.getById(uuid)
    override suspend fun upsert(entity: BookmarkEntity) = dao.upsert(entity)
    override suspend fun getDirty() = dao.getDirty()
    override suspend fun markSynced(uuids: List<String>, syncedAt: Long, fenceTs: Long) =
        dao.markSynced(uuids, syncedAt, fenceTs)
}
