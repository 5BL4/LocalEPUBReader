package com.epubreader.app.data.sync

import com.epubreader.app.core.sync.SyncRecord
import com.epubreader.app.core.sync.SyncType
import com.epubreader.app.data.local.entity.ReadingProgressEntity

class ReadingProgressSyncHandler(
    mapper: SyncRecordMapper,
    adapter: ReadingProgressDaoSyncAdapter
) : BaseSyncEntityHandler<ReadingProgressEntity>(
    type = SyncType.READING_PROGRESS,
    order = 1,
    daoOps = adapter,
    mapper = mapper
) {
    override fun mapFromRecord(record: SyncRecord): ReadingProgressEntity = mapper.fromReadingProgressRecord(record)
    override fun mapToRecord(entity: ReadingProgressEntity): SyncRecord = mapper.toRecord(entity)
}
