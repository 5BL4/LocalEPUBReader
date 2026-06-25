package com.epubreader.app.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SyncRecordTest {

    // ── Test 1: SyncType enum has 5 values in FK order ──
    @Test
    fun `SyncType enum has 5 values in FK order`() {
        val values = SyncType.entries
        assertEquals(5, values.size)
        assertEquals(SyncType.BOOK, values[0])
        assertEquals(SyncType.READING_PROGRESS, values[1])
        assertEquals(SyncType.BOOKMARK, values[2])
        assertEquals(SyncType.HIGHLIGHT, values[3])
        assertEquals(SyncType.NOTE, values[4])
    }

    // ── Test 2: SyncMeta default values (syncedAt null, userId null) ──
    @Test
    fun `SyncMeta default values`() {
        val meta = SyncMeta(
            uuid = "test-uuid",
            isDeleted = false,
            createdAt = 1000L,
            updatedAt = 2000L
        )
        assertEquals("test-uuid", meta.uuid)
        assertNull(meta.syncedAt)
        assertNull(meta.userId)
    }

    // ── Test 3: SyncRecord construction with all fields ──
    @Test
    fun `SyncRecord construction with all fields`() {
        val payload = buildJsonObject { put("key", "value") }
        val meta = SyncMeta(
            uuid = "r1",
            isDeleted = false,
            createdAt = 1000L,
            updatedAt = 2000L,
            syncedAt = 1500L,
            userId = "user1"
        )
        val record = SyncRecord(
            type = SyncType.BOOK,
            meta = meta,
            payload = payload
        )
        assertEquals(SyncType.BOOK, record.type)
        assertEquals("r1", record.meta.uuid)
        assertEquals("user1", record.meta.userId)
        assertEquals(payload, record.payload)
    }

    // ── Test 4: SyncRecord is @Serializable — can be serialized/deserialized ──
    @Test
    fun `SyncRecord is serializable round-trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val originalMeta = SyncMeta(
            uuid = "s1",
            isDeleted = false,
            createdAt = 1000L,
            updatedAt = 2000L,
            syncedAt = 1500L,
            userId = "user1"
        )
        val originalPayload = buildJsonObject { put("title", "Test Book") }
        val original = SyncRecord(
            type = SyncType.BOOK,
            meta = originalMeta,
            payload = originalPayload
        )

        val serialized = json.encodeToString(SyncRecord.serializer(), original)
        val deserialized = json.decodeFromString(SyncRecord.serializer(), serialized)

        assertEquals(original.type, deserialized.type)
        assertEquals(original.meta.uuid, deserialized.meta.uuid)
        assertEquals(original.meta.isDeleted, deserialized.meta.isDeleted)
        assertEquals(original.meta.createdAt, deserialized.meta.createdAt)
        assertEquals(original.meta.updatedAt, deserialized.meta.updatedAt)
        assertEquals(original.meta.syncedAt, deserialized.meta.syncedAt)
        assertEquals(original.meta.userId, deserialized.meta.userId)
        assertNotNull(deserialized.payload)
    }

    // ── Test 5: SyncMeta maps to Syncable fields correctly ──
    @Test
    fun `SyncMeta maps to Syncable fields`() {
        val meta = SyncMeta(
            uuid = "m1",
            isDeleted = true,
            createdAt = 3000L,
            updatedAt = 4000L,
            syncedAt = 3500L,
            userId = "user2"
        )
        // SyncMeta mirrors all Syncable interface fields
        assertEquals("m1", meta.uuid)
        assertEquals(true, meta.isDeleted)
        assertEquals(3000L, meta.createdAt)
        assertEquals(4000L, meta.updatedAt)
        assertEquals(3500L, meta.syncedAt)
        assertEquals("user2", meta.userId)
    }
}
