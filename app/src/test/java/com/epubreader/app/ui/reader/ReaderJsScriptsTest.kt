package com.epubreader.app.ui.reader

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
}
