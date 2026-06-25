package com.epubreader.app.data.sync

import com.epubreader.app.core.sync.SyncRecord
import com.epubreader.app.core.sync.SyncType
import com.epubreader.app.data.local.entity.BookEntity

class BookSyncHandler(
    mapper: SyncRecordMapper,
    adapter: BookDaoSyncAdapter
) : BaseSyncEntityHandler<BookEntity>(
    type = SyncType.BOOK,
    order = 0,
    daoOps = adapter,
    mapper = mapper
) {
    override fun mapFromRecord(record: SyncRecord): BookEntity = mapper.fromBookRecord(record)
    override fun mapToRecord(entity: BookEntity): SyncRecord = mapper.toRecord(entity)
}
