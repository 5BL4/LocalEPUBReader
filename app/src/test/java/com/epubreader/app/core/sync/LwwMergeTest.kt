package com.epubreader.app.core.sync

import com.epubreader.app.core.Syncable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LwwMergeTest {

    private data class TestSyncable(
        override val uuid: String,
        override val isDeleted: Boolean,
        override val createdAt: Long,
        override val updatedAt: Long,
        override val syncedAt: Long?
    ) : Syncable

    private fun record(
        uuid: String = "a",
        isDeleted: Boolean = false,
        createdAt: Long = 0L,
        updatedAt: Long = 0L,
        syncedAt: Long? = null
    ) = TestSyncable(uuid, isDeleted, createdAt, updatedAt, syncedAt)

    // ── Test 1: local null → upsert remote ──
    @Test
    fun `local null → upsert remote`() {
        val remote = record(uuid = "r1", updatedAt = 100L)
        val result = lwwMerge(remote, null, 0L)
        assertTrue(result is MergeDecision.Upsert)
        assertEquals(remote, (result as MergeDecision.Upsert).record)
    }

    // ── Test 2: remote newer → upsert remote ──
    @Test
    fun `remote newer → upsert remote`() {
        val remote = record(uuid = "r2", updatedAt = 200L)
        val local = record(uuid = "r2", updatedAt = 100L)
        val result = lwwMerge(remote, local, 0L)
        assertTrue(result is MergeDecision.Upsert)
        assertEquals(remote, (result as MergeDecision.Upsert).record)
    }

    // ── Test 3: local newer → skip ──
    @Test
    fun `local newer → skip`() {
        val remote = record(uuid = "r3", updatedAt = 100L)
        val local = record(uuid = "r3", updatedAt = 200L)
        val result = lwwMerge(remote, local, 0L)
        assertTrue(result is MergeDecision.Skip)
    }

    // ── Test 4: remote deleted, remote newer → upsert remote ──
    @Test
    fun `remote deleted remote newer → upsert remote`() {
        val remote = record(uuid = "r4", updatedAt = 200L, isDeleted = true)
        val local = record(uuid = "r4", updatedAt = 100L)
        val result = lwwMerge(remote, local, 0L)
        assertTrue(result is MergeDecision.Upsert)
        assertEquals(remote, (result as MergeDecision.Upsert).record)
    }

    // ── Test 5: remote deleted, local newer → skip (Oracle M1 fix) ──
    @Test
    fun `remote deleted local newer → skip`() {
        val remote = record(uuid = "r5", updatedAt = 100L, isDeleted = true)
        val local = record(uuid = "r5", updatedAt = 200L)
        val result = lwwMerge(remote, local, 0L)
        assertTrue(result is MergeDecision.Skip)
    }

    // ── Test 6: local deleted, remote newer → upsert remote (resurrect) ──
    @Test
    fun `local deleted remote newer → upsert remote`() {
        val remote = record(uuid = "r6", updatedAt = 200L)
        val local = record(uuid = "r6", updatedAt = 100L, isDeleted = true)
        val result = lwwMerge(remote, local, 0L)
        assertTrue(result is MergeDecision.Upsert)
        assertEquals(remote, (result as MergeDecision.Upsert).record)
    }

    // ── Test 7: local deleted, remote older → skip ──
    @Test
    fun `local deleted remote older → skip`() {
        val remote = record(uuid = "r7", updatedAt = 100L)
        val local = record(uuid = "r7", updatedAt = 200L, isDeleted = true)
        val result = lwwMerge(remote, local, 0L)
        assertTrue(result is MergeDecision.Skip)
    }

    // ── Test 8: tie: both active, remote.uuid > local.uuid → upsert remote ──
    @Test
    fun `tie both active remote uuid greater → upsert remote`() {
        val remote = record(uuid = "b", updatedAt = 100L)
        val local = record(uuid = "a", updatedAt = 100L)
        val result = lwwMerge(remote, local, 0L)
        assertTrue(result is MergeDecision.Upsert)
        assertEquals(remote, (result as MergeDecision.Upsert).record)
    }

    // ── Test 9: tie: both active, remote.uuid < local.uuid → skip ──
    @Test
    fun `tie both active remote uuid lesser → skip`() {
        val remote = record(uuid = "a", updatedAt = 100L)
        val local = record(uuid = "b", updatedAt = 100L)
        val result = lwwMerge(remote, local, 0L)
        assertTrue(result is MergeDecision.Skip)
    }

    // ── Test 10: tie: remote deleted → upsert remote ──
    @Test
    fun `tie remote deleted → upsert remote`() {
        val remote = record(uuid = "d", updatedAt = 100L, isDeleted = true)
        val local = record(uuid = "d", updatedAt = 100L)
        val result = lwwMerge(remote, local, 0L)
        assertTrue(result is MergeDecision.Upsert)
        assertEquals(remote, (result as MergeDecision.Upsert).record)
    }

    // ── Test 11: tie: local deleted, remote active → skip ──
    @Test
    fun `tie local deleted remote active → skip`() {
        val remote = record(uuid = "e", updatedAt = 100L)
        val local = record(uuid = "e", updatedAt = 100L, isDeleted = true)
        val result = lwwMerge(remote, local, 0L)
        assertTrue(result is MergeDecision.Skip)
    }

    // ── Test 12: tie: both deleted → upsert remote ──
    @Test
    fun `tie both deleted → upsert remote`() {
        val remote = record(uuid = "f", updatedAt = 100L, isDeleted = true)
        val local = record(uuid = "f", updatedAt = 100L, isDeleted = true)
        val result = lwwMerge(remote, local, 0L)
        assertTrue(result is MergeDecision.Upsert)
        assertEquals(remote, (result as MergeDecision.Upsert).record)
    }

    // ── Test 13: clock skew: remote far in future → clamped, then compared ──
    @Test
    fun `clock skew remote far future → clamped to now then upsert when clamped still newer`() {
        val nowMs = 1000L
        // remote.updatedAt = now + 120000 (> now + 60000 SKEW_TOLERANCE), so clamped to 1000
        val remote = record(uuid = "g", updatedAt = nowMs + 120_000L)
        val local = record(uuid = "g", updatedAt = 500L)
        // After clamping: remote=1000 > local=500 → Upsert
        val result = lwwMerge(remote, local, nowMs)
        assertTrue(result is MergeDecision.Upsert)
        assertEquals(remote, (result as MergeDecision.Upsert).record)
    }

    // ── Test 14: clock skew: after clamping, remote still newer → upsert ──
    @Test
    fun `clock skew after clamping remote still newer → upsert`() {
        val nowMs = 1000L
        val remote = record(uuid = "h", updatedAt = nowMs + 120_000L)
        val local = record(uuid = "h", updatedAt = 300L)
        // After clamping: remote=1000 > local=300 → Upsert
        val result = lwwMerge(remote, local, nowMs)
        assertTrue(result is MergeDecision.Upsert)
    }

    // ── Test 15: clock skew: after clamping, local newer → skip ──
    @Test
    fun `clock skew after clamping local newer → skip`() {
        val nowMs = 1000L
        val remote = record(uuid = "i", updatedAt = nowMs + 120_000L)
        val local = record(uuid = "i", updatedAt = 50_000L)
        // After clamping: remote=1000 < local=50000 → Skip
        val result = lwwMerge(remote, local, nowMs)
        assertTrue(result is MergeDecision.Skip)
    }

    // ── Test 16: Upsert decision carries the record as-is (caller sets syncedAt) ──
    @Test
    fun `upsert decision carries record as-is caller sets syncedAt`() {
        val remote = record(uuid = "j", updatedAt = 200L, syncedAt = null)
        val local = record(uuid = "j", updatedAt = 100L, syncedAt = 50L)
        val result = lwwMerge(remote, local, 0L)
        assertTrue(result is MergeDecision.Upsert)
        val record = (result as MergeDecision.Upsert).record
        // The merge function returns the remote record as-is; syncedAt is unmodified
        assertEquals(null, record.syncedAt)
        assertEquals("j", record.uuid)
        assertEquals(200L, record.updatedAt)
    }

    // ── Test 17: MergeDecision.Upsert holds the correct record ──
    @Test
    fun `MergeDecision Upsert holds the correct record`() {
        val remote = record(uuid = "k", updatedAt = 300L, isDeleted = true)
        val decision = MergeDecision.Upsert(remote)
        assertEquals(remote, decision.record)
        assertTrue(decision.record.isDeleted)
        assertEquals("k", decision.record.uuid)
    }

    // ── Test 18: MergeDecision.Skip is a singleton/object ──
    @Test
    fun `MergeDecision Skip is a singleton object`() {
        val skip1 = MergeDecision.Skip
        val skip2 = MergeDecision.Skip
        assertTrue(skip1 === skip2, "Skip should be the same object reference (singleton)")
    }
}
