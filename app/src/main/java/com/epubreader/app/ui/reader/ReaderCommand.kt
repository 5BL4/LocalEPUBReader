package com.epubreader.app.ui.reader

import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

/**
 * One-shot commands from [ReaderViewModel] to [ReaderHostFragment].
 *
 * State-driven actions (e.g., auto-scroll) are NOT commands — they use StateFlow
 * so the Fragment can react to state changes via [androidx.lifecycle.repeatOnLifecycle].
 * This avoids dual-path races (Oracle M2).
 */
sealed interface ReaderCommand {
    data class NavigateToLocator(val locator: Locator) : ReaderCommand
    data class NavigateToLink(val link: Link) : ReaderCommand
    object RequestCurrentSelection : ReaderCommand
    data class ApplyDecorations(val decorations: List<Decoration>, val group: String) : ReaderCommand
    object ClearSelection : ReaderCommand
}
