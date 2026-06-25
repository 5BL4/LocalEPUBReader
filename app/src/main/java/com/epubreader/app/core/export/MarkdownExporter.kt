package com.epubreader.app.core.export

/**
 * Plain data models for Markdown export.
 *
 * Deliberately decoupled from [com.epubreader.app.data.local.entity] Room entities to preserve
 * the Clean Architecture invariant: `core/` must not depend on `data/`. The ViewModel maps
 * Room entities to these plain models before calling [MarkdownExporter].
 */

/** A highlight to export. [text] may contain HTML and is converted via flexmark. */
data class ExportHighlight(
    val text: String,
    val locatorJson: String,
    val color: String,
)

/** A note to export. [highlightText] is the associated highlight text, null for standalone notes. */
data class ExportNote(
    val content: String,
    val highlightText: String?,
    val locatorJson: String?,
)

/** A bookmark to export. */
data class ExportBookmark(
    val label: String?,
    val locatorJson: String,
)

/** A TOC item with href for chapter grouping. */
data class ExportTocItem(
    val title: String,
    val href: String,
    val level: Int,
)

/** Aggregated request for a full book annotation export. */
data class ExportRequest(
    val bookTitle: String,
    val toc: List<ExportTocItem>,
    val highlights: List<ExportHighlight>,
    val notes: List<ExportNote>,
    val bookmarks: List<ExportBookmark>,
)

/**
 * Converts an [ExportRequest] into a structured Markdown string.
 *
 * Uses flexmark-java HTML→Markdown converter (no regex) per harness §7.
 * Output is grouped by chapter (TOC order), with Unsorted section at the end.
 */
interface MarkdownExporter {
    fun exportToMarkdown(request: ExportRequest): String
}
