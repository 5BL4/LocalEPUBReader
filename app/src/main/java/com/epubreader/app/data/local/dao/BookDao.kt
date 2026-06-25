package com.epubreader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.epubreader.app.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Upsert
    suspend fun upsert(entity: BookEntity)

    @Query("UPDATE books SET isDeleted = 1, updatedAt = :now WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, now: Long)

    @Query("SELECT * FROM books WHERE isDeleted = 0")
    fun observeActive(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE uuid = :uuid AND isDeleted = 0")
    fun observeById(uuid: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE uuid = :uuid AND isDeleted = 0")
    suspend fun getById(uuid: String): BookEntity?

    @Query("SELECT * FROM books WHERE isDeleted = 0")
    suspend fun getAllActive(): List<BookEntity>

    @Query("SELECT * FROM books WHERE syncedAt IS NULL OR syncedAt < updatedAt")
    suspend fun getDirty(): List<BookEntity>

    @Query("SELECT * FROM books WHERE updatedAt > :since AND isDeleted = 0")
    suspend fun getUpdatedSince(since: Long): List<BookEntity>

    @Query("UPDATE books SET syncedAt = :syncedAt WHERE uuid IN (:uuids) AND updatedAt <= :fenceTs")
    suspend fun markSynced(uuids: List<String>, syncedAt: Long, fenceTs: Long)
}
