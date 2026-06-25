package com.epubreader.app.ui.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ReaderJsScripts] — verifies NEVER #9 compliance
 * (auto-scroll JS must bind touchstart → cancelAnimationFrame).
 */
class ReaderJsScriptsTest {

    @Test
    fun `AUTO_SCROLL_START contains requestAnimationFrame for smooth scrolling`() {
        assertTrue(
            ReaderJsScripts.AUTO_SCROLL_START.contains("requestAnimationFrame"),
            "Auto-scroll script must use requestAnimationFrame"
        )
    }

    @Test
    fun `AUTO_SCROLL_START binds touchstart with cancelAnimationFrame`() {
        assertTrue(
            ReaderJsScripts.AUTO_SCROLL_START.contains("touchstart"),
            "Auto-scroll script must bind touchstart event (NEVER #9)"
        )
        assertTrue(
            ReaderJsScripts.AUTO_SCROLL_START.contains("cancelAnimationFrame"),
            "Auto-scroll script must call cancelAnimationFrame on touch (NEVER #9)"
        )
    }

    @Test
    fun `AUTO_SCROLL_START notifies native on touch via onAutoScrollStopped`() {
        assertTrue(
            ReaderJsScripts.AUTO_SCROLL_START.contains("onAutoScrollStopped"),
            "Auto-scroll script must notify native when touch stops scrolling"
        )
    }

    @Test
    fun `AUTO_SCROLL_START passes window location origin for security validation`() {
        assertTrue(
            ReaderJsScripts.AUTO_SCROLL_START.contains("window.location.origin"),
            "Auto-scroll script must pass origin to native (NEVER #8)"
        )
    }

    @Test
    fun `AUTO_SCROLL_START is idempotent with guard check`() {
        assertTrue(
            ReaderJsScripts.AUTO_SCROLL_START.contains("__epubAutoScroll"),
            "Auto-scroll script must be idempotent (check existing flag)"
        )
    }

    @Test
    fun `AUTO_SCROLL_STOP calls stop function`() {
        assertTrue(
            ReaderJsScripts.AUTO_SCROLL_STOP.contains("stop"),
            "Auto-scroll stop script must call the stop function"
        )
    }

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
    fun `AUTO_SCROLL_START uses passive touch listeners`() {
        assertTrue(
            ReaderJsScripts.AUTO_SCROLL_START.contains("passive: true"),
            "Touch listeners must be passive to avoid blocking scroll"
        )
    }
}
