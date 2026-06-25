package com.epubreader.app.di

import com.epubreader.app.core.AndroidStringProvider
import com.epubreader.app.core.DefaultDispatchersProvider
import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.StringProvider
import com.epubreader.app.data.prefs.PreferencesRepository
import com.epubreader.app.data.prefs.PreferencesRepositoryImpl
import com.epubreader.app.data.remote.NoopRemoteDataSource
import com.epubreader.app.data.remote.RemoteDataSource
import com.epubreader.app.core.export.MarkdownExporter
import com.epubreader.app.core.export.MarkdownExporterImpl
import com.epubreader.app.data.repo.BookmarkRepositoryImpl
import com.epubreader.app.data.repo.BookRepositoryImpl
import com.epubreader.app.data.repo.HighlightRepositoryImpl
import com.epubreader.app.data.repo.NoteRepositoryImpl
import com.epubreader.app.data.repo.ReadingProgressRepositoryImpl
import com.epubreader.app.domain.repository.BookmarkRepository
import com.epubreader.app.domain.repository.BookRepository
import com.epubreader.app.domain.repository.HighlightRepository
import com.epubreader.app.domain.repository.NoteRepository
import com.epubreader.app.domain.repository.ReadingProgressRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {

    @Binds @Singleton
    abstract fun bindDispatchersProvider(impl: DefaultDispatchersProvider): DispatchersProvider

    @Binds @Singleton
    abstract fun bindStringProvider(impl: AndroidStringProvider): StringProvider

    @Binds @Singleton
    abstract fun bindRemoteDataSource(impl: NoopRemoteDataSource): RemoteDataSource

    @Binds @Singleton
    abstract fun bindPreferencesRepository(impl: PreferencesRepositoryImpl): PreferencesRepository

    @Binds @Singleton
    abstract fun bindBookRepository(impl: BookRepositoryImpl): BookRepository

    @Binds @Singleton
    abstract fun bindReadingProgressRepository(impl: ReadingProgressRepositoryImpl): ReadingProgressRepository

    @Binds @Singleton
    abstract fun bindBookmarkRepository(impl: BookmarkRepositoryImpl): BookmarkRepository

    @Binds @Singleton
    abstract fun bindHighlightRepository(impl: HighlightRepositoryImpl): HighlightRepository

    @Binds @Singleton
    abstract fun bindNoteRepository(impl: NoteRepositoryImpl): NoteRepository

    @Binds
    @Singleton
    abstract fun bindMarkdownExporter(impl: MarkdownExporterImpl): MarkdownExporter
}
