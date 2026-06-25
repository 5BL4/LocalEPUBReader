package com.epubreader.app.data.bookimport

import android.net.Uri
import com.epubreader.app.core.Result
import com.epubreader.app.data.local.entity.BookEntity

interface BookImporter {
    /**
     * Imports an EPUB from a SAF URI.
     * @param uri SAF content URI
     * @param onProgress callback receiving progress 0f..1f. 
     *   WARNING: invoked on IO dispatcher thread. MutableStateFlow.value is thread-safe.
     *   Do NOT wrap in withContext(Main) — that would cause thousands of dispatcher hops.
     */
    suspend fun importBook(uri: Uri, onProgress: (Float) -> Unit): Result<BookEntity>
}
