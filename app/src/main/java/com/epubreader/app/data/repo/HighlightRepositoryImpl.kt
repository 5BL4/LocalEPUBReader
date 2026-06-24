package com.epubreader.app.data.repo

import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.Result
import com.epubreader.app.data.local.dao.HighlightDao
import com.epubreader.app.data.local.entity.HighlightEntity
import com.epubreader.app.domain.repository.HighlightRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HighlightRepositoryImpl @Inject constructor(
    private val highlightDao: HighlightDao,
    private val dispatchers: DispatchersProvider
) : HighlightRepository {
    override fun observeHighlights(bookUuid: String): Flow<List<HighlightEntity>> =
        highlightDao.observeByBook(bookUuid)

    override suspend fun getHighlight(uuid: String): Result<HighlightEntity?> = withContext(dispatchers.io) {
        Result.runCatchingAsync { highlightDao.getById(uuid) }
    }

    override suspend fun addHighlight(highlight: HighlightEntity): Result<Unit> = withContext(dispatchers.io) {
        Result.runCatchingAsync { highlightDao.upsert(highlight) }
    }

    override suspend fun softDeleteHighlight(uuid: String): Result<Unit> = withContext(dispatchers.io) {
        Result.runCatchingAsync { highlightDao.softDelete(uuid, System.currentTimeMillis()) }
    }
}
