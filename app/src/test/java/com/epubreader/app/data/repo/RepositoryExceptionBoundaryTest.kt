package com.epubreader.app.data.repo

import com.epubreader.app.core.AppError
import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.ErrorChannel
import com.epubreader.app.core.Result
import com.epubreader.app.data.bookimport.MetadataParser
import com.epubreader.app.data.local.dao.BookDao
import com.epubreader.app.data.local.dao.BookmarkDao
import com.epubreader.app.data.local.dao.HighlightDao
import com.epubreader.app.data.local.dao.NoteDao
import com.epubreader.app.data.local.dao.ReadingProgressDao
import com.epubreader.app.data.local.entity.BookmarkEntity
import com.epubreader.app.data.local.entity.HighlightEntity
import com.epubreader.app.data.local.entity.NoteEntity
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepositoryExceptionBoundaryTest {
    private class TestDispatchersProvider(testDispatcher: CoroutineDispatcher) : DispatchersProvider {
        override val io = testDispatcher
        override val default = testDispatcher
        override val main = testDispatcher
        override val mainImmediate = testDispatcher
        override val sync = testDispatcher
    }

    private val sampleHighlight = HighlightEntity(
        "hl-1", "book-1", "{}", "text", "yellow", 1000L, 2000L, false, null, null
    )

    // ────────────────────────────────────────────
    // Test 1: HighlightRepositoryImpl getById throws → Result.Error
    // ────────────────────────────────────────────
    @Test
    fun `highlight_getById_throws_returns_result_error`() = runTest {
        val throwingDao = mockk<HighlightDao>()
        coEvery { throwingDao.getById(any()) } throws RuntimeException("db corrupted")
        val repo = HighlightRepositoryImpl(
            throwingDao,
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        val result = repo.getHighlight("hl-1")
        assertTrue(result is Result.Error, "Expected Result.Error, got $result")
        assertEquals("db corrupted", (result as Result.Error).cause.message)
    }

    // ────────────────────────────────────────────
    // Test 2: HighlightRepositoryImpl upsert throws → Result.Error
    // ────────────────────────────────────────────
    @Test
    fun `highlight_upsert_throws_returns_result_error`() = runTest {
        val throwingDao = mockk<HighlightDao>()
        coEvery { throwingDao.upsert(any()) } throws android.database.sqlite.SQLiteException("disk full")
        val repo = HighlightRepositoryImpl(
            throwingDao,
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        val result = repo.addHighlight(sampleHighlight)
        assertTrue(result is Result.Error)
    }

    // ────────────────────────────────────────────
    // Test 3: HighlightRepositoryImpl getByBook throws → Result.Error
    // ────────────────────────────────────────────
    @Test
    fun `highlight_getByBook_throws_returns_result_error`() = runTest {
        val throwingDao = mockk<HighlightDao>()
        coEvery { throwingDao.getByBook(any()) } throws RuntimeException("query failed")
        val repo = HighlightRepositoryImpl(
            throwingDao,
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        val result = repo.getByBook("book-1")
        assertTrue(result is Result.Error, "Expected Result.Error, got $result")
        assertEquals("query failed", (result as Result.Error).cause.message)
    }

    // ────────────────────────────────────────────
    // Test 4: BookmarkRepositoryImpl getById throws → Result.Error
    // ────────────────────────────────────────────
    @Test
    fun `bookmark_getById_throws_returns_result_error`() = runTest {
        val throwingDao = mockk<BookmarkDao>()
        coEvery { throwingDao.getById(any()) } throws RuntimeException("db corrupted")
        val repo = BookmarkRepositoryImpl(
            throwingDao,
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        val result = repo.getBookmark("bm-1")
        assertTrue(result is Result.Error, "Expected Result.Error, got $result")
        assertEquals("db corrupted", (result as Result.Error).cause.message)
    }

    // ────────────────────────────────────────────
    // Test 5: NoteRepositoryImpl upsert throws → Result.Error
    // ────────────────────────────────────────────
    @Test
    fun `note_upsert_throws_returns_result_error`() = runTest {
        val throwingDao = mockk<NoteDao>()
        coEvery { throwingDao.upsert(any()) } throws android.database.sqlite.SQLiteException("disk full")
        val repo = NoteRepositoryImpl(
            throwingDao,
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        val note = NoteEntity("note-1", "book-1", null, null, "content", 1000L, 2000L, false, null, null)
        val result = repo.addNote(note)
        assertTrue(result is Result.Error)
    }

    // ────────────────────────────────────────────
    // Test 6: ReadingProgressRepositoryImpl getById throws → Result.Error
    // ────────────────────────────────────────────
    @Test
    fun `reading_progress_getById_throws_returns_result_error`() = runTest {
        val throwingDao = mockk<ReadingProgressDao>()
        coEvery { throwingDao.getById(any()) } throws RuntimeException("db corrupted")
        val repo = ReadingProgressRepositoryImpl(
            throwingDao,
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        val result = repo.getProgress("prog-1")
        assertTrue(result is Result.Error, "Expected Result.Error, got $result")
        assertEquals("db corrupted", (result as Result.Error).cause.message)
    }

    // ────────────────────────────────────────────
    // Test 7: CancellationException is re-thrown, not swallowed
    // ────────────────────────────────────────────
    @Test
    fun `cancellation_exception_is_rethrown_not_swallowed`() = runTest {
        val throwingDao = mockk<BookDao>()
        coEvery { throwingDao.getById(any()) } throws CancellationException("cancelled")
        val repo = BookRepositoryImpl(
            throwingDao,
            mockk<MetadataParser>(),
            TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        var caught = false
        try {
            repo.getBook("u1")
        } catch (e: CancellationException) {
            caught = true
            assertEquals("cancelled", e.message)
        }
        assertTrue(caught, "Expected CancellationException to be thrown")
    }

    // ────────────────────────────────────────────
    // Test 8: AppCoroutineExceptionHandler emits to ErrorChannel
    // Note: Uses the production ErrorChannel + CEH pattern that
    // AppCoroutineExceptionHandler employs. The CEH catches uncaught
    // coroutine exceptions and emits them via ErrorChannel.tryEmit.
    // ────────────────────────────────────────────
    @Test
    fun `exception_handler_emits_to_error_channel`() = runTest {
        val errorChannel = ErrorChannel()
        val receivedError = CompletableDeferred<AppError>()

        // Start collecting BEFORE the exception is thrown,
        // so we're subscribed when tryEmit fires.
        val collectJob = backgroundScope.launch {
            errorChannel.errors.collect { receivedError.complete(it) }
        }

        // Create a CEH that emits to the ErrorChannel (same pattern as production)
        val handler = CoroutineExceptionHandler { _, throwable ->
            errorChannel.tryEmit(AppError(throwable.message ?: "Unexpected error", throwable))
        }
        val scope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() + handler
        )
        scope.launch { throw RuntimeException("test error") }
        testScheduler.advanceUntilIdle()

        val error = receivedError.await()
        assertEquals("test error", error.message)

        collectJob.cancel()
    }
}
