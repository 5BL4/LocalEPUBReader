package com.epubreader.app.ui.reader

/**
 * JavaScript snippets for paragraph-level bookmark anchoring.
 *
 * Injected into the EPUB WebView via
 * [org.readium.r2.navigator.epub.EpubNavigatorFragment.evaluateJavascript].
 *
 * All scripts:
 * - Are idempotent (safe to re-inject — checks `window.__epubBookmark*` flags).
 * - Pass `window.location.origin` to [AndroidNativeApi] for security validation (NEVER #8).
 */
object BookmarkJsScripts {

    /**
     * Finds the first visible block-level element in the current viewport and
     * returns its text, CSS selector, href, and progression via
     * `AndroidNativeApi.onFirstVisibleBlock(origin, json)`.
     *
     * Returns JSON: `{"text":"...","cssSelector":"p:nth-of-type(3)","href":"chapter1.xhtml","progression":0.05}`
     * or empty string if no visible block is found.
     *
     * Uses the same visibility detection and CSS selector generation pattern as
     * [TtsJsScripts.EXTRACT_SENTENCES], but only returns the first visible block
     * (lighter weight — no full sentence extraction).
     *
     * Idempotent: checks `window.__epubBookmarkExtracting` to prevent concurrent runs.
     */
    val GET_FIRST_VISIBLE_BLOCK: String = """
(function() {
    if (window.__epubBookmarkExtracting) return;
    window.__epubBookmarkExtracting = true;

    try {
        var origin = window.location.origin;
        var href = window.location.pathname;
        var blockSelector = 'p, h1, h2, h3, h4, h5, h6, li, blockquote, td, div';
        var blocks = document.querySelectorAll(blockSelector);
        var vpWidth = window.innerWidth;
        var vpHeight = window.innerHeight;
        var result = null;

        for (var i = 0; i < blocks.length; i++) {
            var block = blocks[i];
            // Skip hidden elements
            var cs = window.getComputedStyle(block);
            if (cs.display === 'none' || cs.visibility === 'hidden') continue;
            // Skip script/style
            if (block.tagName === 'SCRIPT' || block.tagName === 'STYLE') continue;
            var text = block.textContent.trim();
            if (!text) continue;

            var rect = block.getBoundingClientRect();
            // Check viewport overlap
            if (rect.right > 0 && rect.left < vpWidth &&
                rect.bottom > 0 && rect.top < vpHeight) {

                // Generate CSS selector for this block
                var cssSelector = '';
                try {
                    var el = block;
                    var path = [];
                    while (el && el !== document.body && el.parentElement) {
                        var tag = el.tagName.toLowerCase();
                        if (el.id) {
                            path.unshift('#' + el.id);
                            break;
                        }
                        var sibs = Array.prototype.filter.call(
                            el.parentElement.children,
                            function(c) { return c.tagName === el.tagName; }
                        );
                        if (sibs.length > 1) {
                            path.unshift(tag + ':nth-of-type(' + (sibs.indexOf(el) + 1) + ')');
                        } else {
                            path.unshift(tag);
                        }
                        el = el.parentElement;
                    }
                    cssSelector = path.join(' > ');
                } catch(e) { /* selector generation can fail on unusual DOM */ }

                // Column-aware progression (same logic as EXTRACT_SENTENCES)
                var bodyStyle = getComputedStyle(document.body);
                var colCount = parseInt(bodyStyle.columnCount) || 1;
                var bodyRect = document.body.getBoundingClientRect();
                var progression;
                if (colCount > 1) {
                    var colWidth = bodyRect.width / colCount;
                    var blockCenterX = rect.left + rect.width / 2;
                    var colIndex = Math.min(Math.max(
                        Math.floor((blockCenterX - bodyRect.left) / colWidth), 0), colCount - 1);
                    var colHeight = bodyRect.height;
                    var withinColFraction = (rect.top - bodyRect.top) / colHeight;
                    withinColFraction = Math.max(0, Math.min(1, withinColFraction));
                    progression = (colIndex + withinColFraction) / colCount;
                } else {
                    progression = Math.min(
                        (rect.top + window.scrollY) / document.documentElement.scrollHeight, 1.0
                    );
                }
                progression = Math.max(0, Math.min(1, progression));

                result = {
                    text: text.substring(0, 200),
                    cssSelector: cssSelector,
                    href: href,
                    progression: parseFloat(progression.toFixed(4))
                };
                break;
            }
        }

        window.__epubBookmarkExtracting = false;

        if (window.AndroidNativeApi && window.AndroidNativeApi.onFirstVisibleBlock) {
            window.AndroidNativeApi.onFirstVisibleBlock(origin, result ? JSON.stringify(result) : '');
        }
    } catch(e) {
        window.__epubBookmarkExtracting = false;
        // Still call back with empty string so the native side can fall back
        try {
            if (window.AndroidNativeApi && window.AndroidNativeApi.onFirstVisibleBlock) {
                window.AndroidNativeApi.onFirstVisibleBlock(window.location.origin, '');
            }
        } catch(e2) {}
    }
})();
    """.trimIndent()
}
