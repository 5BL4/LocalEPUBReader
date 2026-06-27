package com.epubreader.app.core.tts

import org.readium.r2.shared.publication.Locator

/**
 * A single sentence extracted from the EPUB content, ready for TTS playback.
 *
 * [locator] (Council Top Risk #4/#8, Architect M10):
 * - Enables page-turning in paginated mode: before highlighting, the navigator
 *   calls `navigator.go(sentence.locator)` to flip to the correct page.
 * - Enables progress saving: the current sentence's locator is saved to
 *   [com.epubreader.app.data.local.entity.ReadingProgressEntity] so the user
 *   resumes at the right position.
 *
 * The locator is assembled in Kotlin (not JS) per the Architect's guide:
 * JS extracts `href` + `progression` feature values; Kotlin uses
 * [org.readium.r2.shared.publication.Publication.readingOrder] to build a
 * full [Locator] with the correct [Locator.Locations].
 *
 * @param id Zero-based sentence index within the current chapter.
 * @param text The sentence text to be spoken by TTS.
 * @param locator Readium Locator for navigation + progress saving. Null if
 *   extraction failed for this sentence (TTS can still speak it, but
 *   highlighting/progress won't work for this sentence).
 * @param colIndex CSS multi-column page index (0-based) for this sentence.
 *   Used to gate auto-page-turn: navigation only occurs when a sentence
 *   crosses a column/page boundary. Defaults to 0 for single-column layouts.
 */
data class TtsSentence(
    val id: Int,
    val text: String,
    val locator: Locator?,
    val colIndex: Int = 0
)
