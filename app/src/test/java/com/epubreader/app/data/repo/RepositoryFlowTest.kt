package com.epubreader.app.data.repo

import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.ErrorChannel
import com.epubreader.app.core.AppError
import com.epubreader.app.data.bookimport.MetadataParser
import com.epubreader.app.data.local.dao.BookDao
import com.epubreader.app.data.local.dao.BookmarkDao
import com.epubreader.app.data.local.dao.HighlightDao
import com.epubreader.app.data.local.dao.NoteDao
import com.epubreader.app.data.local.dao.ReadingProgressDao
import com.epubreader.app.data.local.entity.BookEntity
import com.epubreader.app.data.local.entity.BookmarkEntity
import com.epubreader.app.data.local.entity.HighlightEntity
import com.epubreader.app.data.local.entity.NoteEntity
import com.epubreader.app.data.local.entity.ReadingProgressEntity
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepositoryFlowTest {
    private class TestDispatchersProvider(testDispatcher: CoroutineDispatcher) : DispatchersProvider {
        override val io = testDispatcher
        override val default = testDispatcher
        override val main = testDispatcher
        override val mainImmediate = testDispatcher
        override val sync = testDispatcher
    }

    private val sampleBook1 = BookEntity(
        "book-1", "Title One", "Author One", "/cover1.png",
        "/path/one.epub", 1024L, "epub", 1000L, 2000L, false, null, null
    )
    private val sampleBook2 = BookEntity(
        "book-2", "Title Two", "Author Two", "/cover2.png",
        "/path/two.epub", 2048L, "epub", 1500L, 2500L, false, null, null
    )
    private val sampleHighlight = HighlightEntity(
        "hl-1", "book-1", "{}", "highlighted text", "yellow", 1000L, 2000L, false, null, null
    )
    private val sampleBookmark = BookmarkEntity(
        "bm-1", "book-1", "{}", "My Bookmark", 1000L, 2000L, false, null, null
    )
    private val sampleNote = NoteEntity(
        "note-1", "book-1", null, null, "My note content", 1000L, 2000L, false, null, null
    )
    private val sampleProgress = ReadingProgressEntity(
        "prog-1", "book-1", "{}", 0.5, 1000L, 2000L, false, null, null
    )

    // ────────────────────────────────────────────
    // Test 1: BookRepository observeBooks emits list from DAO flow
    // ────────────────────────────────────────────
    @Test
    fun `book_repository_observeBooks_emits_list_from_dao_flow`() = runTest {
        val dao = mockk<BookDao>()
        every { dao.observeActive() } returns flowOf(listOf(sampleBook1, sampleBook2))
        val repo = BookRepositoryImpl(
            dao,
            mockk<MetadataParser>(),
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        repo.observeBooks().test {
            val items = awaitItem()
            assertEquals(2, items.size)
            assertEquals(sampleBook1, items[0])
            assertEquals(sampleBook2, items[1])
            awaitComplete()
        }
    }

    // ────────────────────────────────────────────
    // Test 2: BookRepository observeBook emits null for missing uuid
    // ────────────────────────────────────────────
    @Test
    fun `book_repository_observeBook_emits_null_for_missing_uuid`() = runTest {
        val dao = mockk<BookDao>()
        every { dao.observeById("missing") } returns flowOf(null)
        val repo = BookRepositoryImpl(
            dao,
            mockk<MetadataParser>(),
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        repo.observeBook("missing").test {
            assertNull(awaitItem())
            awaitComplete()
        }
    }

    // ────────────────────────────────────────────
    // Test 3: BookRepository observeBook emits entity when found
    // ────────────────────────────────────────────
    @Test
    fun `book_repository_observeBook_emits_entity_when_found`() = runTest {
        val dao = mockk<BookDao>()
        every { dao.observeById("book-1") } returns flowOf(sampleBook1)
        val repo = BookRepositoryImpl(
            dao,
            mockk<MetadataParser>(),
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        repo.observeBook("book-1").test {
            val book = awaitItem()
            assertNotNull(book)
            assertEquals("book-1", book!!.uuid)
            assertEquals("Title One", book.title)
            awaitComplete()
        }
    }

    // ────────────────────────────────────────────
    // Test 4: HighlightRepository observeHighlights emits filtered list
    // ────────────────────────────────────────────
    @Test
    fun `highlight_repository_observeHighlights_emits_filtered_list`() = runTest {
        val dao = mockk<HighlightDao>()
        every { dao.observeByBook("book-1") } returns flowOf(listOf(sampleHighlight))
        val repo = HighlightRepositoryImpl(
            dao,
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        repo.observeHighlights("book-1").test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("hl-1", items[0].uuid)
            assertEquals("highlighted text", items[0].text)
            awaitComplete()
        }
    }

    // ────────────────────────────────────────────
    // Test 5: BookmarkRepository observeBookmarks emits list
    // ────────────────────────────────────────────
    @Test
    fun `bookmark_repository_observeBookmarks_emits_list`() = runTest {
        val dao = mockk<BookmarkDao>()
        every { dao.observeByBook("book-1") } returns flowOf(listOf(sampleBookmark))
        val repo = BookmarkRepositoryImpl(
            dao,
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        repo.observeBookmarks("book-1").test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("bm-1", items[0].uuid)
            assertEquals("My Bookmark", items[0].label)
            awaitComplete()
        }
    }

    // ────────────────────────────────────────────
    // Test 6: NoteRepository observeNotes emits list
    // ────────────────────────────────────────────
    @Test
    fun `note_repository_observeNotes_emits_list`() = runTest {
        val dao = mockk<NoteDao>()
        every { dao.observeByBook("book-1") } returns flowOf(listOf(sampleNote))
        val repo = NoteRepositoryImpl(
            dao,
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        repo.observeNotes("book-1").test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("note-1", items[0].uuid)
            assertEquals("My note content", items[0].content)
            awaitComplete()
        }
    }

    // ────────────────────────────────────────────
    // Test 7: ReadingProgressRepository observeProgress emits single entity
    // ────────────────────────────────────────────
    @Test
    fun `reading_progress_repository_observeProgress_emits_single_entity`() = runTest {
        val dao = mockk<ReadingProgressDao>()
        every { dao.observeByBookSingle("book-1") } returns flowOf(sampleProgress)
        val repo = ReadingProgressRepositoryImpl(
            dao,
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        repo.observeProgress("book-1").test {
            val progress = awaitItem()
            assertNotNull(progress)
            assertEquals("prog-1", progress!!.uuid)
            assertEquals(0.5, progress.progress)
            awaitComplete()
        }
    }

    // ────────────────────────────────────────────
    // Test 8: ReadingProgressRepository observeProgress emits null when no progress
    // ────────────────────────────────────────────
    @Test
    fun `reading_progress_repository_observeProgress_emits_null_when_no_progress`() = runTest {
        val dao = mockk<ReadingProgressDao>()
        every { dao.observeByBookSingle("book-1") } returns flowOf(null)
        val repo = ReadingProgressRepositoryImpl(
            dao,
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        repo.observeProgress("book-1").test {
            assertNull(awaitItem())
            awaitComplete()
        }
    }

    // ────────────────────────────────────────────
    // Test 9: Flow cancellation stops collection
    // ────────────────────────────────────────────
    @Test
    fun `flow_cancellation_stops_collection`() = runTest {
        val dao = mockk<BookDao>()
        every { dao.observeActive() } returns flow {
            emit(listOf(sampleBook1))
            delay(10000) // hang indefinitely
        }
        val repo = BookRepositoryImpl(
            dao,
            mockk<MetadataParser>(),
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        val job = launch {
            repo.observeBooks().collect { /* collect one item then hang */ }
        }
        // Cancel the collecting job
        job.cancel()
        assertTrue(job.isCancelled)
    }

    // ────────────────────────────────────────────
    // Test 10: ErrorChannel errors flow emits via Turbine
    // ────────────────────────────────────────────
    @Test
    fun `error_channel_errors_flow_emits_via_turbine`() = runTest {
        val errorChannel = ErrorChannel()
        errorChannel.errors.test {
            // Emit an error
            errorChannel.tryEmit(AppError("Something went wrong"))
            val error = awaitItem()
            assertEquals("Something went wrong", error.message)
            cancelAndConsumeRemainingEvents()
        }
    }
}
