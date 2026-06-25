package com.epubreader.app.data.sync

import com.epubreader.app.core.sync.SyncRecord
import com.epubreader.app.core.sync.SyncType
import com.epubreader.app.data.local.entity.HighlightEntity

class HighlightSyncHandler(
    mapper: SyncRecordMapper,
    adapter: HighlightDaoSyncAdapter
) : BaseSyncEntityHandler<HighlightEntity>(
    type = SyncType.HIGHLIGHT,
    order = 1,
    daoOps = adapter,
    mapper = mapper
) {
    override fun mapFromRecord(record: SyncRecord): HighlightEntity = mapper.fromHighlightRecord(record)
    override fun mapToRecord(entity: HighlightEntity): SyncRecord = mapper.toRecord(entity)
}
