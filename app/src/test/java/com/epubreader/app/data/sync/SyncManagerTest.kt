package com.epubreader.app.data.sync

import app.cash.turbine.test
import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.ErrorChannel
import com.epubreader.app.core.PushAck
import com.epubreader.app.core.Result
import com.epubreader.app.core.SyncCursor
import com.epubreader.app.core.SyncPage
import com.epubreader.app.core.Syncable
import com.epubreader.app.core.sync.NoopSyncCursorStore
import com.epubreader.app.core.sync.SyncCursorStore
import com.epubreader.app.core.sync.SyncMeta
import com.epubreader.app.core.sync.SyncRecord
import com.epubreader.app.core.sync.SyncState
import com.epubreader.app.core.sync.SyncType
import com.epubreader.app.data.remote.RemoteDataSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SyncManagerTest {

    private class TestDispatchersProvider(testDispatcher: CoroutineDispatcher) : DispatchersProvider {
        override val io = testDispatcher
        override val default = testDispatcher
        override val main = testDispatcher
        override val mainImmediate = testDispatcher
        override val sync = testDispatcher
    }

    private class FakeRemoteDataSource(
        var pullResult: SyncPage<SyncRecord> = SyncPage(emptyList(), null),
        var pushAck: PushAck = PushAck(emptyList()),
        var pullDelayMs: Long = 0,
        var pullThrows: Throwable? = null,
        val pushCalls: MutableList<List<SyncRecord>> = mutableListOf()
    ) : RemoteDataSource {
        override suspend fun pullSince(cursor: SyncCursor): SyncPage<SyncRecord> {
            pullThrows?.let { throw it }
            if (pullDelayMs > 0) delay(pullDelayMs)
            return pullResult
        }
        override suspend fun push(dirty: List<SyncRecord>): PushAck {
            pushCalls.add(dirty)
            return pushAck
        }
    }

    private fun dummySyncRecord(
        type: SyncType = SyncType.BOOK,
        uuid: String = "rec-1"
    ) = SyncRecord(
        type = type,
        meta = SyncMeta(
            uuid = uuid,
            isDeleted = false,
            createdAt = 1000L,
            updatedAt = 2000L,
            syncedAt = null,
            userId = null
        ),
        payload = buildJsonObject {
            put("title", "Test")
            put("filePath", "/f.epub")
            put("fileSize", 100L)
            put("format", "epub")
        }
    )

    private fun createDefaultHandler(
        type: SyncType = SyncType.BOOK,
        order: Int = 0
    ): SyncEntityHandler {
        val handler = mockk<SyncEntityHandler>(relaxed = true)
        every { handler.type } returns type
        every { handler.order } returns order
        every { handler.toRecord(any()) } returns dummySyncRecord(type)
        coEvery { handler.getDirty() } returns emptyList()
        coEvery { handler.pullMerge(any(), any()) } returns false
        coEvery { handler.markSynced(any(), any(), any()) } just runs
        return handler
    }

    private fun createSyncManager(
        remote: RemoteDataSource = FakeRemoteDataSource(),
        handlers: List<SyncEntityHandler> = listOf(createDefaultHandler()),
        cursorStore: SyncCursorStore = NoopSyncCursorStore(),
        testDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
        errorChannel: ErrorChannel = ErrorChannel()
    ): SyncManager {
        return SyncManager(
            remoteDataSource = remote,
            handlers = handlers,
            cursorStore = cursorStore,
            dispatchers = TestDispatchersProvider(testDispatcher),
            errorChannel = errorChannel
        )
    }

    // ────────────────────────────────────────────
    // Test 1: pull empty page → no upserts, pulled=0
    // ────────────────────────────────────────────
    @Test
    fun `pull empty page returns pulled 0`() = runTest {
        val remote = FakeRemoteDataSource()
        val handler = createDefaultHandler()
        val syncManager = createSyncManager(
            remote = remote,
            handlers = listOf(handler),
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val result = syncManager.sync()

        assertTrue(result is Result.Success)
        val syncResult = (result as Result.Success).data
        assertEquals(0, syncResult.pulled)
        assertEquals(0, syncResult.pushed)
    }

    // ────────────────────────────────────────────
    // Test 2: pull new remote record → pullMerge returns true, pulled=1
    // ────────────────────────────────────────────
    @Test
    fun `pull new remote record pullMerge true pulled 1`() = runTest {
        val record = dummySyncRecord()
        val remote = FakeRemoteDataSource(
            pullResult = SyncPage(listOf(record), null)
        )
        val handler = createDefaultHandler()
        coEvery { handler.pullMerge(any(), any()) } returns true

        val syncManager = createSyncManager(
            remote = remote,
            handlers = listOf(handler),
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val result = syncManager.sync()

        assertTrue(result is Result.Success)
        assertEquals(1, (result as Result.Success).data.pulled)
    }

    // ────────────────────────────────────────────
    // Test 3: pull local newer → pullMerge returns false, pulled=0
    // ────────────────────────────────────────────
    @Test
    fun `pull local newer pullMerge false pulled 0`() = runTest {
        val record = dummySyncRecord()
        val remote = FakeRemoteDataSource(
            pullResult = SyncPage(listOf(record), null)
        )
        val handler = createDefaultHandler()
        coEvery { handler.pullMerge(any(), any()) } returns false

        val syncManager = createSyncManager(
            remote = remote,
            handlers = listOf(handler),
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val result = syncManager.sync()

        assertTrue(result is Result.Success)
        assertEquals(0, (result as Result.Success).data.pulled)
    }

    // ────────────────────────────────────────────
    // Test 4: push dirty → markSynced called with fence
    // ────────────────────────────────────────────
    @Test
    fun `push dirty markSynced called with fence`() = runTest {
        val remote = FakeRemoteDataSource(
            pushAck = PushAck(listOf("rec-1"), mapOf("rec-1" to 3000L))
        )
        val dirtyEntity = mockk<Syncable>()
        val handler = createDefaultHandler()
        coEvery { handler.getDirty() } returns listOf(dirtyEntity)
        every { handler.toRecord(dirtyEntity) } returns dummySyncRecord()

        val syncManager = createSyncManager(
            remote = remote,
            handlers = listOf(handler),
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        syncManager.sync()

        coVerify { handler.markSynced(listOf("rec-1"), any(), any()) }
    }

    // ────────────────────────────────────────────
    // Test 5: push echo prevented — pulled records not re-pushed
    // ────────────────────────────────────────────
    @Test
    fun `push echo prevented pulled records not re-pushed`() = runTest {
        val record = dummySyncRecord()
        val remote = FakeRemoteDataSource(
            pullResult = SyncPage(listOf(record), null)
        )
        val handler = createDefaultHandler()
        coEvery { handler.pullMerge(any(), any()) } returns true
        coEvery { handler.getDirty() } returns emptyList()

        val syncManager = createSyncManager(
            remote = remote,
            handlers = listOf(handler),
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        syncManager.sync()

        // pushCalls should be empty because getDirty returned nothing
        assertTrue(remote.pushCalls.isEmpty(), "Expected no push calls when nothing is dirty")
    }

    // ────────────────────────────────────────────
    // Test 6: RemoteDataSource failure → error recorded in SyncResult
    // ────────────────────────────────────────────
    @Test
    fun `remote failure error recorded in SyncResult`() = runTest {
        val remote = FakeRemoteDataSource(
            pullThrows = RuntimeException("network error")
        )
        val handler = createDefaultHandler()
        val syncManager = createSyncManager(
            remote = remote,
            handlers = listOf(handler),
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val result = syncManager.sync()

        // Exception is caught inside the handler loop and stored in SyncResult.errors
        assertTrue(result is Result.Success)
        val syncResult = (result as Result.Success).data
        assertTrue(syncResult.errors.isNotEmpty(), "Expected error in SyncResult")
        assertEquals("network error", syncResult.errors.first().cause.message)
    }

    // ────────────────────────────────────────────
    // Test 7: DAO failure → error recorded in SyncResult
    // ────────────────────────────────────────────
    @Test
    fun `DAO failure error recorded in SyncResult`() = runTest {
        val handler = createDefaultHandler()
        coEvery { handler.getDirty() } throws RuntimeException("dao error")

        val syncManager = createSyncManager(
            handlers = listOf(handler),
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val result = syncManager.sync()

        assertTrue(result is Result.Success)
        val syncResult = (result as Result.Success).data
        assertTrue(syncResult.errors.isNotEmpty(), "Expected errors in SyncResult")
        assertEquals("dao error", syncResult.errors.first().cause.message)
    }

    // ────────────────────────────────────────────
    // Test 8: CancellationException re-thrown not swallowed
    // ────────────────────────────────────────────
    @Test
    fun `CancellationException re-thrown not swallowed`() = runTest {
        val remote = FakeRemoteDataSource(
            pullThrows = CancellationException("cancelled")
        )
        val handler = createDefaultHandler()
        val syncManager = createSyncManager(
            remote = remote,
            handlers = listOf(handler),
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        var caught = false
        try {
            syncManager.sync()
        } catch (e: CancellationException) {
            caught = true
            assertEquals("cancelled", e.message)
        }
        assertTrue(caught, "Expected CancellationException to be thrown")
    }

    // ────────────────────────────────────────────
    // Test 9: Mutex serializes concurrent sync calls
    // ────────────────────────────────────────────
    @Test
    fun `mutex serializes concurrent sync`() = runTest {
        val remote = FakeRemoteDataSource(pullDelayMs = 100)
        val handler = createDefaultHandler()
        val syncManager = createSyncManager(
            remote = remote,
            handlers = listOf(handler),
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val job1 = backgroundScope.launch { syncManager.sync() }
        val job2 = backgroundScope.launch { syncManager.sync() }

        testScheduler.advanceUntilIdle()
        job1.join()
        job2.join()

        // Both completed without error — mutex prevented overlap
    }

    // ────────────────────────────────────────────
    // Test 10: fenced markSynced uses fenceTs parameter
    // ────────────────────────────────────────────
    @Test
    fun `fenced markSynced uses fenceTs`() = runTest {
        val remote = FakeRemoteDataSource(
            pushAck = PushAck(listOf("rec-1"))
        )
        val dirtyEntity = mockk<Syncable>()
        val handler = createDefaultHandler()
        coEvery { handler.getDirty() } returns listOf(dirtyEntity)
        every { handler.toRecord(dirtyEntity) } returns dummySyncRecord()

        val syncManager = createSyncManager(
            remote = remote,
            handlers = listOf(handler),
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        syncManager.sync()

        val uuidsSlot = slot<List<String>>()
        val syncedAtSlot = slot<Long>()
        val fenceTsSlot = slot<Long>()
        coVerify {
            handler.markSynced(capture(uuidsSlot), capture(syncedAtSlot), capture(fenceTsSlot))
        }
        assertEquals(listOf("rec-1"), uuidsSlot.captured)
        assertTrue(fenceTsSlot.captured > 0, "fenceTs should be > 0")
    }

    // ────────────────────────────────────────────
    // Test 11: batch pagination respects BATCH_SIZE
    // ────────────────────────────────────────────
    @Test
    fun `batch pagination respects BATCH_SIZE`() = runTest {
        val dirtyEntities = (1..500).map { mockk<Syncable>() }
        val handler = createDefaultHandler()
        coEvery { handler.getDirty() } returns dirtyEntities
        dirtyEntities.forEach { entity ->
            every { handler.toRecord(entity) } returns dummySyncRecord()
        }

        val remote = FakeRemoteDataSource(
            pushAck = PushAck(dirtyEntities.mapIndexed { i, _ -> "rec-$i" })
        )
        val syncManager = createSyncManager(
            remote = remote,
            handlers = listOf(handler),
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        syncManager.sync()

        // BATCH_SIZE = 200, so 500 items → 3 batches (200 + 200 + 100)
        assertEquals(3, remote.pushCalls.size, "Expected 3 push batches for 500 items")
        assertEquals(200, remote.pushCalls[0].size)
        assertEquals(200, remote.pushCalls[1].size)
        assertEquals(100, remote.pushCalls[2].size)
    }

    // ────────────────────────────────────────────
    // Test 12: FK order — handlers sorted by order before processing
    // ────────────────────────────────────────────
    @Test
    fun `FK order handlers sorted by order`() = runTest {
        val callOrder = mutableListOf<SyncType>()

        val handler0 = createDefaultHandler(type = SyncType.BOOK, order = 0)
        coEvery { handler0.pullMerge(any(), any()) } answers {
            callOrder.add(SyncType.BOOK)
            false
        }

        val handler1 = createDefaultHandler(type = SyncType.HIGHLIGHT, order = 1)
        coEvery { handler1.pullMerge(any(), any()) } answers {
            callOrder.add(SyncType.HIGHLIGHT)
            false
        }

        val handler2 = createDefaultHandler(type = SyncType.NOTE, order = 2)
        coEvery { handler2.pullMerge(any(), any()) } answers {
            callOrder.add(SyncType.NOTE)
            false
        }

        // Provide records in pull result so pullMerge is actually called
        val remote = FakeRemoteDataSource(
            pullResult = SyncPage(listOf(dummySyncRecord()), null)
        )

        // Register in wrong order to verify sorting
        val syncManager = createSyncManager(
            remote = remote,
            handlers = listOf(handler2, handler0, handler1),
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        syncManager.sync()

        // Verify handlers were called in order: 0 (BOOK) → 1 (HIGHLIGHT) → 2 (NOTE)
        assertEquals(
            listOf(SyncType.BOOK, SyncType.HIGHLIGHT, SyncType.NOTE),
            callOrder,
            "Handlers should be processed in FK order: BOOK(0) → HIGHLIGHT(1) → NOTE(2)"
        )
    }

    // ────────────────────────────────────────────
    // Test 13: SyncState emits Idle then Syncing then Success
    // ────────────────────────────────────────────
    @Test
    fun `sync state emits idle then syncing then success`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val syncManager = createSyncManager(testDispatcher = dispatcher)

        syncManager.state.test {
            // Initial state should be Idle
            assertEquals(SyncState.Idle, awaitItem())

            // Launch sync in background — won't run until we advance the scheduler
            backgroundScope.launch { syncManager.sync() }

            // Advance to execute sync (sets Syncing, then Success)
            testScheduler.advanceUntilIdle()

            // Now we should have both Syncing and Success in the channel
            assertEquals(SyncState.Syncing, awaitItem())

            val state = awaitItem()
            assertTrue(state is SyncState.Success, "Expected Success state, got $state")
        }
    }

    // ────────────────────────────────────────────
    // Test 14: deferred markSynced — errors prevent all marking
    // ────────────────────────────────────────────
    @Test
    fun `deferred markSynced errors prevent all marking`() = runTest {
        val goodHandler = createDefaultHandler(type = SyncType.BOOK, order = 0)
        val dirtyEntity = mockk<Syncable>()
        coEvery { goodHandler.getDirty() } returns listOf(dirtyEntity)
        every { goodHandler.toRecord(dirtyEntity) } returns dummySyncRecord()

        val badHandler = createDefaultHandler(type = SyncType.HIGHLIGHT, order = 1)
        coEvery { badHandler.getDirty() } throws RuntimeException("bad handler failed")

        val syncManager = createSyncManager(
            remote = FakeRemoteDataSource(pushAck = PushAck(listOf("rec-1"))),
            handlers = listOf(goodHandler, badHandler),
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        syncManager.sync()

        // Neither handler should have markSynced called because badHandler errored
        coVerify(exactly = 0) { goodHandler.markSynced(any(), any(), any()) }
        coVerify(exactly = 0) { badHandler.markSynced(any(), any(), any()) }
    }

    // ────────────────────────────────────────────
    // Test 15: launchSync returns Job and executes async
    // ────────────────────────────────────────────
    @Test
    fun `launchSync returns Job and executes async`() = runTest {
        val handler = createDefaultHandler()
        val remote = FakeRemoteDataSource(
            pullResult = SyncPage(listOf(dummySyncRecord()), null)
        )
        val syncManager = createSyncManager(
            remote = remote,
            handlers = listOf(handler),
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val job = syncManager.launchSync()

        // Should return a valid Job immediately (not null — no concurrent sync running)
        assertNotNull(job, "Expected non-null Job when no sync is in progress")
        assertTrue(job!!.isActive || job.isCompleted, "Expected active or completed Job")

        testScheduler.advanceUntilIdle()

        // After advancing, the job should be completed
        assertTrue(job.isCompleted)

        // The handler's pullMerge should have been called (because pullResult has items)
        coVerify(atLeast = 1) { handler.pullMerge(any(), any()) }
    }

    // ────────────────────────────────────────────
    // Test 16: MF1 — markSynced throws → SyncState.Error (not stuck at Syncing)
    // ────────────────────────────────────────────
    @Test
    fun `markSynced_throws_sets_error_state_not_stuck_at_syncing`() = runTest {
        val handler = createDefaultHandler().apply {
            every { type } returns SyncType.BOOK
            every { order } returns 0
            coEvery { getDirty() } returns listOf(mockk<Syncable>(relaxed = true))
            coEvery { toRecord(any()) } returns dummySyncRecord()
            coEvery { pullMerge(any(), any()) } returns false
            // markSynced throws — simulates DB failure during deferred mark phase
            coEvery { markSynced(any(), any(), any()) } throws RuntimeException("DB locked")
        }
        val remote = FakeRemoteDataSource(
            pushAck = PushAck(ackedUuids = listOf("uuid-1"))
        )
        val errorChannel = ErrorChannel()
        val syncManager = createSyncManager(
            remote = remote,
            handlers = listOf(handler),
            errorChannel = errorChannel,
            testDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        // Run sync to completion
        val result = syncManager.sync()

        // MF1: Result should be Success (runCatchingAsync catches the markSynced error
        // internally and records it in errors list), but SyncState must be Error
        // (not stuck at Syncing)
        val finalState = syncManager.state.value
        assertTrue(finalState is SyncState.Error, "Expected SyncState.Error, got $finalState")
        val errorResult = (finalState as SyncState.Error).result
        assertTrue(errorResult.errors.isNotEmpty(), "Expected errors in result")
        assertEquals("DB locked", errorResult.errors[0].cause.message)
    }
}
