package com.epubreader.app.data.repo

import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.Result
import com.epubreader.app.data.local.dao.BookDao
import com.epubreader.app.data.local.entity.BookEntity
import com.epubreader.app.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepositoryImpl @Inject constructor(
    private val bookDao: BookDao,
    private val dispatchers: DispatchersProvider
) : BookRepository {
    override fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeActive()

    override fun observeBook(uuid: String): Flow<BookEntity?> = bookDao.observeById(uuid)

    override suspend fun getBook(uuid: String): Result<BookEntity?> = withContext(dispatchers.io) {
        Result.runCatchingAsync { bookDao.getById(uuid) }
    }

    override suspend fun addBook(book: BookEntity): Result<Unit> = withContext(dispatchers.io) {
        Result.runCatchingAsync { bookDao.upsert(book) }
    }

    override suspend fun softDeleteBook(uuid: String): Result<Unit> = withContext(dispatchers.io) {
        Result.runCatchingAsync { bookDao.softDelete(uuid, System.currentTimeMillis()) }
    }
}
