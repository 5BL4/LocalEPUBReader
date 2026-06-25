package com.epubreader.app.core.tts

/**
 * High-level TTS playback state derived from Media3 Player state.
 *
 * This is NOT stored in [TtsBus] (Oracle S1) — it is derived from
 * [androidx.media3.common.Player] listeners in [TtsControllerImpl]
 * and exposed to the UI via [TtsController.playbackState].
 *
 * Mapping from Media3 [androidx.media3.common.Player.State]:
 * - STATE_IDLE → [Idle]
 * - STATE_READY + playWhenReady=true → [Playing]
 * - STATE_READY + playWhenReady=false → [Paused]
 * - STATE_ENDED → [Ended]
 * - STATE_BUFFERING → [Paused] (TTS is "buffering" between sentences/chapters)
 */
sealed interface TtsPlaybackState {
    data object Idle : TtsPlaybackState
    data object Playing : TtsPlaybackState
    data object Paused : TtsPlaybackState
    data object Ended : TtsPlaybackState
}
