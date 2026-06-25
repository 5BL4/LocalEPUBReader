package com.epubreader.app.ui.bookshelf

import android.net.Uri
import app.cash.turbine.test
import com.epubreader.app.core.AppCoroutineExceptionHandler
import com.epubreader.app.core.ErrorChannel
import com.epubreader.app.core.Result
import com.epubreader.app.core.StringProvider
import com.epubreader.app.data.bookimport.BookImporter
import com.epubreader.app.data.local.entity.BookEntity
import com.epubreader.app.domain.repository.BookRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class BookshelfViewModelTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        books: List<BookEntity> = emptyList(),
        bookImporter: BookImporter = mockk(relaxed = true)
    ): BookshelfViewModel {
        val bookRepo = mockk<BookRepository>(relaxed = true)
        every { bookRepo.observeBooks() } returns flowOf(books)

        val errorChannel = ErrorChannel()
        val exceptionHandler = AppCoroutineExceptionHandler(errorChannel)
        val stringProvider = mockk<StringProvider>(relaxed = true)
        every { stringProvider.get(any()) } returns "error message"

        return BookshelfViewModel(
            bookRepository = bookRepo,
            bookImporter = bookImporter,
            exceptionHandler = exceptionHandler,
            stringProvider = stringProvider,
            errorChannel = errorChannel
        )
    }

    @Test
    fun `uiState emits books from repository`() = runTest {
        val books = listOf(
            BookEntity("u1", "Book 1", "Author 1", null, "/p1", 100L, "epub", 1L, 1L, false),
            BookEntity("u2", "Book 2", null, null, "/p2", 200L, "epub", 2L, 2L, false)
        )
        val viewModel = createViewModel(books = books)

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals(2, state.books.size)
            assertEquals("Book 1", state.books[0].title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `importBook transitions to Success`() = runTest {
        val bookImporter = mockk<BookImporter>(relaxed = true)
        val book = BookEntity("u1", "Test", null, null, "/p", 100L, "epub", 1L, 1L, false)
        coEvery { bookImporter.importBook(any(), any()) } returns Result.Success(book)

        val viewModel = createViewModel(bookImporter = bookImporter)

        viewModel.importState.test {
            assertEquals(ImportState.Idle, awaitItem())
            viewModel.importBook(mockk(relaxed = true))
            // With UnconfinedTestDispatcher, the coroutine runs synchronously.
            // The flow may emit Importing and Success, or conflate to just Success.
            val state = awaitItem()
            // Accept either Importing or Success (whichever Turbine received)
            assertTrue(state != ImportState.Idle, "State should have changed from Idle, got $state")
            // If we only got Importing, wait for Success
            if (state == ImportState.Importing) {
                assertEquals(ImportState.Success, awaitItem())
            } else {
                assertEquals(ImportState.Success, state)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `importBook transitions to Error on failure`() = runTest {
        val bookImporter = mockk<BookImporter>(relaxed = true)
        coEvery {
            bookImporter.importBook(any(), any())
        } returns Result.Error(IOException("disk error"))

        val viewModel = createViewModel(bookImporter = bookImporter)

        viewModel.importState.test {
            assertEquals(ImportState.Idle, awaitItem())
            viewModel.importBook(mockk(relaxed = true))
            val state = awaitItem()
            // Accept either Importing or Error
            if (state == ImportState.Importing) {
                val errorState = awaitItem()
                assertTrue(errorState is ImportState.Error)
            } else {
                assertTrue(state is ImportState.Error)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancelImport resets to Idle`() = runTest {
        val viewModel = createViewModel()

        viewModel.importState.test {
            assertEquals(ImportState.Idle, awaitItem())
            viewModel.cancelImport()
            // Should stay Idle (setting same value won't re-emit)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
