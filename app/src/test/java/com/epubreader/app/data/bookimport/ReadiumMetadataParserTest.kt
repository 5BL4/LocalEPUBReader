package com.epubreader.app.data.bookimport

import android.content.Context
import com.epubreader.app.core.DispatchersProvider
import com.epubreader.app.core.Result
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.readium.r2.shared.publication.Contributor
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.format.FormatHints
import org.readium.r2.streamer.PublicationOpener
import java.io.File

class ReadiumMetadataParserTest {

    private object UnconfinedDispatchers : DispatchersProvider {
        override val io: CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.Unconfined
        override val default: CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.Unconfined
        override val main: CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.Unconfined
        override val mainImmediate: CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.Unconfined
        override val sync: CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.Unconfined
    }

    @BeforeEach
    fun setUp() {
        // Mock android.util.Log to prevent "not mocked" errors in JVM unit tests
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any(), any<Throwable>()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
    }

    private fun mockPublication(
        title: String? = null,
        authors: List<Contributor> = emptyList()
    ): Publication {
        val metadata = mockk<Metadata>(relaxed = true)
        every { metadata.title } returns title
        every { metadata.authors } returns authors

        val pub = mockk<Publication>(relaxed = true)
        every { pub.metadata } returns metadata
        every { pub.close() } just runs
        return pub
    }

    private fun mockContributor(name: String): Contributor {
        val c = mockk<Contributor>(relaxed = true)
        every { c.name } returns name
        return c
    }

    @Test
    fun `successful parse returns metadata with title and author`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "test-covers-1")
        every { context.filesDir } returns tmpDir

        val assetRetriever = mockk<AssetRetriever>(relaxed = true)
        val mockAsset = mockk<Asset>(relaxed = true)
        coEvery { assetRetriever.retrieve(any<File>(), any<FormatHints>()) } returns
            Try.Success<Asset, AssetRetriever.RetrieveError>(mockAsset)

        val publication = mockPublication(
            title = "Test Book",
            authors = listOf(mockContributor("Author One"))
        )

        val publicationOpener = mockk<PublicationOpener>(relaxed = true)
        coEvery {
            publicationOpener.open(any(), any<String>(), false, any(), any())
        } returns Try.Success<Publication, PublicationOpener.OpenError>(publication)

        val parser = ReadiumMetadataParser(
            publicationOpener = publicationOpener,
            assetRetriever = assetRetriever,
            context = context,
            dispatchers = UnconfinedDispatchers
        )

        val result = parser.parse("/fake/path/book.epub")

        assertTrue(result is Result.Success, "Expected Result.Success, got $result")
        val metadata = (result as Result.Success).data
        assertEquals("Test Book", metadata.title)
        assertEquals("Author One", metadata.author)
    }

    @Test
    fun `failed open returns Result Error`() = runTest {
        val context = mockk<Context>(relaxed = true)

        val assetRetriever = mockk<AssetRetriever>(relaxed = true)
        val mockAsset = mockk<Asset>(relaxed = true)
        coEvery { assetRetriever.retrieve(any<File>(), any<FormatHints>()) } returns
            Try.Success<Asset, AssetRetriever.RetrieveError>(mockAsset)

        val publicationOpener = mockk<PublicationOpener>(relaxed = true)
        coEvery {
            publicationOpener.open(any(), any<String>(), false, any(), any())
        } returns Try.Failure<Publication, PublicationOpener.OpenError>(
            PublicationOpener.OpenError.FormatNotSupported()
        )

        val parser = ReadiumMetadataParser(
            publicationOpener = publicationOpener,
            assetRetriever = assetRetriever,
            context = context,
            dispatchers = UnconfinedDispatchers
        )

        val result = parser.parse("/fake/path/book.epub")

        assertTrue(result is Result.Error, "Expected Result.Error, got $result")
    }

    @Test
    fun `successful parse closes publication`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "test-covers-close")
        every { context.filesDir } returns tmpDir

        val assetRetriever = mockk<AssetRetriever>(relaxed = true)
        val mockAsset = mockk<Asset>(relaxed = true)
        coEvery { assetRetriever.retrieve(any<File>(), any<FormatHints>()) } returns
            Try.Success<Asset, AssetRetriever.RetrieveError>(mockAsset)

        val publication = mockPublication(title = "Test Book")

        val publicationOpener = mockk<PublicationOpener>(relaxed = true)
        coEvery {
            publicationOpener.open(any(), any<String>(), false, any(), any())
        } returns Try.Success<Publication, PublicationOpener.OpenError>(publication)

        val parser = ReadiumMetadataParser(
            publicationOpener = publicationOpener,
            assetRetriever = assetRetriever,
            context = context,
            dispatchers = UnconfinedDispatchers
        )

        parser.parse("/fake/path/book.epub")

        verify(exactly = 1) { publication.close() }
    }

    @Test
    fun `failed open does NOT close publication`() = runTest {
        val context = mockk<Context>(relaxed = true)

        val assetRetriever = mockk<AssetRetriever>(relaxed = true)
        val mockAsset = mockk<Asset>(relaxed = true)
        coEvery { assetRetriever.retrieve(any<File>(), any<FormatHints>()) } returns
            Try.Success<Asset, AssetRetriever.RetrieveError>(mockAsset)

        val publication = mockPublication(title = "Should Not Close")

        val publicationOpener = mockk<PublicationOpener>(relaxed = true)
        coEvery {
            publicationOpener.open(any(), any<String>(), false, any(), any())
        } returns Try.Failure<Publication, PublicationOpener.OpenError>(
            PublicationOpener.OpenError.FormatNotSupported()
        )

        val parser = ReadiumMetadataParser(
            publicationOpener = publicationOpener,
            assetRetriever = assetRetriever,
            context = context,
            dispatchers = UnconfinedDispatchers
        )

        parser.parse("/fake/path/book.epub")

        verify(exactly = 0) { publication.close() }
    }

    @Test
    fun `cover extraction failure does not fail parse`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "test-covers-fail")
        every { context.filesDir } returns tmpDir

        val assetRetriever = mockk<AssetRetriever>(relaxed = true)
        val mockAsset = mockk<Asset>(relaxed = true)
        coEvery { assetRetriever.retrieve(any<File>(), any<FormatHints>()) } returns
            Try.Success<Asset, AssetRetriever.RetrieveError>(mockAsset)

        // cover() is an extension function that naturally fails on a mock Publication.
        // extractCover catches exceptions and returns null.
        val publication = mockPublication(title = "No Cover Book")

        val publicationOpener = mockk<PublicationOpener>(relaxed = true)
        coEvery {
            publicationOpener.open(any(), any<String>(), false, any(), any())
        } returns Try.Success<Publication, PublicationOpener.OpenError>(publication)

        val parser = ReadiumMetadataParser(
            publicationOpener = publicationOpener,
            assetRetriever = assetRetriever,
            context = context,
            dispatchers = UnconfinedDispatchers
        )

        val result = parser.parse("/fake/path/book.epub")

        assertTrue(result is Result.Success, "Expected Result.Success despite cover failure, got $result")
        val metadata = (result as Result.Success).data
        assertEquals("No Cover Book", metadata.title)
        assertNull(metadata.coverPath, "Cover path should be null when cover extraction fails")
    }
}
