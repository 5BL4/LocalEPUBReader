package com.epubreader.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.epubreader.app.data.local.entity.BookEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookDaoSoftDeleteTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: BookDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.bookDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun softDelete_marks_isDeleted_and_excludes_from_active() = runTest {
        val now = 1000L
        val book = BookEntity(
            uuid = "u1",
            title = "T",
            author = null,
            coverPath = null,
            filePath = "/p",
            fileSize = 1L,
            format = "epub",
            createdAt = now,
            updatedAt = now,
            isDeleted = false,
            syncedAt = null,
            userId = null
        )
        dao.upsert(book)
        dao.softDelete("u1", 2000L)
        val all = dao.getAllActive()
        assertTrue(all.isEmpty())
        val dirty = dao.getDirty()
        assertEquals(1, dirty.size)
        assertEquals(true, dirty.first().isDeleted)
    }

    @Test
    fun observeActive_filters_soft_deleted() = runTest {
        dao.observeActive().test {
            assertEquals(0, awaitItem().size)
            val now = 1L
            dao.upsert(
                BookEntity(
                    "u2", "T2", null, null, "/p", 1L, "epub",
                    now, now, false, null, null
                )
            )
            assertEquals(1, awaitItem().size)
            dao.softDelete("u2", 2L)
            assertEquals(0, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
