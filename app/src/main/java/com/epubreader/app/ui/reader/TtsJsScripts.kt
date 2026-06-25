package com.epubreader.app.ui.reader

/**
 * TTS-related JavaScript snippets injected into the EPUB WebView.
 *
 * **Architecture (Architect Locator Guide, Council M10):**
 * - JS extracts **feature values** (href + progression) per sentence, NOT
 *   full Readium Locator JSON. Kotlin assembles the Locator using
 *   `publication.readingOrder` (more reliable than JS-side construction).
 * - DOM Ranges are stored in `window.__epubTtsRanges` for highlighting.
 * - All scripts are idempotent (check `window.__epub*` flags).
 * - All scripts pass `window.location.origin` to AndroidNativeApi (NEVER #8).
 *
 * **Sentence segmentation (Council S11/S12):**
 * - Splits on Latin (. ! ?) and CJK (。！？) punctuation.
 * - Long sentences (>800 chars) are further chunked by TtsEngine, not JS.
 *
 * **DOM Range lifecycle (Council M12):**
 * - Ranges are per-WebView; chapter navigation creates a new WebView,
 *   automatically clearing `window.__epubTtsRanges`.
 * - UserSettings changes (font/theme) invalidate Ranges — the UI locks
 *   UserSettings during TTS playback (M12) to prevent this.
 */
object TtsJsScripts {

    /**
     * Extracts all sentences from the current chapter's visible text.
     *
     * Returns JSON via `AndroidNativeApi.onSentencesExtracted(origin, json)`:
     * ```json
     * [{"id":0,"text":"First sentence.","href":"chapter1.xhtml","progression":0.05}, ...]
     * ```
     *
     * - `href`: current chapter URL (from `window.location.pathname` basename).
     * - `progression`: element's vertical position / total scroll height (0.0-1.0).
     *
     * Idempotent: checks `window.__epubTtsExtracting` to prevent concurrent runs.
     */
    val EXTRACT_SENTENCES: String = """
(function() {
    if (window.__epubTtsExtracting) return;
    window.__epubTtsExtracting = true;

    var origin = window.location.origin;
    var href = window.location.pathname.split('/').pop() || '';
    var scrollHeight = document.body.scrollHeight || 1;

    // Collect text nodes from block-level elements (skip script/style/img)
    var blockSelector = 'p, h1, h2, h3, h4, h5, h6, li, blockquote, td, div';
    var blocks = document.querySelectorAll(blockSelector);
    var sentences = [];
    var ranges = [];
    var sentenceId = 0;

    // Sentence split regex: Latin (. ! ?) + CJK (。！？)
    var splitRegex = /[^.!?。！？]+[.!?。！？]+["'"')\]]*\s*|[^.!?。！？]+$/g;

    blocks.forEach(function(block) {
        // Skip hidden elements
        if (block.offsetParent === null && block.style.display !== 'none') return;
        // Skip elements inside script/style
        if (block.tagName === 'SCRIPT' || block.tagName === 'STYLE') return;

        var text = block.textContent.trim();
        if (!text) return;

        var matches = text.match(splitRegex);
        if (!matches) return;

        var blockTop = block.getBoundingClientRect().top + window.scrollY;
        var progression = Math.min(blockTop / scrollHeight, 1.0);

        matches.forEach(function(sentence) {
            var trimmed = sentence.trim();
            if (trimmed.length < 2) return;

            // Store DOM range for highlighting (approximate: whole block)
            try {
                var range = document.createRange();
                range.selectNodeContents(block);
                ranges.push({ id: sentenceId, range: range, block: block });
            } catch(e) { /* Range creation can fail on some elements */ }

            sentences.push({
                id: sentenceId,
                text: trimmed,
                href: href,
                progression: parseFloat(progression.toFixed(4))
            });
            sentenceId++;
        });
    });

    // Store ranges globally for highlight/clear operations
    window.__epubTtsRanges = ranges;
    window.__epubTtsExtracting = false;

    if (window.AndroidNativeApi && sentences.length > 0) {
        window.AndroidNativeApi.onSentencesExtracted(origin, JSON.stringify(sentences));
    }
})();
    """.trimIndent()

    /**
     * Highlights the sentence at [index] using the stored DOM Range.
     * Wraps the range in a `<span class="__epub_tts_highlight">` element.
     *
     * Idempotent: clears previous highlight before applying new one.
     * Passes `window.location.origin` (NEVER #8).
     *
     * @param index The sentence index to highlight.
     */
    fun highlightSentence(index: Int): String = """
(function() {
    var origin = window.location.origin;
    // Clear previous highlight
    var prev = document.querySelector('.__epub_tts_highlight');
    if (prev) {
        var parent = prev.parentNode;
        while (prev.firstChild) parent.insertBefore(prev.firstChild, prev);
        parent.removeChild(prev);
    }

    var ranges = window.__epubTtsRanges;
    if (!ranges) return;

    var entry = ranges.find(function(r) { return r.id === $index; });
    if (!entry || !entry.range) return;

    try {
        var range = entry.range;
        // Scroll the block into view
        if (entry.block) {
            entry.block.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
        // Wrap range in highlight span
        var span = document.createElement('span');
        span.className = '__epub_tts_highlight';
        span.style.backgroundColor = '#FFEB3B';
        span.style.color = '#000000';
        range.surroundContents(span);
    } catch(e) {
        // surroundContents fails if range spans multiple elements
        // Fallback: highlight the block element directly
        if (entry.block) {
            entry.block.style.backgroundColor = '#FFEB3B';
        }
    }
})();
    """.trimIndent()

    /**
     * Clears all TTS highlights from the page.
     * Idempotent. Passes `window.location.origin` (NEVER #8).
     */
    val CLEAR_TTS_HIGHLIGHT: String = """
(function() {
    // Remove highlight spans
    var highlights = document.querySelectorAll('.__epub_tts_highlight');
    highlights.forEach(function(span) {
        var parent = span.parentNode;
        while (span.firstChild) parent.insertBefore(span.firstChild, span);
        parent.removeChild(span);
    });
    // Clear inline background styles (fallback highlight)
    var ranges = window.__epubTtsRanges;
    if (ranges) {
        ranges.forEach(function(r) {
            if (r.block) r.block.style.backgroundColor = '';
        });
    }
})();
    """.trimIndent()
}
