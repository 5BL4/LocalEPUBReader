package com.epubreader.app.ui.reader

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class ReaderUiState(
    val isLoading: Boolean = true,
    val bookTitle: String? = null,
    val navigatorFactoryReady: Boolean = false,
    val error: String? = null,
    val toc: PersistentList<TocItem> = persistentListOf(),
    val isTocDrawerOpen: Boolean = false,
    val isSearchPanelOpen: Boolean = false,
    val isAutoScrollActive: Boolean = false,
    val isKnowledgePanelOpen: Boolean = false
)
