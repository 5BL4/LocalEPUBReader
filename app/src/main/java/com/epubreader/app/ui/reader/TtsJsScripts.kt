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
     * {"firstVisibleSentenceId": 5, "sentences": [{"id":0,"text":"...","href":"chapter1.xhtml","progression":0.05,"cssSelector":"p:nth-of-type(3)"}, ...]}
     * ```
     *
     * - `firstVisibleSentenceId`: index of the sentence nearest the visible viewport (Fix A).
     * - `cssSelector`: CSS path to the block element for precise navigation (Fix D).
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
    var href = window.location.pathname;
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
        // Skip hidden elements (getComputedStyle works reliably in CSS column layouts)
        var cs = window.getComputedStyle(block);
        if (cs.display === 'none' || cs.visibility === 'hidden') return;
        // Skip elements inside script/style
        if (block.tagName === 'SCRIPT' || block.tagName === 'STYLE') return;

        var text = block.textContent.trim();
        if (!text) return;

        var matches = text.match(splitRegex);
        if (!matches) return;

        // Column-aware progression for paginated (CSS multi-column) layout.
        // Falls back to scroll-based progression when columnCount is not available.
        var bodyStyle = getComputedStyle(document.body);
        var colCount = parseInt(bodyStyle.columnCount) || 1;
        var bodyRect = document.body.getBoundingClientRect();
        var blockRect = block.getBoundingClientRect();
        var progression;
        var colIndex = 0;
        if (colCount > 1) {
            // Map block's horizontal position to column index, then blend with
            // vertical position within the column for a smooth progression.
            // In CSS multi-column layout, bodyRect.height is the viewport height
            // (single column height), not the total content height. The column
            // height equals bodyRect.height for balanced columns.
            var colWidth = bodyRect.width / colCount;
            var blockCenterX = blockRect.left + blockRect.width / 2;
            colIndex = Math.min(Math.max(Math.floor((blockCenterX - bodyRect.left) / colWidth), 0), colCount - 1);
            // Column height = body height in balanced column layout
            var colHeight = bodyRect.height;
            var withinColFraction = (blockRect.top - bodyRect.top) / colHeight;
            withinColFraction = Math.max(0, Math.min(1, withinColFraction));
            progression = (colIndex + withinColFraction) / colCount;
        } else {
            progression = Math.min(
                (blockRect.top + window.scrollY) / document.documentElement.scrollHeight,
                1.0
            );
        }
        progression = Math.max(0, Math.min(1, progression));

        // Compute a CSS selector for this block element (Fix D)
        var blockCssSelector = '';
        try {
            var el = block;
            var path = [];
            while (el && el !== document.body && el.parentElement) {
                var tag = el.tagName.toLowerCase();
                if (el.id) {
                    path.unshift('#' + el.id);
                    break;
                }
                var siblings = Array.prototype.filter.call(el.parentElement.children, function(c) {
                    return c.tagName === el.tagName;
                });
                if (siblings.length > 1) {
                    path.unshift(tag + ':nth-of-type(' + (siblings.indexOf(el) + 1) + ')');
                } else {
                    path.unshift(tag);
                }
                el = el.parentElement;
            }
            blockCssSelector = path.join(' > ');
        } catch(e) { /* selector generation can fail on unusual DOM */ }

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
                progression: parseFloat(progression.toFixed(4)),
                cssSelector: blockCssSelector,
                colIndex: colIndex
            });
            sentenceId++;
        });
    });

    // Find the first sentence visible in the current viewport (Fix A)
    // In CSS multi-column paginated layout, only one column is visible.
    // A block is visible if its bounding rect overlaps the viewport.
    var firstVisibleSentenceId = 0;
    var vpWidth = window.innerWidth;
    var vpHeight = window.innerHeight;
    for (var i = 0; i < ranges.length; i++) {
        var r = ranges[i];
        if (r && r.block) {
            var rect = r.block.getBoundingClientRect();
            if (rect.right > 0 && rect.left < vpWidth &&
                rect.bottom > 0 && rect.top < vpHeight) {
                firstVisibleSentenceId = r.id;
                break;
            }
        }
    }

    // Store ranges globally for highlight/clear operations
    window.__epubTtsRanges = ranges;
    window.__epubTtsExtracting = false;

    if (window.AndroidNativeApi && sentences.length > 0) {
        var payload = {
            firstVisibleSentenceId: firstVisibleSentenceId,
            colCount: colCount,
            sentences: sentences
        };
        window.AndroidNativeApi.onSentencesExtracted(origin, JSON.stringify(payload));
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
     * @param color CSS background color for the highlight (e.g. "rgba(180,180,180,0.35)" or "#FFEB3B").
     */
    fun highlightSentence(index: Int, color: String): String = """
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

    // Use CSS-only highlighting on the block element, not DOM mutation.
    // range.surroundContents(span) triggers Chromium scroll anchoring
    // which can pull the viewport unpredictably in paginated layout.
    // The block-level approach is simpler and avoids this entirely.
    if (entry.block) {
        entry.block.style.backgroundColor = '$color';
        entry.block.style.overflowAnchor = 'none';
        entry.block.style.transition = 'background-color 0.15s ease';
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
