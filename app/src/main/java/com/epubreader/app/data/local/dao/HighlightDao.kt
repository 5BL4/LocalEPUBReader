package com.epubreader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.epubreader.app.data.local.entity.HighlightEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {
    @Upsert
    suspend fun upsert(entity: HighlightEntity)

    @Query("UPDATE highlights SET isDeleted = 1, updatedAt = :now WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, now: Long)

    @Query("SELECT * FROM highlights WHERE isDeleted = 0")
    fun observeActive(): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE uuid = :uuid AND isDeleted = 0")
    fun observeById(uuid: String): Flow<HighlightEntity?>

    @Query("SELECT * FROM highlights WHERE uuid = :uuid AND isDeleted = 0")
    suspend fun getById(uuid: String): HighlightEntity?

    @Query("SELECT * FROM highlights WHERE isDeleted = 0")
    suspend fun getAllActive(): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE syncedAt IS NULL OR syncedAt < updatedAt")
    suspend fun getDirty(): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE updatedAt > :since AND isDeleted = 0")
    suspend fun getUpdatedSince(since: Long): List<HighlightEntity>

    @Query("UPDATE highlights SET syncedAt = :ts WHERE uuid IN (:uuids)")
    suspend fun markSynced(uuids: List<String>, ts: Long)

    @Query("SELECT * FROM highlights WHERE bookUuid = :bookUuid AND isDeleted = 0")
    fun observeByBook(bookUuid: String): Flow<List<HighlightEntity>>
}
