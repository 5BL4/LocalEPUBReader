package com.epubreader.app.core.sync

import com.epubreader.app.core.SyncCursor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SyncCursorStoreTest {

    private val store = NoopSyncCursorStore()

    // ── Test 1: NoopSyncCursorStore.getCursor returns null for all types ──
    @Test
    fun `NoopSyncCursorStore getCursor returns null for all types`() {
        SyncType.entries.forEach { type ->
            assertNull(store.getCursor(type), "Expected null cursor for $type")
        }
    }

    // ── Test 2: NoopSyncCursorStore.saveCursor is a no-op (no exception) ──
    @Test
    fun `NoopSyncCursorStore saveCursor is no-op no exception`() = runTest {
        store.saveCursor(SyncType.BOOK, SyncCursor(updatedAt = 1000L))
        store.saveCursor(SyncType.HIGHLIGHT, null)
        // No exception = test passes
    }

    // ── Test 3: getCursor after saveCursor still returns null (noop doesn't persist) ──
    @Test
    fun `getCursor after saveCursor still returns null`() = runTest {
        store.saveCursor(SyncType.BOOK, SyncCursor(updatedAt = 1000L))
        val cursor = store.getCursor(SyncType.BOOK)
        assertNull(cursor, "Noop cursor store should not persist")
    }
}
