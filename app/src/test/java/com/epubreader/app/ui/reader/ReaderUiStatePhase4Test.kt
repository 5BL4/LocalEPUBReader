package com.epubreader.app.ui.reader

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for Phase 4 data classes and state models.
 *
 * The ViewModel itself cannot be constructed in pure JVM tests (toRoute()
 * requires android.os.Bundle), so these tests validate the data layer that
 * the ViewModel operates on.
 */
class ReaderUiStatePhase4Test {

    @Test
    fun `ReaderUiState default values include Phase 4 fields`() {
        val state = ReaderUiState()
        assertTrue(state.isLoading)
        assertNull(state.bookTitle)
        assertFalse(state.navigatorFactoryReady)
        assertNull(state.error)
        assertTrue(state.toc.isEmpty())
        assertFalse(state.isTocDrawerOpen)
        assertFalse(state.isSearchPanelOpen)
        assertFalse(state.isToolbarVisible)
        assertFalse(state.isSettingsPanelOpen)
    }

    @Test
    fun `ReaderUiState copy preserves all Phase 4 fields`() {
        val toc = persistentListOf(
            TocItem("Chapter 1", 0),
            TocItem("Section 1.1", 1)
        )
        val state = ReaderUiState(
            isLoading = false,
            bookTitle = "Test Book",
            navigatorFactoryReady = true,
            toc = toc,
            isTocDrawerOpen = true,
            isSearchPanelOpen = false,
            isToolbarVisible = true,
            isSettingsPanelOpen = true
        )
        val updated = state.copy(error = "Error")
        assertEquals(toc, updated.toc)
        assertTrue(updated.isTocDrawerOpen)
        assertFalse(updated.isSearchPanelOpen)
        assertTrue(updated.isToolbarVisible)
        assertTrue(updated.isSettingsPanelOpen)
        assertEquals("Error", updated.error)
    }

    @Test
    fun `TocItem holds title and level`() {
        val item = TocItem(title = "Chapter 5", level = 2)
        assertEquals("Chapter 5", item.title)
        assertEquals(2, item.level)
    }

    @Test
    fun `SearchState default values`() {
        val state = SearchState()
        assertEquals("", state.query)
        assertFalse(state.isSearching)
        assertTrue(state.results.isEmpty())
        assertEquals(-1, state.currentIndex)
        assertNull(state.error)
    }

    @Test
    fun `SearchState copy with results`() {
        val results = listOf(
            SearchResult("match", "before ", " after")
        ).toPersistentList()
        val state = SearchState(
            query = "match",
            isSearching = false,
            results = results,
            currentIndex = 0
        )
        assertEquals("match", state.query)
        assertEquals(1, state.results.size)
        assertEquals("match", state.results[0].text)
        assertEquals("before ", state.results[0].before)
        assertEquals(" after", state.results[0].after)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `SelectionState default values`() {
        val state = SelectionState()
        assertFalse(state.isActive)
        assertEquals("", state.text)
    }

    @Test
    fun `SelectionState active with text`() {
        val state = SelectionState(isActive = true, text = "selected text")
        assertTrue(state.isActive)
        assertEquals("selected text", state.text)
    }

    @Test
    fun `SearchResult holds text before and after`() {
        val result = SearchResult(
            text = "highlighted",
            before = "This is ",
            after = " text"
        )
        assertEquals("highlighted", result.text)
        assertEquals("This is ", result.before)
        assertEquals(" text", result.after)
    }

    @Test
    fun `ReaderCommand sealed interface has all expected types`() {
        val commands: List<ReaderCommand> = listOf(
            ReaderCommand.ClearSelection,
            ReaderCommand.RequestCurrentSelection
        )
        assertEquals(2, commands.size)
    }
}
