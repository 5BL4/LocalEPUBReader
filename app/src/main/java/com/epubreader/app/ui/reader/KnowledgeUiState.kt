package com.epubreader.app.ui.reader

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * UI state for the Knowledge panel (Phase 5).
 * Shows highlights, notes, and bookmarks for the current book.
 */

enum class KnowledgeItemType { HIGHLIGHT, NOTE, BOOKMARK }

@Immutable
data class KnowledgeItem(
    val uuid: String,
    val type: KnowledgeItemType,
    val text: String,
    val noteText: String? = null,
    val chapterTitle: String? = null,
    val color: String? = null,
    val createdAt: Long = 0L,
)

@Immutable
data class KnowledgeState(
    val items: PersistentList<KnowledgeItem> = persistentListOf(),
    val highlightCount: Int = 0,
    val noteCount: Int = 0,
    val bookmarkCount: Int = 0,
)

sealed interface ExportState {
    data object Idle : ExportState
    data object Preparing : ExportState
    data class Ready(val content: String, val suggestedFileName: String) : ExportState
    data class Error(val message: String) : ExportState
}

sealed interface NoteEditorState {
    data object Hidden : NoteEditorState
    data class Editing(val selectedText: String) : NoteEditorState
}
