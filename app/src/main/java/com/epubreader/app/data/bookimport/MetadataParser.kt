package com.epubreader.app.data.bookimport

import com.epubreader.app.core.Result

interface MetadataParser {
    suspend fun parse(filePath: String): Result<BookMetadata>
}
