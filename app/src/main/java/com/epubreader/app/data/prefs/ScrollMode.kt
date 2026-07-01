package com.epubreader.app.data.prefs

/**
 * Reader scroll behavior.
 *
 * Maps to two booleans on [AppPreferences]:
 * - [PAGINATED]            — `scroll = false`, `continuousScroll = false` (page-by-page turn)
 * - [SCROLLED_PER_CHAPTER] — `scroll = true`,  `continuousScroll = false` (scroll within a single chapter)
 * - [CONTINUOUS]           — `scroll = true`,  `continuousScroll = true`  (scroll across the entire book)
 */
enum class ScrollMode {
    PAGINATED,
    SCROLLED_PER_CHAPTER,
    CONTINUOUS
}

/**
 * Derive the effective [ScrollMode] from a preferences snapshot.
 */
fun AppPreferences.toScrollMode(): ScrollMode = when {
    !scroll -> ScrollMode.PAGINATED
    !continuousScroll -> ScrollMode.SCROLLED_PER_CHAPTER
    else -> ScrollMode.CONTINUOUS
}
