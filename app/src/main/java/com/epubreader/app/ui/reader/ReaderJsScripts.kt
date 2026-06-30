package com.epubreader.app.ui.reader

import com.epubreader.app.data.prefs.AppPreferences

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

    /**
     * Injects a scroll listener that notifies native when the user scrolls near
     * the bottom of the document — used to trigger auto-chapter-advance in scroll mode.
     * Idempotent (checks `window.__epubScrollListener`).
     *
     * The payload JSON includes href, scroll position, direction, and
     * distance-to-bottom so the native side can apply direction-aware guards.
     */
    val SCROLL_LISTENER: String = """
(function() {
    if (window.__epubScrollListener) return;
    var origin = window.location.origin;
    window.__epubScrollListener = true;
    var lastScrollY = 0;
    var isScrollingDown = true;
    window.addEventListener('scroll', function() {
        var sy = window.scrollY || window.pageYOffset || 0;
        var ih = window.innerHeight;
        var sh = document.documentElement.scrollHeight || document.body.scrollHeight || 0;
        isScrollingDown = sy > lastScrollY;
        lastScrollY = sy;
        if (window.AndroidNativeApi && window.AndroidNativeApi.onScrollNearBottom) {
            var distanceToBottom = sh - (sy + ih);
            window.AndroidNativeApi.onScrollNearBottom(origin, JSON.stringify({
                href: window.location.pathname,
                scrollY: sy,
                innerHeight: ih,
                scrollHeight: sh,
                direction: isScrollingDown ? "down" : "up",
                distanceToBottom: distanceToBottom
            }));
        }
    }, { passive: true });
})();
    """.trimIndent()

    /**
     * Builds reader CSS JavaScript injection with dynamic typography rules.
     *
     * ## Responsibility Split
     * - **Top/Bottom padding**: handled at the Android View level via
     *   `ViewCompat.setOnApplyWindowInsetsListener` in ReaderHostFragment
     *   (applies system bar insets as container padding — 100% reliable).
     * - **Left/Right padding**: handled by this JS injection (overrides
     *   Readium's `--RS__pageGutter` and `max-width`/`margin` to ensure
     *   symmetric, user-adjustable horizontal margins).
     * - **Typography rules (paragraph indent, spacing, line-height)**: generated
     *   dynamically from [prefs] and injected into the <style> element.
     *
     * ## Problem
     * Readium CSS sets `body { padding: 0 var(--RS__pageGutter) !important;
     * max-width: 40rem !important; margin: 0 auto !important; }` which causes
     * asymmetric left/right margins (body centering + gutter interactions).
     *
     * ## Strategy
     * 1. **JS inline style** (`body.style.setProperty(..., 'important')`):
     *    Highest CSS priority (1,0,0,!important) — cannot be overridden by
     *    any stylesheet rule. Sets `padding-left/right`, `max-width: none`,
     *    `margin: 0`, `width: 100%`. Vertical padding explicitly set to 0
     *    (Android View level handles top/bottom).
     * 2. **CSS fallback**: Stylesheet with Chrome 51-compatible features
     *    (`calc`, `vmin`, `var` — no `max()`/`env()`). First-frame baseline.
     *    Dynamic typography rules are appended to this stylesheet.
     * 3. **MutationObserver**: Re-applies when Readium's `submitPreferences`
     *    changes `:root` inline style (`--USER__pageMargins`).
     * 4. **Resize listener**: Re-applies on orientation change.
     *
     * ## Idempotency
     * The &lt;style&gt; element is ALWAYS removed and re-created (to pick up
     * updated CSS values from changed prefs). The MutationObserver and resize
     * listener are only created ONCE (guard on `window.__epubMarginObserver`).
     *
     * ## Diagnostics
     * Logs to `Android.log` at key points to verify JS execution and
     * computed values. Check logcat for "ReaderMargin" tag.
     */
    fun buildReaderCss(prefs: AppPreferences): String {
        val defaultLineSpacing = 1.4f
        val rules = mutableListOf<String>()
        if (prefs.paragraphIndent != 0f) {
            rules.add("body p { text-indent: ${prefs.paragraphIndent}em !important; }")
            rules.add("h1+p,h2+p,h3+p,h4+p,h5+p,h6+p { text-indent: 0 !important; }")
        }
        if (prefs.paragraphSpacing != 0f) {
            rules.add("body p { margin-bottom: ${prefs.paragraphSpacing}em !important; }")
        }
        if (prefs.lineSpacing != defaultLineSpacing) {
            rules.add("body p { line-height: ${prefs.lineSpacing} !important; }")
        }
        val typographyCss = if (rules.isEmpty()) "" else rules.joinToString("\n")

        return """
(function() {
    try { Android.log('ReaderMargin: JS injection started'); } catch(e) {}

    function applyReaderMargins() {
        var body = document.body;
        var root = document.documentElement;
        if (!body || !root) {
            try { Android.log('ReaderMargin: body or root is null'); } catch(e) {}
            return;
        }
        var vw = window.innerWidth;
        var vh = window.innerHeight;
        if (vw <= 0 || vh <= 0) return;

        var pmRaw = getComputedStyle(root).getPropertyValue('--USER__pageMargins');
        var pageMargins = parseFloat(pmRaw) || 1.0;
        var vmin = Math.min(vw, vh);
        var leftRight = Math.round(vmin * 0.1 * pageMargins);

        try { Android.log('ReaderMargin: applying lr=' + leftRight + ' pm=' + pageMargins + ' vw=' + vw + ' vh=' + vh); } catch(e) {}

        // Horizontal padding only — vertical padding is handled at Android View level
        body.style.setProperty('padding-left', leftRight + 'px', 'important');
        body.style.setProperty('padding-right', leftRight + 'px', 'important');
        body.style.setProperty('padding-top', '0px', 'important');
        body.style.setProperty('padding-bottom', '0px', 'important');
        body.style.setProperty('max-width', 'none', 'important');
        body.style.setProperty('width', '100%', 'important');
        body.style.setProperty('margin-top', '0px', 'important');
        body.style.setProperty('margin-right', '0px', 'important');
        body.style.setProperty('margin-bottom', '0px', 'important');
        body.style.setProperty('margin-left', '0px', 'important');
        body.style.setProperty('box-sizing', 'border-box', 'important');

        // Verify and log computed values
        try {
            var c = getComputedStyle(body);
            Android.log('ReaderMargin: verified pl=' + c.paddingLeft + ' pr=' + c.paddingRight + ' pt=' + c.paddingTop + ' pb=' + c.paddingBottom + ' mw=' + c.maxWidth);
        } catch(e) {}
    }

    // ALWAYS update <style> — CSS rules may have changed due to preference updates
    var existing = document.getElementById('__epubReaderCss');
    if (existing) existing.remove();

    var style = document.createElement('style');
    style.id = '__epubReaderCss';
    style.textContent = [
        ':root { --RS__pageGutter: 0 !important; }',
        ':root body, :root[style] body {',
        '  max-width: none !important; width: 100% !important; margin: 0 !important;',
        '  box-sizing: border-box !important;',
        '  padding-left: calc(10vmin * var(--USER__pageMargins, 1.0)) !important;',
        '  padding-right: calc(10vmin * var(--USER__pageMargins, 1.0)) !important;',
        '  padding-top: 0 !important; padding-bottom: 0 !important;',
        '}',
        '$typographyCss'
    ].join('\n');
    document.head.appendChild(style);

    // MutationObserver + resize listener — only create once
    if (!window.__epubMarginObserver) {
        var prefsTimer = null;
        var observer = new MutationObserver(function() {
            if (prefsTimer) clearTimeout(prefsTimer);
            prefsTimer = setTimeout(applyReaderMargins, 50);
        });
        observer.observe(document.documentElement, {
            attributes: true,
            attributeFilter: ['style']
        });
        window.__epubMarginObserver = observer;

        var resizeTimer = null;
        window.addEventListener('resize', function() {
            if (resizeTimer) clearTimeout(resizeTimer);
            resizeTimer = setTimeout(applyReaderMargins, 100);
        }, { passive: true });
    }

    applyReaderMargins();
    try { Android.log('ReaderMargin: JS injection complete'); } catch(e) {}
})();
        """.trimIndent()
    }
}
