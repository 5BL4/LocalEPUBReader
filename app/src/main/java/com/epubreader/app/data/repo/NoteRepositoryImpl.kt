package com.epubreader.app.data.repo

import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.Result
import com.epubreader.app.data.local.dao.NoteDao
import com.epubreader.app.data.local.entity.NoteEntity
import com.epubreader.app.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepositoryImpl @Inject constructor(
    private val noteDao: NoteDao,
    private val dispatchers: DispatchersProvider
) : NoteRepository {
    override fun observeNotes(bookUuid: String): Flow<List<NoteEntity>> =
        noteDao.observeByBook(bookUuid)

    override suspend fun getNote(uuid: String): Result<NoteEntity?> = withContext(dispatchers.io) {
        Result.runCatchingAsync { noteDao.getById(uuid) }
    }

    override suspend fun addNote(note: NoteEntity): Result<Unit> = withContext(dispatchers.io) {
        Result.runCatchingAsync { noteDao.upsert(note) }
    }

    override suspend fun softDeleteNote(uuid: String): Result<Unit> = withContext(dispatchers.io) {
        Result.runCatchingAsync { noteDao.softDelete(uuid, System.currentTimeMillis()) }
    }

    override suspend fun getByBook(bookUuid: String): Result<List<NoteEntity>> = withContext(dispatchers.io) {
        Result.runCatchingAsync { noteDao.getByBook(bookUuid) }
    }
}
