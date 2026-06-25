package com.epubreader.app.data.remote

import com.epubreader.app.core.PushAck
import com.epubreader.app.core.SyncCursor
import com.epubreader.app.core.SyncPage
import com.epubreader.app.core.sync.SyncRecord
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoopRemoteDataSource @Inject constructor() : RemoteDataSource {
    override suspend fun pullSince(cursor: SyncCursor): SyncPage<SyncRecord> =
        SyncPage(items = emptyList(), nextCursor = null)

    override suspend fun push(dirty: List<SyncRecord>): PushAck {
        val now = System.currentTimeMillis()
        return PushAck(
            ackedUuids = dirty.map { it.meta.uuid },
            serverUpdatedAt = dirty.associate { it.meta.uuid to now }
        )
    }
}
