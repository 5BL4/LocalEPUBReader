package com.epubreader.app.core.export

import com.vladsch.flexmark.html2md.converter.ExtensionConversion
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import com.vladsch.flexmark.html2md.converter.LinkConversion
import com.vladsch.flexmark.util.data.MutableDataSet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarkdownExporterImpl @Inject constructor() : MarkdownExporter {

    private val converter: FlexmarkHtmlConverter by lazy {
        val options = MutableDataSet().apply {
            set(FlexmarkHtmlConverter.DIV_AS_PARAGRAPH, true)
            set(FlexmarkHtmlConverter.BR_AS_PARA_BREAKS, true)
            set(FlexmarkHtmlConverter.BR_AS_EXTRA_BLANK_LINES, false)
            set(FlexmarkHtmlConverter.EXT_TABLES, ExtensionConversion.MARKDOWN)
            set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
            set(FlexmarkHtmlConverter.OUTPUT_UNKNOWN_TAGS, false)
            set(FlexmarkHtmlConverter.TYPOGRAPHIC_QUOTES, false)
            set(FlexmarkHtmlConverter.TYPOGRAPHIC_SMARTS, false)
            set(FlexmarkHtmlConverter.ADD_TRAILING_EOL, true)
            set(FlexmarkHtmlConverter.UNORDERED_LIST_DELIMITER, '-')
            set(FlexmarkHtmlConverter.NBSP_TEXT, " ")
            set(FlexmarkHtmlConverter.MAX_BLANK_LINES, 1)
            set(FlexmarkHtmlConverter.MAX_TRAILING_BLANK_LINES, 1)
        }
        FlexmarkHtmlConverter.builder(options).build()
    }

    @Synchronized
    override fun exportToMarkdown(request: ExportRequest): String {
        val sb = StringBuilder()
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

        // Book title
        sb.append("# ").append(request.bookTitle).append("\n\n")
        sb.append("> Exported: ").append(dateStr).append("\n\n")

        // Build chapter map: normalizedHref -> ExportTocItem (Oracle M2: strip fragment)
        val chapterByHref: Map<String, ExportTocItem> = request.toc.associateBy { it.href.normalizeHref() }

        // Group annotations by chapter
        // Each annotation has a locatorJson; parse href from it (locator JSON has "href" field)
        val highlightsByChapter = request.highlights.groupBy { it.locatorJson.extractHref()?.normalizeHref() }
        val notesByChapter = request.notes.groupBy { it.locatorJson?.extractHref()?.normalizeHref() }
        val bookmarksByChapter = request.bookmarks.groupBy { it.locatorJson.extractHref()?.normalizeHref() }

        // Collect all chapter hrefs that HAVE annotations, in TOC order
        val tocHrefOrder = request.toc.map { it.href.normalizeHref() }
        val annotatedHrefs = LinkedHashSet<String>()
        highlightsByChapter.keys.filterNotNull().forEach { annotatedHrefs.add(it) }
        notesByChapter.keys.filterNotNull().forEach { annotatedHrefs.add(it) }
        bookmarksByChapter.keys.filterNotNull().forEach { annotatedHrefs.add(it) }

        // Render chapters in TOC order (only those with annotations), then unmatched at the end as "Unsorted"
        val sortedChapters = tocHrefOrder.filter { it in annotatedHrefs }.distinct()
        val unsortedHrefs = annotatedHrefs.filter { it !in tocHrefOrder }

        for (href in sortedChapters) {
            val tocItem = chapterByHref[href]
            val chHighlights = highlightsByChapter[href] ?: emptyList()
            val chNotes = notesByChapter[href] ?: emptyList()
            val chBookmarks = bookmarksByChapter[href] ?: emptyList()
            renderChapter(sb, tocItem?.title ?: "Untitled", tocItem?.level ?: 1, chHighlights, chNotes, chBookmarks)
        }

        // Unsorted: merge all unmatched hrefs + null-locator annotations
        val unsortedHighlights = (unsortedHrefs.flatMap { highlightsByChapter[it] ?: emptyList() } +
            (highlightsByChapter[null] ?: emptyList()))
        val unsortedNotes = (unsortedHrefs.flatMap { notesByChapter[it] ?: emptyList() } +
            (notesByChapter[null] ?: emptyList()))
        val unsortedBookmarks = (unsortedHrefs.flatMap { bookmarksByChapter[it] ?: emptyList() } +
            (bookmarksByChapter[null] ?: emptyList()))

        if (unsortedHighlights.isNotEmpty() || unsortedNotes.isNotEmpty() || unsortedBookmarks.isNotEmpty()) {
            renderChapter(sb, "Unsorted", 1, unsortedHighlights, unsortedNotes, unsortedBookmarks)
        }

        return sb.toString()
    }

    private fun renderChapter(
        sb: StringBuilder,
        title: String,
        level: Int,
        highlights: List<ExportHighlight>,
        notes: List<ExportNote>,
        bookmarks: List<ExportBookmark>,
    ) {
        val headingPrefix = "#".repeat((level + 1).coerceIn(2, 6))
        sb.append(headingPrefix).append(" ").append(title).append("\n\n")

        val sortedHighlights = highlights.sortedBy { it.locatorJson }
        val sortedNotes = notes.sortedBy { it.locatorJson }
        val sortedBookmarks = bookmarks.sortedBy { it.locatorJson }

        if (highlights.isNotEmpty()) {
            sb.append("### Highlights\n\n")
            for (h in highlights) {
                val md = htmlToMarkdown(h.text)
                // Oracle S7: prefix every line with "> " for blockquote
                val quoted = md.lineSequence().joinToString("\n") { line -> "> $line" }
                sb.append(quoted).append("\n\n")
            }
        }

        if (notes.isNotEmpty()) {
            sb.append("### Notes\n\n")
            for (n in notes) {
                val noteMd = htmlToMarkdown(n.content)
                if (n.highlightText != null) {
                    val highlightMd = htmlToMarkdown(n.highlightText)
                    sb.append("- ").append(noteMd).append(" *(on: \"").append(highlightMd).append("\")*\n")
                } else {
                    sb.append("- ").append(noteMd).append("\n")
                }
            }
            sb.append("\n")
        }

        if (bookmarks.isNotEmpty()) {
            sb.append("### Bookmarks\n\n")
            for (b in bookmarks) {
                val label = b.label ?: "(unlabeled)"
                sb.append("- ").append(label).append("\n")
            }
            sb.append("\n")
        }
    }

    private fun htmlToMarkdown(html: String): String {
        return converter.convert(html).trim()
    }

    /** Strip fragment (#...) and query (?...) from href for matching. Oracle M2. */
    private fun String.normalizeHref(): String {
        return substringBefore('#').substringBefore('?').trimEnd('/')
    }

    /** Extract href from a Locator JSON string. Locator JSON contains "href":"..." field. */
    private fun String.extractHref(): String? {
        if (isBlank()) return null
        // Simple extraction of "href" field from JSON — avoids pulling Readium Locator into core
        val regex = Regex(""""href"\s*:\s*"([^"]+)"""")
        return regex.find(this)?.groupValues?.getOrNull(1)
    }
}
