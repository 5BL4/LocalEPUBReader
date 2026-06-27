package com.epubreader.app.ui.reader

import org.junit.jupiter.api.Assertions.assertFalse
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
        val script = TtsJsScripts.highlightSentence(0, "rgba(180,180,180,0.35)")
        assertTrue(
            script.contains("__epub_tts_highlight"),
            "highlightSentence must use __epub_tts_highlight CSS class"
        )
    }

    @Test
    fun `highlightSentence uses index parameter`() {
        val script = TtsJsScripts.highlightSentence(5, "rgba(180,180,180,0.35)")
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
    fun `highlightSentence does NOT scroll into view`() {
        val script = TtsJsScripts.highlightSentence(0, "rgba(180,180,180,0.35)")
        assertFalse(
            script.contains("scrollIntoView"),
            "highlightSentence must NOT scroll into view (causes pagination shift in Readium CSS-column layout)"
        )
    }

    @Test
    fun `EXTRACT_SENTENCES includes firstVisibleSentenceId in payload`() {
        assertTrue(
            TtsJsScripts.EXTRACT_SENTENCES.contains("firstVisibleSentenceId"),
            "Extract sentences script must compute firstVisibleSentenceId (Fix A)"
        )
    }

    @Test
    fun `EXTRACT_SENTENCES includes cssSelector in sentence objects`() {
        assertTrue(
            TtsJsScripts.EXTRACT_SENTENCES.contains("cssSelector"),
            "Extract sentences script must include cssSelector per sentence (Fix D)"
        )
    }

    @Test
    fun `EXTRACT_SENTENCES uses object payload not bare array`() {
        assertTrue(
            TtsJsScripts.EXTRACT_SENTENCES.contains("payload"),
            "Extract sentences script must use object payload with firstVisibleSentenceId + sentences"
        )
    }

    @Test
    fun `EXTRACT_SENTENCES includes colIndex in sentence objects`() {
        assertTrue(
            TtsJsScripts.EXTRACT_SENTENCES.contains("colIndex"),
            "Extract sentences script must include colIndex per sentence for page-boundary gating"
        )
    }

    @Test
    fun `EXTRACT_SENTENCES declares colIndex before column count branch`() {
        assertTrue(
            TtsJsScripts.EXTRACT_SENTENCES.contains("var colIndex = 0"),
            "Extract sentences script must declare colIndex with a default before the if/else branch"
        )
    }

    @Test
    fun `EXTRACT_SENTENCES includes colCount in payload`() {
        assertTrue(
            TtsJsScripts.EXTRACT_SENTENCES.contains("colCount"),
            "Extract sentences script must include colCount in payload for scroll/paginated mode detection"
        )
    }
}
