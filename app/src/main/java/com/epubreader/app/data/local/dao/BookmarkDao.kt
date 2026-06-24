package com.epubreader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.epubreader.app.data.local.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Upsert
    suspend fun upsert(entity: BookmarkEntity)

    @Query("UPDATE bookmarks SET isDeleted = 1, updatedAt = :now WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, now: Long)

    @Query("SELECT * FROM bookmarks WHERE isDeleted = 0")
    fun observeActive(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE uuid = :uuid AND isDeleted = 0")
    fun observeById(uuid: String): Flow<BookmarkEntity?>

    @Query("SELECT * FROM bookmarks WHERE uuid = :uuid AND isDeleted = 0")
    suspend fun getById(uuid: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE isDeleted = 0")
    suspend fun getAllActive(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE syncedAt IS NULL OR syncedAt < updatedAt")
    suspend fun getDirty(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE updatedAt > :since AND isDeleted = 0")
    suspend fun getUpdatedSince(since: Long): List<BookmarkEntity>

    @Query("UPDATE bookmarks SET syncedAt = :ts WHERE uuid IN (:uuids)")
    suspend fun markSynced(uuids: List<String>, ts: Long)

    @Query("SELECT * FROM bookmarks WHERE bookUuid = :bookUuid AND isDeleted = 0")
    fun observeByBook(bookUuid: String): Flow<List<BookmarkEntity>>
}
