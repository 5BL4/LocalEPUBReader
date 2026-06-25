package com.epubreader.app.data.bookimport

import android.content.Context
import android.graphics.Bitmap
import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.streamer.PublicationOpener
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadiumMetadataParser @Inject constructor(
    private val publicationOpener: PublicationOpener,
    private val assetRetriever: AssetRetriever,
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatchersProvider
) : MetadataParser {

    companion object {
        private const val COVERS_DIR = "covers"
    }

    override suspend fun parse(filePath: String): Result<BookMetadata> =
        withContext(dispatchers.io) {
            Result.runCatchingAsync {
                // NEVER #14: Readium Try handled functionally via getOrElse + throw,
                // and Try.fold. No try-catch around Try. Thrown exceptions are caught
                // by runCatchingAsync → Result.Error.

                val epubFile = File(filePath)

                // Step 1 — Retrieve asset directly from File
                val asset = assetRetriever.retrieve(file = epubFile)
                    .getOrElse { error ->
                        throw AssetRetrieveException(
                            "Failed to retrieve asset: $error",
                            null
                        )
                    }

                // Step 2 — Open publication
                val publication: Publication = publicationOpener.open(
                    asset = asset,
                    allowUserInteraction = false
                ).getOrElse { error ->
                    throw PublicationOpenException(
                        "Failed to open EPUB: $error",
                        null
                    )
                }

                try {
                    // Step 3 — Extract metadata
                    val title = publication.metadata.title?.ifBlank {
                        epubFile.nameWithoutExtension
                    } ?: epubFile.nameWithoutExtension
                    val author = publication.metadata.authors
                        .joinToString(", ") { it.name }
                        .takeIf { it.isNotBlank() }

                    // Step 4 — Extract cover image
                    val coverPath = extractCover(publication)

                    BookMetadata(
                        title = title,
                        author = author,
                        coverPath = coverPath
                    )
                } finally {
                    // NEVER #14 / D3: Always close publication
                    publication.close()
                }
            }
        }

    /**
     * Attempts to extract a cover image from the publication.
     * Uses [cover] extension function (CoverServiceKt) which handles
     * finding the cover link and decoding the bitmap.
     * Returns the saved file path, or null if no cover could be extracted.
     */
    private suspend fun extractCover(publication: Publication): String? {
        return try {
            val coverBitmap: Bitmap? = publication.cover()
            if (coverBitmap != null) {
                val uuid = UUID.randomUUID().toString()
                val coversDir = File(context.filesDir, COVERS_DIR).apply { mkdirs() }
                val coverFile = File(coversDir, "$uuid.png")
                FileOutputStream(coverFile).use { out ->
                    coverBitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                coverFile.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            // Cover extraction is best-effort; failure does not fail the whole parse
            android.util.Log.w("ReadiumMetadataParser", "Cover extraction failed", e)
            null
        }
    }
}

/** Thrown when Readium fails to open a publication. */
class PublicationOpenException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Thrown when Readium fails to retrieve an asset (file not found, etc). */
class AssetRetrieveException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
