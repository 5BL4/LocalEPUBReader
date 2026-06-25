package com.epubreader.app.ui.bookshelf

import androidx.compose.runtime.Immutable
import com.epubreader.app.data.local.entity.BookEntity
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class BookshelfUiState(
    val isLoading: Boolean = true,
    val books: PersistentList<BookEntity> = persistentListOf(),
    val error: String? = null
)
