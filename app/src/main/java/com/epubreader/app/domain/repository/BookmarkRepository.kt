package com.epubreader.app.domain.repository

import com.epubreader.app.core.Result
import com.epubreader.app.data.local.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {
    fun observeBookmarks(bookUuid: String): Flow<List<BookmarkEntity>>
    suspend fun getBookmark(uuid: String): Result<BookmarkEntity?>
    suspend fun addBookmark(bookmark: BookmarkEntity): Result<Unit>
    suspend fun softDeleteBookmark(uuid: String): Result<Unit>
    suspend fun getByBook(bookUuid: String): Result<List<BookmarkEntity>>
}
