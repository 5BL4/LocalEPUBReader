package com.epubreader.app.core.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide shared state between [TtsPlaybackService] (media layer) and
 * [TtsControllerImpl] (UI-facing layer), both running in the same process.
 *
 * **Design decisions:**
 *
 * - **@Singleton** (Council consensus): Same-process @Singleton is acceptable
 *   for passing large sentence lists + Locators across the service/UI boundary
 *   without IPC serialization overhead. [clear] MUST be called on book switch
 *   or explicit stop to prevent stale data leaks.
 *
 * - **No playbackState** (Oracle S1): Playback state is derived from
 *   [androidx.media3.common.Player] listeners in [TtsControllerImpl] —
 *   storing it here would create a dual-source-of-truth race.
 *
 * - **sentences is a data source, not a driver** (Oracle M1): [TtsPlayer]
 *   reads [sentences].value on demand inside `handleSetMediaItems` — it does
 *   NOT collect this StateFlow. This avoids the dual-control-path race that
 *   the codebase explicitly outlaws (see [ReaderCommand] KDoc).
 *
 * - **generationId** (Council M14): Each JS sentence extraction produces a new
 *   UUID. Highlight commands carry this ID; the Fragment discards stale
 *   highlights whose generationId doesn't match the current one, preventing
 *   cross-chapter highlight corruption.
 *
 * - **currentSentenceIndex** (Oracle M2): State-driven (not a ReaderCommand)
 *   because it changes every few seconds and would flood the commands
 *   SharedFlow buffer (DROP_OLDEST would drop navigation commands).
 */
@Singleton
class TtsBus @Inject constructor() {

    private val _sentences = MutableStateFlow<List<TtsSentence>>(emptyList())
    /** Sentence list for the current chapter. Read on demand by TtsPlayer. */
    val sentences: StateFlow<List<TtsSentence>> = _sentences.asStateFlow()

    private val _engineState = MutableStateFlow<TtsEngineState>(TtsEngineState.Uninitialized)
    /** TTS engine state — written by TtsEngine, read by TtsController/ReaderVM. */
    val engineState: StateFlow<TtsEngineState> = _engineState.asStateFlow()

    private val _currentSentenceIndex = MutableStateFlow(-1)
    /** Index of the sentence currently being spoken. -1 = not playing. */
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

    private val _generationId = MutableStateFlow("")
    /**
     * Unique ID for the current sentence batch. Changes on each extraction.
     * Empty string = no active batch (Oracle S9: reset on service onCreate / clear).
     */
    val generationId: StateFlow<String> = _generationId.asStateFlow()

    // -- Write API (called by TtsControllerImpl / TtsEngine / TtsPlayer) --

    fun setSentences(sentences: List<TtsSentence>) {
        _sentences.value = sentences
    }

    fun setEngineState(state: TtsEngineState) {
        _engineState.value = state
    }

    fun setCurrentSentenceIndex(index: Int) {
        _currentSentenceIndex.value = index
    }

    fun setGenerationId(id: String) {
        _generationId.value = id
    }

    /**
     * Resets all state to initial values. Called by [TtsControllerImpl] on:
     * - Book switch (stop old TTS before starting new)
     * - Explicit stop (user taps stop / end of book)
     * - Service onCreate (Oracle S9: clear stale state from prior session)
     *
     * Council consensus: mandatory to prevent memory leaks and stale data.
     * generationId is reset to empty string (Oracle M14: prevents stale matches).
     */
    fun clear() {
        _sentences.value = emptyList()
        _engineState.value = TtsEngineState.Uninitialized
        _currentSentenceIndex.value = -1
        _generationId.value = ""
    }
}
