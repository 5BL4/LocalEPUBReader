package com.epubreader.app.domain.repository

import com.epubreader.app.core.Result
import com.epubreader.app.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow

interface BookRepository {
    fun observeBooks(): Flow<List<BookEntity>>
    fun observeBook(uuid: String): Flow<BookEntity?>
    suspend fun getBook(uuid: String): Result<BookEntity?>
    suspend fun addBook(book: BookEntity): Result<Unit>
    suspend fun softDeleteBook(uuid: String): Result<Unit>
    suspend fun reparseMetadata(uuid: String): Result<BookEntity>
}
