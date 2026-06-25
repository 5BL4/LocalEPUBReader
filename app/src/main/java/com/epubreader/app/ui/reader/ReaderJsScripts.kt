package com.epubreader.app.ui.reader

/**
 * JavaScript snippets injected into the EPUB WebView via
 * [org.readium.r2.navigator.epub.EpubNavigatorFragment.evaluateJavascript].
 *
 * All scripts:
 * - Are idempotent (safe to re-inject on resource navigation — Oracle M6).
 * - Pass `window.location.origin` to [AndroidNativeApi] for security validation (NEVER #8).
 *
 * Auto-scroll (NEVER #9):
 * - Uses `requestAnimationFrame` for smooth scrolling.
 * - Binds `touchstart` + `touchmove` → `cancelAnimationFrame` (anti-tug-of-war).
 * - Notifies native via `AndroidNativeApi.onAutoScrollStopped(origin)` when touch is detected.
 */
object ReaderJsScripts {

    /**
     * Starts smooth auto-scroll. Cancels on any touch interaction (NEVER #9).
     * Idempotent: checks `window.__epubAutoScroll` to avoid duplicate loops.
     */
    val AUTO_SCROLL_START: String = """
(function() {
    if (window.__epubAutoScroll && window.__epubAutoScroll.running) return;

    var speed = 2;
    var rafId = null;
    var isRunning = true;
    var origin = window.location.origin;

    function scrollStep() {
        if (!isRunning) return;
        // S-I: Stop at end of document to avoid wasting CPU/battery
        if (window.scrollY + window.innerHeight >= document.body.scrollHeight) {
            cancelScroll();
            return;
        }
        window.scrollBy(0, speed);
        rafId = requestAnimationFrame(scrollStep);
    }

    function cancelScroll() {
        if (!isRunning) return;
        isRunning = false;
        if (rafId) {
            cancelAnimationFrame(rafId);
            rafId = null;
        }
        if (window.__epubAutoScroll) {
            window.__epubAutoScroll.running = false;
        }
        if (window.AndroidNativeApi) {
            window.AndroidNativeApi.onAutoScrollStopped(origin);
        }
    }

    document.addEventListener('touchstart', cancelScroll, { passive: true });
    document.addEventListener('touchmove', cancelScroll, { passive: true });

    window.__epubAutoScroll = {
        running: true,
        stop: cancelScroll
    };

    scrollStep();
})();
    """.trimIndent()

    /**
     * Stops auto-scroll if active. Idempotent.
     */
    val AUTO_SCROLL_STOP: String = """
(function() {
    if (window.__epubAutoScroll && window.__epubAutoScroll.stop) {
        window.__epubAutoScroll.stop();
    }
})();
    """.trimIndent()

    /**
     * Injects a debounced `selectionchange` listener that notifies native
     * when the user selects or clears text. Idempotent (checks `window.__epubSelectionListener`).
     *
     * Notifies native with empty text when selection is cleared so the UI can
     * dismiss the selection toolbar.
     */
    val SELECTION_LISTENER: String = """
(function() {
    if (window.__epubSelectionListener) return;

    var debounceTimer = null;
    var origin = window.location.origin;

    window.__epubSelectionListener = true;

    document.addEventListener('selectionchange', function() {
        if (debounceTimer) clearTimeout(debounceTimer);
        debounceTimer = setTimeout(function() {
            var sel = window.getSelection();
            var text = sel ? sel.toString().trim() : '';
            if (window.AndroidNativeApi) {
                window.AndroidNativeApi.onSelectionChanged(origin, text);
            }
        }, 300);
    }, { passive: true });
})();
    """.trimIndent()
}
