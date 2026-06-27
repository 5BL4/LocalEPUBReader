package com.epubreader.app.ui.reader

import androidx.compose.runtime.Immutable
import com.epubreader.app.core.tts.TtsPlaybackState

/**
 * TTS panel UI state (Phase 6).
 *
 * Separated from [ReaderUiState] to keep the top-level state lean —
 * TTS state is only collected when the TTS panel is open (NEVER #12).
 */
@Immutable
data class TtsPanelState(
    val isPanelOpen: Boolean = false,
    val isSettingsLocked: Boolean = false,
    val totalSentences: Int = 0,
    val currentSentence: Int = -1,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val playbackState: TtsPlaybackState = TtsPlaybackState.Idle
)

/**
 * Sleep timer state (Phase 6, Oracle D7).
 *
 * "End of chapter" mode: the timer fires when [TtsPlaybackState.Ended] is
 * observed for the current chapter (Architect: hard cap at 2 hours for
 * very long chapters — Council UX risk #11).
 */
@Immutable
sealed interface SleepTimerState {
    data object Inactive : SleepTimerState
    data class Active(val remainingMs: Long, val totalMs: Long) : SleepTimerState
    data object EndOfChapter : SleepTimerState
}
