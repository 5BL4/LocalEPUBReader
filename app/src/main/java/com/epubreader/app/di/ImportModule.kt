package com.epubreader.app.di

import com.epubreader.app.data.bookimport.BookImporter
import com.epubreader.app.data.bookimport.EpubBookImporter
import com.epubreader.app.data.bookimport.MetadataParser
import com.epubreader.app.data.bookimport.ReadiumMetadataParser
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ImportModule {

    @Binds @Singleton
    abstract fun bindBookImporter(impl: EpubBookImporter): BookImporter

    @Binds @Singleton
    abstract fun bindMetadataParser(impl: ReadiumMetadataParser): MetadataParser
}
