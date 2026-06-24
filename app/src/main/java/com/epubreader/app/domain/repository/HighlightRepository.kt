package com.epubreader.app.domain.repository

import com.epubreader.app.core.Result
import com.epubreader.app.data.local.entity.HighlightEntity
import kotlinx.coroutines.flow.Flow

interface HighlightRepository {
    fun observeHighlights(bookUuid: String): Flow<List<HighlightEntity>>
    suspend fun getHighlight(uuid: String): Result<HighlightEntity?>
    suspend fun addHighlight(highlight: HighlightEntity): Result<Unit>
    suspend fun softDeleteHighlight(uuid: String): Result<Unit>
}
