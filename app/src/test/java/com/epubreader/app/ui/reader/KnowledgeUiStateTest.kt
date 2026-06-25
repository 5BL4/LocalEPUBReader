package com.epubreader.app.ui.reader

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for Phase 5 UI state classes: KnowledgeState, KnowledgeItem,
 * ExportState, NoteEditorState.
 */
class KnowledgeUiStateTest {

    // -- KnowledgeState --

    @Test
    fun `KnowledgeState default values are empty`() {
        val state = KnowledgeState()
        assertTrue(state.items.isEmpty())
        assertEquals(0, state.highlightCount)
        assertEquals(0, state.noteCount)
        assertEquals(0, state.bookmarkCount)
    }

    @Test
    fun `KnowledgeState with items and counts`() {
        val items = listOf(
            KnowledgeItem(uuid = "u1", type = KnowledgeItemType.HIGHLIGHT, text = "text1", createdAt = 100L),
            KnowledgeItem(uuid = "u2", type = KnowledgeItemType.NOTE, text = "note1", createdAt = 200L),
            KnowledgeItem(uuid = "u3", type = KnowledgeItemType.BOOKMARK, text = "bookmark1", createdAt = 300L)
        ).toPersistentList()
        val state = KnowledgeState(
            items = items,
            highlightCount = 1,
            noteCount = 1,
            bookmarkCount = 1
        )
        assertEquals(3, state.items.size)
        assertEquals(1, state.highlightCount)
        assertEquals(1, state.noteCount)
        assertEquals(1, state.bookmarkCount)
    }

    // -- KnowledgeItem --

    @Test
    fun `KnowledgeItem highlight with all fields`() {
        val item = KnowledgeItem(
            uuid = "uuid-1",
            type = KnowledgeItemType.HIGHLIGHT,
            text = "Highlighted passage",
            noteText = "Associated note",
            chapterTitle = "Chapter 5",
            color = "#FFFF00",
            createdAt = 123456L
        )
        assertEquals("uuid-1", item.uuid)
        assertEquals(KnowledgeItemType.HIGHLIGHT, item.type)
        assertEquals("Highlighted passage", item.text)
        assertEquals("Associated note", item.noteText)
        assertEquals("Chapter 5", item.chapterTitle)
        assertEquals("#FFFF00", item.color)
        assertEquals(123456L, item.createdAt)
    }

    @Test
    fun `KnowledgeItem bookmark with minimal fields`() {
        val item = KnowledgeItem(
            uuid = "uuid-2",
            type = KnowledgeItemType.BOOKMARK,
            text = "My Bookmark"
        )
        assertEquals(KnowledgeItemType.BOOKMARK, item.type)
        assertEquals("My Bookmark", item.text)
        assertNull(item.noteText)
        assertNull(item.chapterTitle)
        assertNull(item.color)
        assertEquals(0L, item.createdAt)
    }

    @Test
    fun `KnowledgeItemType enum has three values`() {
        val types = KnowledgeItemType.values()
        assertEquals(3, types.size)
        assertTrue(types.contains(KnowledgeItemType.HIGHLIGHT))
        assertTrue(types.contains(KnowledgeItemType.NOTE))
        assertTrue(types.contains(KnowledgeItemType.BOOKMARK))
    }

    // -- ExportState --

    @Test
    fun `ExportState Idle is a data object`() {
        val state: ExportState = ExportState.Idle
        assertEquals(ExportState.Idle, state)
    }

    @Test
    fun `ExportState Preparing is a data object`() {
        val state: ExportState = ExportState.Preparing
        assertEquals(ExportState.Preparing, state)
    }

    @Test
    fun `ExportState Ready holds content and filename`() {
        val state = ExportState.Ready(content = "# Markdown content", suggestedFileName = "book-annotations.md")
        assertEquals("# Markdown content", state.content)
        assertEquals("book-annotations.md", state.suggestedFileName)
    }

    @Test
    fun `ExportState Error holds message`() {
        val state = ExportState.Error(message = "Something went wrong")
        assertEquals("Something went wrong", state.message)
    }

    @Test
    fun `ExportState sealed type variants are distinct`() {
        val states: List<ExportState> = listOf(
            ExportState.Idle,
            ExportState.Preparing,
            ExportState.Ready("content", "file.md"),
            ExportState.Error("error")
        )
        assertEquals(4, states.size)
        // Verify type discrimination
        assertTrue(states[0] is ExportState.Idle)
        assertTrue(states[1] is ExportState.Preparing)
        assertTrue(states[2] is ExportState.Ready)
        assertTrue(states[3] is ExportState.Error)
    }

    // -- NoteEditorState --

    @Test
    fun `NoteEditorState Hidden is default`() {
        val state: NoteEditorState = NoteEditorState.Hidden
        assertEquals(NoteEditorState.Hidden, state)
    }

    @Test
    fun `NoteEditorState Editing holds selected text`() {
        val state = NoteEditorState.Editing(selectedText = "Selected passage")
        assertEquals("Selected passage", state.selectedText)
    }

    @Test
    fun `NoteEditorState sealed type variants are distinct`() {
        val hidden: NoteEditorState = NoteEditorState.Hidden
        val editing: NoteEditorState = NoteEditorState.Editing("text")
        assertTrue(hidden is NoteEditorState.Hidden)
        assertTrue(editing is NoteEditorState.Editing)
        assertFalse(hidden is NoteEditorState.Editing)
        assertFalse(editing is NoteEditorState.Hidden)
    }

    // -- ReaderUiState Phase 5 field --

    @Test
    fun `ReaderUiState default has knowledge panel closed`() {
        val state = ReaderUiState()
        assertFalse(state.isKnowledgePanelOpen)
    }

    @Test
    fun `ReaderUiState copy preserves knowledge panel field`() {
        val state = ReaderUiState(isKnowledgePanelOpen = true)
        val updated = state.copy(isSearchPanelOpen = true)
        assertTrue(updated.isKnowledgePanelOpen)
        assertTrue(updated.isSearchPanelOpen)
    }
}
