package com.epubreader.app.data.repo

import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.Result
import com.epubreader.app.data.local.dao.BookmarkDao
import com.epubreader.app.data.local.entity.BookmarkEntity
import com.epubreader.app.domain.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepositoryImpl @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val dispatchers: DispatchersProvider
) : BookmarkRepository {
    override fun observeBookmarks(bookUuid: String): Flow<List<BookmarkEntity>> =
        bookmarkDao.observeByBook(bookUuid)

    override suspend fun getBookmark(uuid: String): Result<BookmarkEntity?> = withContext(dispatchers.io) {
        Result.runCatchingAsync { bookmarkDao.getById(uuid) }
    }

    override suspend fun addBookmark(bookmark: BookmarkEntity): Result<Unit> = withContext(dispatchers.io) {
        Result.runCatchingAsync { bookmarkDao.upsert(bookmark) }
    }

    override suspend fun softDeleteBookmark(uuid: String): Result<Unit> = withContext(dispatchers.io) {
        Result.runCatchingAsync { bookmarkDao.softDelete(uuid, System.currentTimeMillis()) }
    }
}
