package com.epubreader.app.data.sync

import com.epubreader.app.core.sync.SyncRecord
import com.epubreader.app.core.sync.SyncType
import com.epubreader.app.data.local.entity.NoteEntity

class NoteSyncHandler(
    mapper: SyncRecordMapper,
    adapter: NoteDaoSyncAdapter
) : BaseSyncEntityHandler<NoteEntity>(
    type = SyncType.NOTE,
    order = 2,
    daoOps = adapter,
    mapper = mapper
) {
    override fun mapFromRecord(record: SyncRecord): NoteEntity = mapper.fromNoteRecord(record)
    override fun mapToRecord(entity: NoteEntity): SyncRecord = mapper.toRecord(entity)
}
