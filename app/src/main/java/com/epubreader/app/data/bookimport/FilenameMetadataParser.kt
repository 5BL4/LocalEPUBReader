package com.epubreader.app.data.bookimport

import com.epubreader.app.core.Result
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FilenameMetadataParser @Inject constructor() : MetadataParser {
    override suspend fun parse(filePath: String): Result<BookMetadata> = Result.runCatching {
        val file = File(filePath)
        val name = file.nameWithoutExtension
        // Replace underscores and hyphens with spaces, capitalize first letter
        val title = name.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        BookMetadata(title = title, author = null, coverPath = null)
    }
}
