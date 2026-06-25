package com.epubreader.app.data.sync

import com.epubreader.app.data.local.dao.NoteDao
import com.epubreader.app.data.local.entity.NoteEntity

class NoteDaoSyncAdapter(
    private val dao: NoteDao
) : SyncableDaoOps<NoteEntity> {
    override suspend fun getById(uuid: String) = dao.getById(uuid)
    override suspend fun upsert(entity: NoteEntity) = dao.upsert(entity)
    override suspend fun getDirty() = dao.getDirty()
    override suspend fun markSynced(uuids: List<String>, syncedAt: Long, fenceTs: Long) =
        dao.markSynced(uuids, syncedAt, fenceTs)
}
