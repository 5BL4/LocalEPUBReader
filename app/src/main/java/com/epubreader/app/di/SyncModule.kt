package com.epubreader.app.di

import com.epubreader.app.core.sync.NoopSyncCursorStore
import com.epubreader.app.core.sync.SyncCursorStore
import com.epubreader.app.data.local.dao.BookDao
import com.epubreader.app.data.local.dao.BookmarkDao
import com.epubreader.app.data.local.dao.HighlightDao
import com.epubreader.app.data.local.dao.NoteDao
import com.epubreader.app.data.local.dao.ReadingProgressDao
import com.epubreader.app.data.sync.BookDaoSyncAdapter
import com.epubreader.app.data.sync.BookSyncHandler
import com.epubreader.app.data.sync.BookmarkDaoSyncAdapter
import com.epubreader.app.data.sync.BookmarkSyncHandler
import com.epubreader.app.data.sync.HighlightDaoSyncAdapter
import com.epubreader.app.data.sync.HighlightSyncHandler
import com.epubreader.app.data.sync.NoteDaoSyncAdapter
import com.epubreader.app.data.sync.NoteSyncHandler
import com.epubreader.app.data.sync.ReadingProgressDaoSyncAdapter
import com.epubreader.app.data.sync.ReadingProgressSyncHandler
import com.epubreader.app.data.sync.SyncEntityHandler
import com.epubreader.app.data.sync.SyncRecordMapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideBookDaoSyncAdapter(dao: BookDao) = BookDaoSyncAdapter(dao)

    @Provides
    @Singleton
    fun provideReadingProgressDaoSyncAdapter(dao: ReadingProgressDao) = ReadingProgressDaoSyncAdapter(dao)

    @Provides
    @Singleton
    fun provideBookmarkDaoSyncAdapter(dao: BookmarkDao) = BookmarkDaoSyncAdapter(dao)

    @Provides
    @Singleton
    fun provideHighlightDaoSyncAdapter(dao: HighlightDao) = HighlightDaoSyncAdapter(dao)

    @Provides
    @Singleton
    fun provideNoteDaoSyncAdapter(dao: NoteDao) = NoteDaoSyncAdapter(dao)

    @Provides
    @Singleton
    fun provideSyncRecordMapper() = SyncRecordMapper()

    @Provides
    @Singleton
    fun provideSyncHandlers(
        mapper: SyncRecordMapper,
        bookAdapter: BookDaoSyncAdapter,
        progressAdapter: ReadingProgressDaoSyncAdapter,
        bookmarkAdapter: BookmarkDaoSyncAdapter,
        highlightAdapter: HighlightDaoSyncAdapter,
        noteAdapter: NoteDaoSyncAdapter
    ): List<@JvmSuppressWildcards SyncEntityHandler> = listOf(
        BookSyncHandler(mapper, bookAdapter),
        ReadingProgressSyncHandler(mapper, progressAdapter),
        BookmarkSyncHandler(mapper, bookmarkAdapter),
        HighlightSyncHandler(mapper, highlightAdapter),
        NoteSyncHandler(mapper, noteAdapter)
    )

    @Provides
    @Singleton
    fun provideSyncCursorStore(): SyncCursorStore = NoopSyncCursorStore()
}
