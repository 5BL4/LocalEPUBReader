package com.epubreader.app.core.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MarkdownExporterImpl] — Phase 5.
 *
 * Tests HTML→Markdown conversion via flexmark, chapter grouping with fragment
 * normalization (Oracle M2), blockquote per-line formatting (Oracle S7), and
 * the full export document structure.
 */
class MarkdownExporterTest {

    private lateinit var exporter: MarkdownExporter

    @BeforeEach
    fun setUp() {
        exporter = MarkdownExporterImpl()
    }

    // -- Basic structure --

    @Test
    fun `empty request produces minimal document with title and date`() {
        val request = ExportRequest(
            bookTitle = "Test Book",
            toc = emptyList(),
            highlights = emptyList(),
            notes = emptyList(),
            bookmarks = emptyList()
        )
        val md = exporter.exportToMarkdown(request)
        assertTrue(md.startsWith("# Test Book\n"))
        assertTrue(md.contains("> Exported:"))
    }

    @Test
    fun `export with no annotations has no chapter sections`() {
        val request = ExportRequest(
            bookTitle = "Empty Book",
            toc = listOf(ExportTocItem("Chapter 1", "ch1.xhtml", 1)),
            highlights = emptyList(),
            notes = emptyList(),
            bookmarks = emptyList()
        )
        val md = exporter.exportToMarkdown(request)
        assertFalse(md.contains("## Chapter 1"))
        assertFalse(md.contains("### Highlights"))
    }

    // -- Highlights --

    @Test
    fun `highlights produce blockquote format`() {
        val request = ExportRequest(
            bookTitle = "Book",
            toc = listOf(ExportTocItem("Chapter 1", "ch1.xhtml", 1)),
            highlights = listOf(
                ExportHighlight(text = "Important text", locatorJson = """{"href":"ch1.xhtml"}""", color = "#FFFF00")
            ),
            notes = emptyList(),
            bookmarks = emptyList()
        )
        val md = exporter.exportToMarkdown(request)
        assertTrue(md.contains("### Highlights"))
        assertTrue(md.contains("> Important text"))
    }

    @Test
    fun `HTML in highlight text is converted to markdown`() {
        val request = ExportRequest(
            bookTitle = "Book",
            toc = listOf(ExportTocItem("Ch1", "ch1.xhtml", 1)),
            highlights = listOf(
                ExportHighlight(text = "<b>Bold</b> and <i>italic</i>", locatorJson = """{"href":"ch1.xhtml"}""", color = "#FFFF00")
            ),
            notes = emptyList(),
            bookmarks = emptyList()
        )
        val md = exporter.exportToMarkdown(request)
        assertTrue(md.contains("**Bold**"))
        assertTrue(md.contains("*italic*"))
    }

    @Test
    fun `plain text with special chars survives conversion`() {
        val request = ExportRequest(
            bookTitle = "Book",
            toc = listOf(ExportTocItem("Ch1", "ch1.xhtml", 1)),
            highlights = listOf(
                ExportHighlight(text = "a < b & c > d", locatorJson = """{"href":"ch1.xhtml"}""", color = "#FFFF00")
            ),
            notes = emptyList(),
            bookmarks = emptyList()
        )
        val md = exporter.exportToMarkdown(request)
        // The text should not be interpreted as HTML tags
        assertFalse(md.contains("<b>"))
    }

    // -- Notes --

    @Test
    fun `notes produce list items`() {
        val request = ExportRequest(
            bookTitle = "Book",
            toc = listOf(ExportTocItem("Ch1", "ch1.xhtml", 1)),
            highlights = emptyList(),
            notes = listOf(
                ExportNote(content = "My thought", highlightText = null, locatorJson = """{"href":"ch1.xhtml"}""")
            ),
            bookmarks = emptyList()
        )
        val md = exporter.exportToMarkdown(request)
        assertTrue(md.contains("### Notes"))
        assertTrue(md.contains("- My thought"))
    }

    @Test
    fun `note with associated highlight includes highlight text`() {
        val request = ExportRequest(
            bookTitle = "Book",
            toc = listOf(ExportTocItem("Ch1", "ch1.xhtml", 1)),
            highlights = emptyList(),
            notes = listOf(
                ExportNote(content = "Great point", highlightText = "Original text", locatorJson = """{"href":"ch1.xhtml"}""")
            ),
            bookmarks = emptyList()
        )
        val md = exporter.exportToMarkdown(request)
        assertTrue(md.contains("- Great point"))
        assertTrue(md.contains("Original text"))
    }

    @Test
    fun `standalone note without highlight has no associated text`() {
        val request = ExportRequest(
            bookTitle = "Book",
            toc = listOf(ExportTocItem("Ch1", "ch1.xhtml", 1)),
            highlights = emptyList(),
            notes = listOf(
                ExportNote(content = "Standalone thought", highlightText = null, locatorJson = """{"href":"ch1.xhtml"}""")
            ),
            bookmarks = emptyList()
        )
        val md = exporter.exportToMarkdown(request)
        assertTrue(md.contains("- Standalone thought"))
        assertFalse(md.contains("on:"))
    }

