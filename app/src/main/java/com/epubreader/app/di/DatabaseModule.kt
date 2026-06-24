package com.epubreader.app.di

import android.content.Context
import androidx.room.Room
import com.epubreader.app.data.local.AppDatabase
import com.epubreader.app.data.local.dao.BookDao
import com.epubreader.app.data.local.dao.BookmarkDao
import com.epubreader.app.data.local.dao.HighlightDao
import com.epubreader.app.data.local.dao.NoteDao
import com.epubreader.app.data.local.dao.ReadingProgressDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "epubreader.db").build()

    @Provides fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()
    @Provides fun provideReadingProgressDao(db: AppDatabase): ReadingProgressDao = db.readingProgressDao()
    @Provides fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun provideHighlightDao(db: AppDatabase): HighlightDao = db.highlightDao()
    @Provides fun provideNoteDao(db: AppDatabase): NoteDao = db.noteDao()
}
