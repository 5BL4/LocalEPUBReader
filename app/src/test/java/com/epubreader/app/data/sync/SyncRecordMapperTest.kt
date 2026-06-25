package com.epubreader.app.data.sync

import com.epubreader.app.core.sync.SyncMeta
import com.epubreader.app.core.sync.SyncRecord
import com.epubreader.app.core.sync.SyncType
import com.epubreader.app.data.local.entity.BookEntity
import com.epubreader.app.data.local.entity.HighlightEntity
import com.epubreader.app.data.local.entity.NoteEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SyncRecordMapperTest {

    private val mapper = SyncRecordMapper()

    @Test
    fun `BookEntity to SyncRecord to BookEntity round-trip preserves all fields`() {
        val original = BookEntity(
            uuid = "book-1",
            title = "Test Title",
            author = "Author Name",
            coverPath = "/covers/book1.jpg",
            filePath = "/books/book1.epub",
            fileSize = 123456L,
            format = "epub",
            createdAt = 1000L,
            updatedAt = 2000L,
            isDeleted = false,
            syncedAt = 2000L,
            userId = "user-1"
        )

        val record = mapper.toRecord(original)
        assertEquals(SyncType.BOOK, record.type)

        val restored = mapper.fromBookRecord(record)

        assertEquals(original.uuid, restored.uuid)
        assertEquals(original.title, restored.title)
        assertEquals(original.author, restored.author)
        assertEquals(original.coverPath, restored.coverPath)
        assertEquals(original.filePath, restored.filePath)
        assertEquals(original.fileSize, restored.fileSize)
        assertEquals(original.format, restored.format)
        assertEquals(original.isDeleted, restored.isDeleted)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.updatedAt, restored.updatedAt)
        assertEquals(original.syncedAt, restored.syncedAt)
        assertEquals(original.userId, restored.userId)
    }

    @Test
    fun `HighlightEntity to SyncRecord to HighlightEntity round-trip preserves all fields`() {
        val original = HighlightEntity(
            uuid = "hl-1",
            bookUuid = "book-1",
            locator = "{\"href\":\"chap1.html\",\"offset\":42}",
            text = "highlighted text",
            color = "yellow",
            createdAt = 1000L,
            updatedAt = 2000L,
            isDeleted = false,
            syncedAt = 2000L,
            userId = "user-1"
        )

        val record = mapper.toRecord(original)
        assertEquals(SyncType.HIGHLIGHT, record.type)

        val restored = mapper.fromHighlightRecord(record)

        assertEquals(original.uuid, restored.uuid)
        assertEquals(original.bookUuid, restored.bookUuid)
        assertEquals(original.locator, restored.locator)
        assertEquals(original.text, restored.text)
        assertEquals(original.color, restored.color)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.updatedAt, restored.updatedAt)
        assertEquals(original.isDeleted, restored.isDeleted)
        assertEquals(original.syncedAt, restored.syncedAt)
        assertEquals(original.userId, restored.userId)
    }

    @Test
    fun `NoteEntity with nullable fields round-trip preserves nulls`() {
        val original = NoteEntity(
            uuid = "note-1",
            bookUuid = "book-1",
            highlightUuid = null,
            locator = null,
            content = "A note about chapter 1",
            createdAt = 1000L,
            updatedAt = 2000L,
            isDeleted = false,
            syncedAt = 2000L,
            userId = "user-1"
        )

        val record = mapper.toRecord(original)
        assertEquals(SyncType.NOTE, record.type)

        val restored = mapper.fromNoteRecord(record)

        assertEquals(original.uuid, restored.uuid)
        assertEquals(original.bookUuid, restored.bookUuid)
        assertNull(restored.highlightUuid, "highlightUuid should be null after round-trip")
        assertNull(restored.locator, "locator should be null after round-trip")
        assertEquals(original.content, restored.content)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.updatedAt, restored.updatedAt)
        assertEquals(original.syncedAt, restored.syncedAt)
        assertEquals(original.userId, restored.userId)
    }

    @Test
    fun `fromBookRecord sets syncedAt to remote updatedAt`() {
        val record = SyncRecord(
            type = SyncType.BOOK,
            meta = SyncMeta(
                uuid = "book-1",
                isDeleted = false,
                createdAt = 1000L,
                updatedAt = 5000L,
                syncedAt = 3000L,
                userId = null
            ),
            payload = buildJsonObject {
                put("title", "Test")
                put("author", "Author")
                put("coverPath", "/c.jpg")
                put("filePath", "/f.epub")
                put("fileSize", 100L)
                put("format", "epub")
            }
        )

        val entity = mapper.fromBookRecord(record)

        // Oracle M2: syncedAt should be set to remote.updatedAt (5000), NOT the original syncedAt (3000)
        assertEquals(5000L, entity.syncedAt)
    }

    @Test
    fun `fromNoteRecord throws IllegalArgumentException on type mismatch`() {
        val record = SyncRecord(
            type = SyncType.BOOK,
            meta = SyncMeta(
                uuid = "note-1",
                isDeleted = false,
                createdAt = 1000L,
                updatedAt = 2000L,
                syncedAt = null,
                userId = null
            ),
            payload = buildJsonObject {
                put("bookUuid", "book-1")
                put("content", "text")
            }
        )

        assertThrows<IllegalArgumentException> {
            mapper.fromNoteRecord(record)
        }
    }

    @Test
    fun `nullable String fields handled correctly`() {
        val original = BookEntity(
            uuid = "book-2",
            title = "Title",
            author = null,
            coverPath = null,
            filePath = "/path/book.epub",
            fileSize = 999L,
            format = "pdf",
            createdAt = 1000L,
            updatedAt = 2000L,
            isDeleted = false,
            syncedAt = 2000L,
            userId = null
        )

        val record = mapper.toRecord(original)
        val restored = mapper.fromBookRecord(record)

        assertEquals(original.title, restored.title)
        assertNull(restored.author, "author should still be null after round-trip")
        assertNull(restored.coverPath, "coverPath should still be null after round-trip")
        assertNull(restored.userId, "userId should still be null after round-trip")
        assertEquals(original.filePath, restored.filePath)
        assertEquals(original.fileSize, restored.fileSize)
    }
}
