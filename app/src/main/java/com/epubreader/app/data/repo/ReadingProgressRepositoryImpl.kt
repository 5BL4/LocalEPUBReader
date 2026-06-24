package com.epubreader.app.data.repo

import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.Result
import com.epubreader.app.data.local.dao.ReadingProgressDao
import com.epubreader.app.data.local.entity.ReadingProgressEntity
import com.epubreader.app.domain.repository.ReadingProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadingProgressRepositoryImpl @Inject constructor(
    private val readingProgressDao: ReadingProgressDao,
    private val dispatchers: DispatchersProvider
) : ReadingProgressRepository {
    override fun observeProgress(bookUuid: String): Flow<ReadingProgressEntity?> =
        readingProgressDao.observeByBookSingle(bookUuid)

    override suspend fun getProgress(uuid: String): Result<ReadingProgressEntity?> = withContext(dispatchers.io) {
        Result.runCatchingAsync { readingProgressDao.getById(uuid) }
    }

    override suspend fun saveProgress(progress: ReadingProgressEntity): Result<Unit> = withContext(dispatchers.io) {
        Result.runCatchingAsync { readingProgressDao.upsert(progress) }
    }

    override suspend fun softDeleteProgress(uuid: String): Result<Unit> = withContext(dispatchers.io) {
        Result.runCatchingAsync { readingProgressDao.softDelete(uuid, System.currentTimeMillis()) }
    }
}
