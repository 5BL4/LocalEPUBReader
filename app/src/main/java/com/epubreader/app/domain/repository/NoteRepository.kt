package com.epubreader.app.domain.repository

import com.epubreader.app.core.Result
import com.epubreader.app.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

interface NoteRepository {
    fun observeNotes(bookUuid: String): Flow<List<NoteEntity>>
    suspend fun getNote(uuid: String): Result<NoteEntity?>
    suspend fun addNote(note: NoteEntity): Result<Unit>
    suspend fun softDeleteNote(uuid: String): Result<Unit>
    suspend fun getByBook(bookUuid: String): Result<List<NoteEntity>>
}
