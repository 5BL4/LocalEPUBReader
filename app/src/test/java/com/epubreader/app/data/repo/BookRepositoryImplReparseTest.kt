package com.epubreader.app.data.repo

import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.Result
import com.epubreader.app.data.bookimport.BookMetadata
import com.epubreader.app.data.bookimport.MetadataParser
import com.epubreader.app.data.local.dao.BookDao
import com.epubreader.app.data.local.entity.BookEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class BookRepositoryImplReparseTest {

    private class TestDispatchersProvider(testDispatcher: CoroutineDispatcher) : DispatchersProvider {
        override val io = testDispatcher
        override val default = testDispatcher
        override val main = testDispatcher
        override val mainImmediate = testDispatcher
        override val sync = testDispatcher
    }

    private val sampleBook = BookEntity(
        uuid = "book-uuid-001",
        title = "Old Title",
        author = "Old Author",
        coverPath = "/old/cover.png",
        filePath = "/books/test.epub",
        fileSize = 5000L,
        format = "epub",
        createdAt = 1000L,
        updatedAt = 2000L,
        isDeleted = false,
        syncedAt = null,
        userId = null
    )

    @Test
    fun `successful reparse updates book metadata`() = runTest {
        val bookDao = mockk<BookDao>(relaxed = true)
        val metadataParser = mockk<MetadataParser>(relaxed = true)
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)

        coEvery { bookDao.getById("book-uuid-001") } returns sampleBook
        coEvery { metadataParser.parse(sampleBook.filePath) } returns Result.Success(
            BookMetadata(title = "New Title", author = "New Author", coverPath = "/new/cover.png")
        )

        val upsertSlot = slot<BookEntity>()
        coEvery { bookDao.upsert(capture(upsertSlot)) } returns Unit

        val repo = BookRepositoryImpl(bookDao, metadataParser, TestDispatchersProvider(testDispatcher))

        val result = repo.reparseMetadata("book-uuid-001")

        assertTrue(result is Result.Success, "Expected Result.Success, got $result")
        val updatedBook = (result as Result.Success).data
        assertEquals("New Title", updatedBook.title)
        assertEquals("New Author", updatedBook.author)
        assertEquals("/new/cover.png", updatedBook.coverPath)
        assertEquals("book-uuid-001", updatedBook.uuid)
        assertEquals(sampleBook.filePath, updatedBook.filePath)

        // Verify upsert was called with updated entity
        coVerify(exactly = 1) { bookDao.upsert(any()) }
        val captured = upsertSlot.captured
        assertEquals("New Title", captured.title)
        assertEquals("New Author", captured.author)
        assertEquals("/new/cover.png", captured.coverPath)
    }

    @Test
    fun `reparse preserves existing coverPath when new metadata has no cover`() = runTest {
        val bookDao = mockk<BookDao>(relaxed = true)
        val metadataParser = mockk<MetadataParser>(relaxed = true)
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)

        coEvery { bookDao.getById("book-uuid-001") } returns sampleBook
        // Metadata parse returns coverPath=null — should preserve old cover
        coEvery { metadataParser.parse(sampleBook.filePath) } returns Result.Success(
            BookMetadata(title = "New Title", author = "New Author", coverPath = null)
        )

        val upsertSlot = slot<BookEntity>()
        coEvery { bookDao.upsert(capture(upsertSlot)) } returns Unit

        val repo = BookRepositoryImpl(bookDao, metadataParser, TestDispatchersProvider(testDispatcher))

        val result = repo.reparseMetadata("book-uuid-001")

        assertTrue(result is Result.Success, "Expected Result.Success, got $result")
        val updatedBook = (result as Result.Success).data
        assertEquals("/old/cover.png", updatedBook.coverPath, "Should preserve old coverPath when new metadata has null")

        val captured = upsertSlot.captured
        assertEquals("/old/cover.png", captured.coverPath)
    }

    @Test
    fun `book not found returns error`() = runTest {
        val bookDao = mockk<BookDao>(relaxed = true)
        val metadataParser = mockk<MetadataParser>(relaxed = true)
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)

        coEvery { bookDao.getById("nonexistent") } returns null

        val repo = BookRepositoryImpl(bookDao, metadataParser, TestDispatchersProvider(testDispatcher))

        val result = repo.reparseMetadata("nonexistent")

        assertTrue(result is Result.Error, "Expected Result.Error for missing book, got $result")
        val error = (result as Result.Error).cause
        assertTrue(error is NoSuchElementException, "Expected NoSuchElementException, got ${error::class.simpleName}")
        assertEquals("Book not found: nonexistent", error.message)

        // Verify no metadata parse or upsert was called
        coVerify(exactly = 0) { metadataParser.parse(any()) }
        coVerify(exactly = 0) { bookDao.upsert(any()) }
    }

    @Test
    fun `metadata parse failure returns error`() = runTest {
        val bookDao = mockk<BookDao>(relaxed = true)
        val metadataParser = mockk<MetadataParser>(relaxed = true)
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)

        coEvery { bookDao.getById("book-uuid-001") } returns sampleBook
        coEvery { metadataParser.parse(sampleBook.filePath) } returns Result.Error(IOException("Parse failed"))

        val repo = BookRepositoryImpl(bookDao, metadataParser, TestDispatchersProvider(testDispatcher))

        val result = repo.reparseMetadata("book-uuid-001")

        assertTrue(result is Result.Error, "Expected Result.Error for parse failure, got $result")
        val error = (result as Result.Error).cause
        assertTrue(error is IOException, "Expected IOException, got ${error::class.simpleName}")
        assertEquals("Parse failed", error.message)

        // Verify no upsert was called
        coVerify(exactly = 0) { bookDao.upsert(any()) }
    }
}
