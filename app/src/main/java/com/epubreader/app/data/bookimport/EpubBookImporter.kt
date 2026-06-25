package com.epubreader.app.data.bookimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.Result
import com.epubreader.app.core.getOrThrow
import com.epubreader.app.data.local.entity.BookEntity
import com.epubreader.app.domain.repository.BookRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class EpubBookImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val metadataParser: MetadataParser,
    private val dispatchers: DispatchersProvider
) : BookImporter {

    companion object {
        private const val BUFFER_SIZE = 16 * 1024 // 16KB
        private const val BOOKS_DIR = "books"
    }

    override suspend fun importBook(
        uri: Uri,
        onProgress: (Float) -> Unit
    ): Result<BookEntity> = withContext(dispatchers.io) {
        var targetFile: File? = null
        var success = false
        try {
            // Step 1: Query URI for size and display name
            val uriInfo = queryUriInfo(uri)
            val declaredSize = uriInfo.size // may be null (M4)

            // Step 2: Prepare target directory
            val targetDir = File(context.filesDir, BOOKS_DIR).apply { mkdirs() }

            // Step 3: Storage validation (NEVER #27) — only if size is known (M4)
            if (declaredSize != null && declaredSize > 0) {
                val usable = targetDir.usableSpace
                if (usable < declaredSize) {
                    return@withContext Result.Error(
                        InsufficientStorageException(
                            requiredBytes = declaredSize,
                            availableBytes = usable
                        )
                    )
                }
            }

            // Step 4: Generate UUID BEFORE copy (M3 — prevents same-name collision)
            val uuid = UUID.randomUUID().toString()
            targetFile = File(targetDir, "$uuid.epub")

            // Step 5: Copy with NIO + yield (NEVER #24)
            val actualSize = copyFileWithNio(uri, targetFile, declaredSize, onProgress)

            // Step 6: Parse metadata
            val metadata = metadataParser.parse(targetFile.absolutePath).getOrThrow()

            // Step 7: Create BookEntity and save to Room
            val now = System.currentTimeMillis()
            val book = BookEntity(
                uuid = uuid,
                title = metadata.title,
                author = metadata.author,
                coverPath = metadata.coverPath,
                filePath = targetFile.absolutePath,
                fileSize = actualSize,
                format = "epub",
                createdAt = now,
                updatedAt = now,
                isDeleted = false
            )
            bookRepository.addBook(book).getOrThrow()

            success = true
            Result.Success(book)
        } catch (e: CancellationException) {
            // MUST catch CancellationException BEFORE Exception (M2)
            // Re-throw to preserve structured concurrency — do NOT swallow
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        } finally {
            // Dirty cleanup (NEVER #27) — delete partial file on any failure
            if (!success) {
                targetFile?.delete()
            }
        }
    }

    /**
     * Queries ContentResolver for file size and display name.
     * Returns null size if the provider doesn't report OpenableColumns.SIZE (M4).
     */
    private fun queryUriInfo(uri: Uri): UriInfo {
        var size: Long? = null
        var displayName: String? = null
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    size = cursor.getLong(sizeIdx)
                }
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                    displayName = cursor.getString(nameIdx)
                }
            }
        }
        return UriInfo(size, displayName)
    }

    /**
     * Copies file using NIO FileChannel.write(ByteBuffer) with 16KB buffer + yield().
     * Returns actual bytes copied (used for fileSize when declared size is unknown).
     */
    private suspend fun copyFileWithNio(
        uri: Uri,
        target: File,
        declaredSize: Long?,
        onProgress: (Float) -> Unit
    ): Long {
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        var copied = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).channel.use { channel ->
                while (true) {
                    buffer.clear()
                    val read = input.read(buffer.array(), 0, BUFFER_SIZE)
                    if (read == -1) break
                    buffer.limit(read)
                    channel.write(buffer)
                    copied += read
                    // Report progress — only if we know the total size
                    if (declaredSize != null && declaredSize > 0) {
                        onProgress(copied.toFloat() / declaredSize)
                    } else {
                        // Unknown total — report indeterminate progress as -1f
                        onProgress(-1f)
                    }
                    yield() // NEVER #24 — respond to cancellation
                }
            }
        } ?: throw IOException("Cannot open input stream for URI: $uri")
        return copied
    }

    private data class UriInfo(val size: Long?, val displayName: String?)
}
