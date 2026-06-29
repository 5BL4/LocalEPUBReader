package com.epubreader.app.ui.reader

import com.epubreader.app.data.prefs.AppPreferences
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ReaderJsScripts] — verifies injected JS snippets
 * (center-tap listener, selection listener) are idempotent, passive,
 * and pass origin for security validation (NEVER #8).
 */
class ReaderJsScriptsTest {

    @Test
    fun `SELECTION_LISTENER contains selectionchange event and AndroidNativeApi`() {
        assertTrue(
            ReaderJsScripts.SELECTION_LISTENER.contains("selectionchange"),
            "Selection listener must bind selectionchange event"
        )
        assertTrue(
            ReaderJsScripts.SELECTION_LISTENER.contains("AndroidNativeApi"),
            "Selection listener must call AndroidNativeApi bridge"
        )
    }

    @Test
    fun `SELECTION_LISTENER passes window location origin`() {
        assertTrue(
            ReaderJsScripts.SELECTION_LISTENER.contains("window.location.origin"),
            "Selection listener must pass origin to native (NEVER #8)"
        )
    }

    @Test
    fun `SELECTION_LISTENER is idempotent with guard check`() {
        assertTrue(
            ReaderJsScripts.SELECTION_LISTENER.contains("__epubSelectionListener"),
            "Selection listener must be idempotent (check existing flag)"
        )
    }

    @Test
    fun `CENTER_TAP_LISTENER binds click event and AndroidNativeApi`() {
        assertTrue(
            ReaderJsScripts.CENTER_TAP_LISTENER.contains("addEventListener('click'"),
            "Center-tap listener must bind click event"
        )
        assertTrue(
            ReaderJsScripts.CENTER_TAP_LISTENER.contains("onCenterTap"),
            "Center-tap listener must call onCenterTap bridge"
        )
    }

    @Test
    fun `CENTER_TAP_LISTENER passes window location origin`() {
        assertTrue(
            ReaderJsScripts.CENTER_TAP_LISTENER.contains("window.location.origin"),
            "Center-tap listener must pass origin to native (NEVER #8)"
        )
    }

    @Test
    fun `CENTER_TAP_LISTENER is idempotent with guard check`() {
        assertTrue(
            ReaderJsScripts.CENTER_TAP_LISTENER.contains("__epubCenterTap"),
            "Center-tap listener must be idempotent (check existing flag)"
        )
    }

    @Test
    fun `CENTER_TAP_LISTENER uses passive listener`() {
        assertTrue(
            ReaderJsScripts.CENTER_TAP_LISTENER.contains("passive: true"),
            "Center-tap listener must be passive"
        )
    }

    @Test
    fun `CENTER_TAP_LISTENER checks horizontal center band`() {
        assertTrue(
            ReaderJsScripts.CENTER_TAP_LISTENER.contains("0.33") &&
                ReaderJsScripts.CENTER_TAP_LISTENER.contains("0.67"),
            "Center-tap listener must restrict to horizontal center third"
        )
    }

    @Test
    fun `buildReaderCss with default prefs contains margin rules but no typography rules`() {
        val css = ReaderJsScripts.buildReaderCss(AppPreferences())
        assertTrue(css.contains("--RS__pageGutter"), "CSS must always contain margin gutter override")
        assertTrue(css.contains("padding-left"), "CSS must always contain horizontal padding")
        assertFalse(css.contains("text-indent"), "Default prefs should not inject text-indent")
        assertFalse(css.contains("body p { margin-bottom:"), "Default prefs should not inject paragraph margin-bottom")
        assertFalse(css.contains("body p { line-height:"), "Default prefs should not inject line-height")
    }

    @Test
    fun `buildReaderCss with paragraph indent injects text-indent rule`() {
        val prefs = AppPreferences(paragraphIndent = 2.0f)
        val css = ReaderJsScripts.buildReaderCss(prefs)
        assertTrue(css.contains("text-indent: 2.0em"), "CSS must contain text-indent with correct em value")
        assertTrue(css.contains("h1+p"), "CSS must suppress indent on first paragraph after heading")
    }

    @Test
    fun `buildReaderCss with paragraph spacing injects margin-bottom rule`() {
        val prefs = AppPreferences(paragraphSpacing = 1.5f)
        val css = ReaderJsScripts.buildReaderCss(prefs)
        assertTrue(css.contains("margin-bottom: 1.5em"), "CSS must contain margin-bottom with correct em value")
    }

    @Test
    fun `buildReaderCss with non-default line spacing injects line-height rule`() {
        val prefs = AppPreferences(lineSpacing = 1.8f)
        val css = ReaderJsScripts.buildReaderCss(prefs)
        assertTrue(css.contains("line-height: 1.8"), "CSS must contain line-height with correct value")
        assertTrue(css.contains("body p"), "line-height must target body p, not body")
    }
}
