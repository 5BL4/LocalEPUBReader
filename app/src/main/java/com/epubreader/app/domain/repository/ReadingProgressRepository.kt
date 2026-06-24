package com.epubreader.app.domain.repository

import com.epubreader.app.core.Result
import com.epubreader.app.data.local.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

interface ReadingProgressRepository {
    fun observeProgress(bookUuid: String): Flow<ReadingProgressEntity?>
    suspend fun getProgress(uuid: String): Result<ReadingProgressEntity?>
    suspend fun saveProgress(progress: ReadingProgressEntity): Result<Unit>
    suspend fun softDeleteProgress(uuid: String): Result<Unit>
}
