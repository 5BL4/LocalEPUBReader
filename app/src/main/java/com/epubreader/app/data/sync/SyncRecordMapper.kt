package com.epubreader.app.data.sync

import com.epubreader.app.core.sync.SyncMeta
import com.epubreader.app.core.sync.SyncRecord
import com.epubreader.app.core.sync.SyncType
import com.epubreader.app.data.local.entity.BookEntity
import com.epubreader.app.data.local.entity.BookmarkEntity
import com.epubreader.app.data.local.entity.HighlightEntity
import com.epubreader.app.data.local.entity.NoteEntity
import com.epubreader.app.data.local.entity.ReadingProgressEntity
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRecordMapper @Inject constructor() {

    // ── Book ──

    fun toRecord(entity: BookEntity): SyncRecord = SyncRecord(
        type = SyncType.BOOK,
        meta = SyncMeta(
            uuid = entity.uuid,
            isDeleted = entity.isDeleted,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            syncedAt = entity.syncedAt,
            userId = entity.userId
        ),
        payload = buildJsonObject {
            put("title", entity.title)
            put("author", entity.author)
            put("coverPath", entity.coverPath)
            put("filePath", entity.filePath)
            put("fileSize", entity.fileSize)
            put("format", entity.format)
        }
    )

    fun fromBookRecord(record: SyncRecord): BookEntity {
        require(record.type == SyncType.BOOK) { "Expected BOOK, got ${record.type}" }
        val p = record.payload
        return BookEntity(
            uuid = record.meta.uuid,
            title = p["title"]!!.jsonPrimitive.content,
            author = nullableString(p, "author"),
            coverPath = nullableString(p, "coverPath"),
            filePath = p["filePath"]!!.jsonPrimitive.content,
            fileSize = p["fileSize"]!!.jsonPrimitive.long,
            format = p["format"]!!.jsonPrimitive.content,
            createdAt = record.meta.createdAt,
            updatedAt = record.meta.updatedAt,
            isDeleted = record.meta.isDeleted,
            syncedAt = record.meta.updatedAt,  // Oracle M2: set syncedAt to remote.updatedAt
            userId = record.meta.userId
        )
    }

    // ── ReadingProgress ──

    fun toRecord(entity: ReadingProgressEntity): SyncRecord = SyncRecord(
        type = SyncType.READING_PROGRESS,
        meta = SyncMeta(
            uuid = entity.uuid,
            isDeleted = entity.isDeleted,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            syncedAt = entity.syncedAt,
            userId = entity.userId
        ),
        payload = buildJsonObject {
            put("bookUuid", entity.bookUuid)
            put("locator", entity.locator)
            put("progress", entity.progress)
        }
    )

    fun fromReadingProgressRecord(record: SyncRecord): ReadingProgressEntity {
        require(record.type == SyncType.READING_PROGRESS) { "Expected READING_PROGRESS, got ${record.type}" }
        val p = record.payload
        return ReadingProgressEntity(
            uuid = record.meta.uuid,
            bookUuid = p["bookUuid"]!!.jsonPrimitive.content,
            locator = p["locator"]!!.jsonPrimitive.content,
            progress = p["progress"]!!.jsonPrimitive.double,
            createdAt = record.meta.createdAt,
            updatedAt = record.meta.updatedAt,
            isDeleted = record.meta.isDeleted,
            syncedAt = record.meta.updatedAt,  // Oracle M2
            userId = record.meta.userId
        )
    }

    // ── Bookmark ──

    fun toRecord(entity: BookmarkEntity): SyncRecord = SyncRecord(
        type = SyncType.BOOKMARK,
        meta = SyncMeta(
            uuid = entity.uuid,
            isDeleted = entity.isDeleted,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            syncedAt = entity.syncedAt,
            userId = entity.userId
        ),
        payload = buildJsonObject {
            put("bookUuid", entity.bookUuid)
            put("locator", entity.locator)
            put("label", entity.label)
        }
    )

    fun fromBookmarkRecord(record: SyncRecord): BookmarkEntity {
        require(record.type == SyncType.BOOKMARK) { "Expected BOOKMARK, got ${record.type}" }
        val p = record.payload
        return BookmarkEntity(
            uuid = record.meta.uuid,
            bookUuid = p["bookUuid"]!!.jsonPrimitive.content,
            locator = p["locator"]!!.jsonPrimitive.content,
            label = nullableString(p, "label"),
            createdAt = record.meta.createdAt,
            updatedAt = record.meta.updatedAt,
            isDeleted = record.meta.isDeleted,
            syncedAt = record.meta.updatedAt,  // Oracle M2
            userId = record.meta.userId
        )
    }

    // ── Highlight ──

    fun toRecord(entity: HighlightEntity): SyncRecord = SyncRecord(
        type = SyncType.HIGHLIGHT,
        meta = SyncMeta(
            uuid = entity.uuid,
            isDeleted = entity.isDeleted,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            syncedAt = entity.syncedAt,
            userId = entity.userId
        ),
        payload = buildJsonObject {
            put("bookUuid", entity.bookUuid)
            put("locator", entity.locator)
            put("text", entity.text)
            put("color", entity.color)
        }
    )

    fun fromHighlightRecord(record: SyncRecord): HighlightEntity {
        require(record.type == SyncType.HIGHLIGHT) { "Expected HIGHLIGHT, got ${record.type}" }
        val p = record.payload
        return HighlightEntity(
            uuid = record.meta.uuid,
            bookUuid = p["bookUuid"]!!.jsonPrimitive.content,
            locator = p["locator"]!!.jsonPrimitive.content,
            text = p["text"]!!.jsonPrimitive.content,
            color = p["color"]!!.jsonPrimitive.content,
            createdAt = record.meta.createdAt,
            updatedAt = record.meta.updatedAt,
            isDeleted = record.meta.isDeleted,
            syncedAt = record.meta.updatedAt,  // Oracle M2
            userId = record.meta.userId
        )
    }

    // ── Note ──

    fun toRecord(entity: NoteEntity): SyncRecord = SyncRecord(
        type = SyncType.NOTE,
        meta = SyncMeta(
            uuid = entity.uuid,
            isDeleted = entity.isDeleted,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            syncedAt = entity.syncedAt,
            userId = entity.userId
        ),
        payload = buildJsonObject {
            put("bookUuid", entity.bookUuid)
            put("highlightUuid", entity.highlightUuid)
            put("locator", entity.locator)
            put("content", entity.content)
        }
    )

    fun fromNoteRecord(record: SyncRecord): NoteEntity {
        require(record.type == SyncType.NOTE) { "Expected NOTE, got ${record.type}" }
        val p = record.payload
        return NoteEntity(
            uuid = record.meta.uuid,
            bookUuid = p["bookUuid"]!!.jsonPrimitive.content,
            highlightUuid = nullableString(p, "highlightUuid"),
            locator = nullableString(p, "locator"),
            content = p["content"]!!.jsonPrimitive.content,
            createdAt = record.meta.createdAt,
            updatedAt = record.meta.updatedAt,
            isDeleted = record.meta.isDeleted,
            syncedAt = record.meta.updatedAt,  // Oracle M2
            userId = record.meta.userId
        )
    }

    companion object {
        private fun nullableString(obj: JsonObject, key: String): String? =
            obj[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }
    }
}
