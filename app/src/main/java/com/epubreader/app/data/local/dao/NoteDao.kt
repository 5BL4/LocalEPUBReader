package com.epubreader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.epubreader.app.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Upsert
    suspend fun upsert(entity: NoteEntity)

    @Query("UPDATE notes SET isDeleted = 1, updatedAt = :now WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, now: Long)

    @Query("SELECT * FROM notes WHERE isDeleted = 0")
    fun observeActive(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE uuid = :uuid AND isDeleted = 0")
    fun observeById(uuid: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE uuid = :uuid AND isDeleted = 0")
    suspend fun getById(uuid: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE isDeleted = 0")
    suspend fun getAllActive(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE syncedAt IS NULL OR syncedAt < updatedAt")
    suspend fun getDirty(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE updatedAt > :since AND isDeleted = 0")
    suspend fun getUpdatedSince(since: Long): List<NoteEntity>

    @Query("UPDATE notes SET syncedAt = :ts WHERE uuid IN (:uuids)")
    suspend fun markSynced(uuids: List<String>, ts: Long)

    @Query("SELECT * FROM notes WHERE bookUuid = :bookUuid AND isDeleted = 0")
    fun observeByBook(bookUuid: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE bookUuid = :bookUuid AND isDeleted = 0")
    suspend fun getByBook(bookUuid: String): List<NoteEntity>
}
