package com.epubreader.app.data.remote

import com.epubreader.app.core.PushAck
import com.epubreader.app.core.Syncable
import com.epubreader.app.core.SyncCursor
import com.epubreader.app.core.SyncPage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoopRemoteDataSource @Inject constructor() : RemoteDataSource {
    override suspend fun pullSince(cursor: SyncCursor): SyncPage<Syncable> =
        SyncPage(items = emptyList(), nextCursor = null)

    override suspend fun push(dirty: List<Syncable>): PushAck {
        val now = System.currentTimeMillis()
        return PushAck(
            ackedUuids = dirty.map { it.uuid },
            serverUpdatedAt = dirty.associate { it.uuid to now }
        )
    }
}
