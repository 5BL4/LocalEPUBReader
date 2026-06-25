package com.epubreader.app.ui.reader

import androidx.compose.runtime.Immutable

@Immutable
data class ReaderUiState(
    val isLoading: Boolean = true,
    val bookTitle: String? = null,
    val navigatorFactoryReady: Boolean = false,
    val error: String? = null
)
