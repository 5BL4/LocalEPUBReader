package com.epubreader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.epubreader.app.data.local.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingProgressDao {
    @Upsert
    suspend fun upsert(entity: ReadingProgressEntity)

    @Query("UPDATE reading_progress SET isDeleted = 1, updatedAt = :now WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, now: Long)

    @Query("SELECT * FROM reading_progress WHERE isDeleted = 0")
    fun observeActive(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress WHERE uuid = :uuid AND isDeleted = 0")
    fun observeById(uuid: String): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE uuid = :uuid AND isDeleted = 0")
    suspend fun getById(uuid: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress WHERE isDeleted = 0")
    suspend fun getAllActive(): List<ReadingProgressEntity>

    @Query("SELECT * FROM reading_progress WHERE syncedAt IS NULL OR syncedAt < updatedAt")
    suspend fun getDirty(): List<ReadingProgressEntity>

    @Query("SELECT * FROM reading_progress WHERE updatedAt > :since AND isDeleted = 0")
    suspend fun getUpdatedSince(since: Long): List<ReadingProgressEntity>

    @Query("UPDATE reading_progress SET syncedAt = :syncedAt WHERE uuid IN (:uuids) AND updatedAt <= :fenceTs")
    suspend fun markSynced(uuids: List<String>, syncedAt: Long, fenceTs: Long)

    @Query("SELECT * FROM reading_progress WHERE bookUuid = :bookUuid AND isDeleted = 0")
    fun observeByBook(bookUuid: String): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress WHERE bookUuid = :bookUuid AND isDeleted = 0")
    fun observeByBookSingle(bookUuid: String): Flow<ReadingProgressEntity?>
}
