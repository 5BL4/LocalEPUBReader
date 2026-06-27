package com.epubreader.app.ui.reader

/**
 * JavaScript snippets injected into the EPUB WebView via
 * [org.readium.r2.navigator.epub.EpubNavigatorFragment.evaluateJavascript].
 *
 * All scripts:
 * - Are idempotent (safe to re-inject on resource navigation — Oracle M6).
 * - Pass `window.location.origin` to [AndroidNativeApi] for security validation (NEVER #8).
 */
object ReaderJsScripts {

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

    /**
     * Injects a click listener that notifies native when the user taps the
     * horizontal center third of the reading area — used to toggle the
     * toolbar. Idempotent (checks `window.__epubCenterTap`).
     *
     * Ignores clicks on links and clicks while text is selected so that
     * navigation and selection still work normally.
     */
    val CENTER_TAP_LISTENER: String = """
(function() {
    if (window.__epubCenterTap) return;

    var origin = window.location.origin;
    window.__epubCenterTap = true;

    document.addEventListener('click', function(e) {
        // Ignore clicks on links so in-book navigation still works.
        if (e.target && e.target.closest && e.target.closest('a')) return;
        // Ignore while the user has an active text selection.
        var sel = window.getSelection();
        if (sel && sel.toString().trim().length > 0) return;
        // Only fire in the horizontal center third.
        var x = e.clientX;
        var w = window.innerWidth;
        if (x > w * 0.33 && x < w * 0.67) {
            if (window.AndroidNativeApi && window.AndroidNativeApi.onCenterTap) {
                window.AndroidNativeApi.onCenterTap(origin);
            }
        }
    }, { passive: true });
})();
    """.trimIndent()
}
