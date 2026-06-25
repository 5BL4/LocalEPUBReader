package com.epubreader.app.data.sync

import com.epubreader.app.core.sync.SyncRecord
import com.epubreader.app.core.sync.SyncType
import com.epubreader.app.data.local.entity.BookmarkEntity

class BookmarkSyncHandler(
    mapper: SyncRecordMapper,
    adapter: BookmarkDaoSyncAdapter
) : BaseSyncEntityHandler<BookmarkEntity>(
    type = SyncType.BOOKMARK,
    order = 1,
    daoOps = adapter,
    mapper = mapper
) {
    override fun mapFromRecord(record: SyncRecord): BookmarkEntity = mapper.fromBookmarkRecord(record)
    override fun mapToRecord(entity: BookmarkEntity): SyncRecord = mapper.toRecord(entity)
}
