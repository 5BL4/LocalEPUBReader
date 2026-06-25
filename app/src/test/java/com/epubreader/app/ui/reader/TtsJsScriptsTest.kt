package com.epubreader.app.ui.reader

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TtsJsScripts] — verifies TTS sentence extraction and
 * highlighting JavaScript snippets (Council S11/S12, M10, M12).
 */
class TtsJsScriptsTest {

    @Test
    fun `EXTRACT_SENTENCES calls onSentencesExtracted via AndroidNativeApi`() {
        assertTrue(
            TtsJsScripts.EXTRACT_SENTENCES.contains("onSentencesExtracted"),
            "Extract sentences script must call onSentencesExtracted on the native bridge"
        )
    }

    @Test
    fun `EXTRACT_SENTENCES passes window location origin`() {
        assertTrue(
            TtsJsScripts.EXTRACT_SENTENCES.contains("window.location.origin"),
            "Extract sentences script must pass origin to native (NEVER #8)"
        )
    }

    @Test
    fun `EXTRACT_SENTENCES is idempotent with guard check`() {
        assertTrue(
            TtsJsScripts.EXTRACT_SENTENCES.contains("__epubTtsExtracting"),
            "Extract sentences script must be idempotent (check existing flag)"
        )
    }

    @Test
    fun `EXTRACT_SENTENCES handles CJK punctuation`() {
        val script = TtsJsScripts.EXTRACT_SENTENCES
        assertTrue(
            script.contains("。"),
            "Sentence split regex must include CJK period (U+3002)"
        )
        assertTrue(
            script.contains("！"),
            "Sentence split regex must include CJK exclamation (U+FF01)"
        )
        assertTrue(
            script.contains("？"),
            "Sentence split regex must include CJK question (U+FF1F)"
        )
    }

    @Test
    fun `EXTRACT_SENTENCES returns JSON with id text href progression`() {
        val script = TtsJsScripts.EXTRACT_SENTENCES
        assertTrue(
            script.contains("sentenceId") || script.contains("\"id\""),
            "Extract sentences must include sentence ID field"
        )
        assertTrue(
            script.contains("progression"),
            "Extract sentences must include progression field"
        )
        assertTrue(
            script.contains("href"),
            "Extract sentences must include href field"
        )
    }

    @Test
    fun `highlightSentence contains highlight class`() {
        val script = TtsJsScripts.highlightSentence(0)
        assertTrue(
            script.contains("__epub_tts_highlight"),
            "highlightSentence must use __epub_tts_highlight CSS class"
        )
    }

    @Test
    fun `highlightSentence uses index parameter`() {
        val script = TtsJsScripts.highlightSentence(5)
        assertTrue(
            script.contains("5"),
            "highlightSentence must embed the index parameter (5) in the JS snippet"
        )
    }

    @Test
    fun `CLEAR_TTS_HIGHLIGHT removes highlight spans`() {
        assertTrue(
            TtsJsScripts.CLEAR_TTS_HIGHLIGHT.contains("__epub_tts_highlight"),
            "CLEAR_TTS_HIGHLIGHT must target __epub_tts_highlight elements"
        )
    }

    @Test
    fun `CLEAR_TTS_HIGHLIGHT is idempotent`() {
        val script = TtsJsScripts.CLEAR_TTS_HIGHLIGHT
        assertTrue(
            script.contains("querySelectorAll"),
            "CLEAR_TTS_HIGHLIGHT must use querySelectorAll (handles empty case gracefully)"
        )
    }

    @Test
    fun `highlightSentence scrolls block into view`() {
        val script = TtsJsScripts.highlightSentence(0)
        assertTrue(
            script.contains("scrollIntoView"),
            "highlightSentence must scroll the target block into view"
        )
    }
}
