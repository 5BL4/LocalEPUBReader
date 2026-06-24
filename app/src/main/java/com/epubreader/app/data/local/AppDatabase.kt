package com.epubreader.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.epubreader.app.data.local.converter.Converters
import com.epubreader.app.data.local.dao.BookDao
import com.epubreader.app.data.local.dao.BookmarkDao
import com.epubreader.app.data.local.dao.HighlightDao
import com.epubreader.app.data.local.dao.NoteDao
import com.epubreader.app.data.local.dao.ReadingProgressDao
import com.epubreader.app.data.local.entity.BookEntity
import com.epubreader.app.data.local.entity.BookmarkEntity
import com.epubreader.app.data.local.entity.HighlightEntity
import com.epubreader.app.data.local.entity.NoteEntity
import com.epubreader.app.data.local.entity.ReadingProgressEntity

@Database(
    entities = [
        BookEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        HighlightEntity::class,
        NoteEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao
    abstract fun noteDao(): NoteDao
}