    // -- Bookmarks --

    @Test
    fun `bookmarks produce bookmark list`() {
        val request = ExportRequest(
            bookTitle = "Book",
            toc = listOf(ExportTocItem("Ch1", "ch1.xhtml", 1)),
            highlights = emptyList(),
            notes = emptyList(),
            bookmarks = listOf(
                ExportBookmark(label = "Page 1", locatorJson = """{"href":"ch1.xhtml"}""")
            )
        )
        val md = exporter.exportToMarkdown(request)
        assertTrue(md.contains("### Bookmarks"))
        assertTrue(md.contains("- Page 1"))
    }

    @Test
    fun `bookmark with null label uses unlabeled placeholder`() {
        val request = ExportRequest(
            bookTitle = "Book",
            toc = listOf(ExportTocItem("Ch1", "ch1.xhtml", 1)),
            highlights = emptyList(),
            notes = emptyList(),
            bookmarks = listOf(
                ExportBookmark(label = null, locatorJson = """{"href":"ch1.xhtml"}""")
            )
        )
        val md = exporter.exportToMarkdown(request)
        assertTrue(md.contains("(unlabeled)"))
    }

    // -- Chapter grouping (Oracle M2) --

    @Test
    fun `annotations grouped by chapter using TOC hrefs`() {
        val request = ExportRequest(
            bookTitle = "Book",
            toc = listOf(
                ExportTocItem("Chapter 1", "ch1.xhtml", 1),
                ExportTocItem("Chapter 2", "ch2.xhtml", 1)
            ),
            highlights = listOf(
                ExportHighlight(text = "In chapter 1", locatorJson = """{"href":"ch1.xhtml"}""", color = "#FFFF00")
            ),
            notes = listOf(
                ExportNote(content = "In chapter 2", highlightText = null, locatorJson = """{"href":"ch2.xhtml"}""")
            ),
            bookmarks = emptyList()
        )
        val md = exporter.exportToMarkdown(request)
        assertTrue(md.contains("## Chapter 1"))
        assertTrue(md.contains("## Chapter 2"))
        assertTrue(md.contains("> In chapter 1"))
        assertTrue(md.contains("- In chapter 2"))
    }

    @Test
    fun `fragment normalization matches ch1_xhtml_section to ch1_xhtml`() {
        val request = ExportRequest(
            bookTitle = "Book",
            toc = listOf(ExportTocItem("Chapter 1", "ch1.xhtml", 1)),
            highlights = listOf(
                ExportHighlight(
                    text = "Highlight with fragment",
                    locatorJson = """{"href":"ch1.xhtml#section-3"}""",
                    color = "#FFFF00"
                )
            ),
            notes = emptyList(),
            bookmarks = emptyList()
        )
        val md = exporter.exportToMarkdown(request)
        // Should be grouped under Chapter 1, not Unsorted
        assertTrue(md.contains("## Chapter 1"), "Expected '## Chapter 1' in output:\n$md")
        assertTrue(md.contains("> Highlight with fragment"), "Expected highlight text in output:\n$md")
        assertFalse(md.contains("## Unsorted"), "Should not contain Unsorted:\n$md")
    }

    @Test
    fun `unmatched locators go to Unsorted section`() {
        val request = ExportRequest(
            bookTitle = "Book",
            toc = listOf(ExportTocItem("Chapter 1", "ch1.xhtml", 1)),
            highlights = listOf(
                ExportHighlight(
                    text = "Unknown chapter",
                    locatorJson = """{"href":"ch99.xhtml"}""",
                    color = "#FFFF00"
                )
            ),
            notes = emptyList(),
            bookmarks = emptyList()
        )
        val md = exporter.exportToMarkdown(request)
        assertTrue(md.contains("## Unsorted"))
        assertTrue(md.contains("> Unknown chapter"))
    }

    @Test
    fun `null locator notes go to Unsorted section`() {
        val request = ExportRequest(
            bookTitle = "Book",
            toc = listOf(ExportTocItem("Chapter 1", "ch1.xhtml", 1)),
            highlights = emptyList(),
            notes = listOf(
                ExportNote(content = "No locator note", highlightText = null, locatorJson = null)
            ),
            bookmarks = emptyList()
        )
        val md = exporter.exportToMarkdown(request)
        assertTrue(md.contains("## Unsorted"))
        assertTrue(md.contains("- No locator note"))
    }

    // -- Multi-line blockquote (Oracle S7) --

    @Test
    fun `multi-line highlight text gets blockquote prefix on every line`() {
        val request = ExportRequest(
            bookTitle = "Book",
            toc = listOf(ExportTocItem("Ch1", "ch1.xhtml", 1)),
            highlights = listOf(
                ExportHighlight(
                    text = "<p>Line one</p><p>Line two</p>",
                    locatorJson = """{"href":"ch1.xhtml"}""",
                    color = "#FFFF00"
                )
            ),
            notes = emptyList(),
            bookmarks = emptyList()
        )
        val md = exporter.exportToMarkdown(request)
        // Both lines should have "> " prefix
        assertTrue(md.contains("> Line one"))
        assertTrue(md.contains("> Line two"))
    }
}
