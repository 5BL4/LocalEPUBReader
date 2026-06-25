package com.epubreader.app.data.bookimport

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.Result
import com.epubreader.app.data.local.entity.BookEntity
import com.epubreader.app.domain.repository.BookRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class EpubBookImporterTest {

    @TempDir
    lateinit var tempDir: File

    private object UnconfinedDispatchers : DispatchersProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
        override val sync: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private fun mockContextWithFileDir(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempDir
        return context
    }

    private fun mockCursor(size: Long?, displayName: String?): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.moveToFirst() } returns true
        // Use simple 0-indexed column positions; relaxed mock handles unmatched columns with defaults
        every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns 0
        every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 1
        if (size != null) {
            every { cursor.isNull(0) } returns false
            every { cursor.getLong(0) } returns size
        } else {
            every { cursor.isNull(0) } returns true
        }
        if (displayName != null) {
            every { cursor.isNull(1) } returns false
            every { cursor.getString(1) } returns displayName
        } else {
            every { cursor.isNull(1) } returns true
        }
        every { cursor.close() } returns Unit
        return cursor
    }

    private fun testUri(): Uri = mockk(relaxed = true)

    @Test
    fun `insufficient storage returns Error and creates no file`() = runTest(UnconfinedTestDispatcher()) {
        val context = mockContextWithFileDir()
        val contentResolver = mockk<android.content.ContentResolver>(relaxed = true)
        every { context.contentResolver } returns contentResolver

        val hugeSize = Long.MAX_VALUE / 2
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns mockCursor(hugeSize, "book.epub")

        val bookRepo = mockk<BookRepository>(relaxed = true)
        val metadataParser = mockk<MetadataParser>(relaxed = true)
        val importer = EpubBookImporter(context, bookRepo, metadataParser, UnconfinedDispatchers)

        val result = importer.importBook(testUri()) {}

        assertTrue(result is Result.Error, "Expected Error, got $result")
        val cause = (result as Result.Error).cause
        assertTrue(cause is InsufficientStorageException, "Expected InsufficientStorageException, got ${cause::class}")

        val booksDir = File(tempDir, "books")
        if (booksDir.exists()) {
            assertEquals(0, booksDir.listFiles()?.size ?: 0, "No partial files should remain")
        }
        coVerify(exactly = 0) { bookRepo.addBook(any()) }
    }

    @Test
    fun `successful import copies file and saves book`() = runTest(UnconfinedTestDispatcher()) {
        val context = mockContextWithFileDir()
        val contentResolver = mockk<android.content.ContentResolver>(relaxed = true)
        every { context.contentResolver } returns contentResolver

        val epubContent = "fake epub content for testing".toByteArray()
        every {
            contentResolver.query(any(), any(), any(), any(), any())
        } returns mockCursor(epubContent.size.toLong(), "test.epub")
        every { contentResolver.openInputStream(any()) } returns ByteArrayInputStream(epubContent)

        val bookRepo = mockk<BookRepository>(relaxed = true)
        coEvery { bookRepo.addBook(any()) } returns Result.Success(Unit)

        val metadataParser = mockk<MetadataParser>(relaxed = true)
        coEvery { metadataParser.parse(any()) } returns Result.Success(
            BookMetadata("Test", null, null)
        )

        val importer = EpubBookImporter(context, bookRepo, metadataParser, UnconfinedDispatchers)

        var lastProgress = -2f
        val result = importer.importBook(testUri()) { progress ->
            lastProgress = progress
        }

        assertTrue(result is Result.Success, "Expected Success, got $result")
        val book = (result as Result.Success).data
        assertEquals("Test", book.title)
        assertEquals("epub", book.format)
        assertEquals(epubContent.size.toLong(), book.fileSize)
        assertFalse(book.isDeleted)

        val savedFile = File(book.filePath)
        assertTrue(savedFile.exists(), "Copied file should exist")
        assertEquals(epubContent.size.toLong(), savedFile.length())

        // Progress callback should have been invoked at least once
        assertTrue(lastProgress != -2f, "Progress callback was never invoked")

        coVerify(exactly = 1) { bookRepo.addBook(any()) }
    }

    @Test
    fun `copy failure cleans up partial file`() = runTest(UnconfinedTestDispatcher()) {
        val context = mockContextWithFileDir()
        val contentResolver = mockk<android.content.ContentResolver>(relaxed = true)
        every { context.contentResolver } returns contentResolver

        every {
            contentResolver.query(any(), any(), any(), any(), any())
        } returns mockCursor(1000L, "book.epub")
        every { contentResolver.openInputStream(any()) } throws IOException("Cannot read URI")

        val bookRepo = mockk<BookRepository>(relaxed = true)
        val metadataParser = mockk<MetadataParser>(relaxed = true)
        val importer = EpubBookImporter(context, bookRepo, metadataParser, UnconfinedDispatchers)

        val result = importer.importBook(testUri()) {}

        assertTrue(result is Result.Error, "Expected Error, got $result")

        val booksDir = File(tempDir, "books")
        if (booksDir.exists()) {
            val files = booksDir.listFiles()
            assertEquals(
                0,
                files?.size ?: 0,
                "No partial files should remain after failure, found: ${files?.toList()}"
            )
        }
        coVerify(exactly = 0) { bookRepo.addBook(any()) }
    }

    @Test
    fun `metadata parse failure cleans up copied file`() = runTest(UnconfinedTestDispatcher()) {
        val context = mockContextWithFileDir()
        val contentResolver = mockk<android.content.ContentResolver>(relaxed = true)
        every { context.contentResolver } returns contentResolver

        val epubContent = "fake epub".toByteArray()
        every {
            contentResolver.query(any(), any(), any(), any(), any())
        } returns mockCursor(epubContent.size.toLong(), "test.epub")
        every { contentResolver.openInputStream(any()) } returns ByteArrayInputStream(epubContent)

        val bookRepo = mockk<BookRepository>(relaxed = true)
        val metadataParser = mockk<MetadataParser>(relaxed = true)
        coEvery { metadataParser.parse(any()) } returns Result.Error(IOException("parse failed"))

        val importer = EpubBookImporter(context, bookRepo, metadataParser, UnconfinedDispatchers)

        val result = importer.importBook(testUri()) {}

        assertTrue(result is Result.Error, "Expected Error, got $result")

        val booksDir = File(tempDir, "books")
        if (booksDir.exists()) {
            assertEquals(0, booksDir.listFiles()?.size ?: 0, "No partial files should remain")
        }
        coVerify(exactly = 0) { bookRepo.addBook(any()) }
    }
}
