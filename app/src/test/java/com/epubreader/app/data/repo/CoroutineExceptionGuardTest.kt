package com.epubreader.app.data.repo

import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.Result
import com.epubreader.app.data.bookimport.MetadataParser
import com.epubreader.app.data.local.dao.BookDao
import com.epubreader.app.data.local.entity.BookEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoroutineExceptionGuardTest {
    private class TestDispatchersProvider(testDispatcher: CoroutineDispatcher) : DispatchersProvider {
        override val io = testDispatcher
        override val default = testDispatcher
        override val main = testDispatcher
        override val mainImmediate = testDispatcher
        override val sync = testDispatcher
    }

    @Test
    fun dao_throws_returns_result_error_not_propagation() = runTest {
        val throwingDao = mockk<BookDao>()
        coEvery { throwingDao.getById(any()) } throws RuntimeException("db corrupted")
        val repo = BookRepositoryImpl(throwingDao, mockk<MetadataParser>(), TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler)))
        val result = repo.getBook("u1")
        assertTrue(result is Result.Error, "Expected Result.Error, got $result")
        assertEquals("db corrupted", (result as Result.Error).cause.message)
    }

    @Test
    fun dao_upsert_throws_returns_result_error() = runTest {
        val throwingDao = mockk<BookDao>()
        coEvery { throwingDao.upsert(any()) } throws android.database.sqlite.SQLiteException("disk full")
        val repo = BookRepositoryImpl(throwingDao, mockk<MetadataParser>(), TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler)))
        val book = BookEntity("u1", "T", null, null, "/p", 1L, "epub", 1L, 1L, false, null, null)
        val result = repo.addBook(book)
        assertTrue(result is Result.Error)
    }
}
