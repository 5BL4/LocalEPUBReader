package com.epubreader.app.core.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TtsEngineState] sealed interface — verifies state machine
 * data carriers and type-safety (NEVER #28).
 */
class TtsEngineStateTest {

    @Test
    fun `Uninitialized is distinct data object`() {
        val state: TtsEngineState = TtsEngineState.Uninitialized
        assertNotNull(state)
        assertEquals(TtsEngineState.Uninitialized, state)
        // Verify it is the same singleton instance
        assertEquals(TtsEngineState.Uninitialized, TtsEngineState.Uninitialized)
    }

    @Test
    fun `LanguageMissing carries locale`() {
        val state = TtsEngineState.LanguageMissing("en-US")
        assertEquals("en-US", state.locale)
    }

    @Test
    fun `Error carries reason`() {
        val state = TtsEngineState.Error("init failed")
        assertEquals("init failed", state.reason)
    }

    @Test
    fun `Ready is distinct data object`() {
        val state: TtsEngineState = TtsEngineState.Ready
        assertNotNull(state)
        assertEquals(TtsEngineState.Ready, state)
        assertEquals(TtsEngineState.Ready, TtsEngineState.Ready)
    }

    @Test
    fun `state transitions are type-safe`() {
        // Verify that a when() expression on TtsEngineState covers all cases.
        // If a new state is added without updating this function, the compiler
        // (with -Xwhen-guards or exhaustive when) catches it.
        val states: List<TtsEngineState> = listOf(
            TtsEngineState.Uninitialized,
            TtsEngineState.Initializing,
            TtsEngineState.Ready,
            TtsEngineState.LanguageMissing("fr-FR"),
            TtsEngineState.Error("test error")
        )

        for (state in states) {
            val result = describeState(state)
            assertNotNull(result)
            // speak() should only be valid when Ready
            if (state is TtsEngineState.Ready) {
                assertEquals("ready", result)
            } else {
                // All non-Ready states should block speak
                assertNotReady(result)
            }
        }
    }

    @Test
    fun `watchdog timeout constant is 2000ms`() {
        assertEquals(2_000L, TtsEngine.WATCHDOG_TIMEOUT_MS)
    }

    @Test
    fun `max utterance length is 800`() {
        assertEquals(800, TtsEngine.MAX_UTTERANCE_LENGTH)
    }

    // --- helpers ---

    /**
     * Exhaustive when() over [TtsEngineState]. Compiler will flag missing branches
     * when a new state is added — this is the type-safety contract (NEVER #28).
     */
    private fun describeState(state: TtsEngineState): String = when (state) {
        is TtsEngineState.Uninitialized -> "uninitialized"
        is TtsEngineState.Initializing -> "initializing"
        is TtsEngineState.Ready -> "ready"
        is TtsEngineState.LanguageMissing -> "language_missing:${state.locale}"
        is TtsEngineState.Error -> "error:${state.reason}"
    }

    private fun assertNotReady(result: String) {
        // All non-Ready states should produce a description that is NOT "ready"
        val readyStates = listOf("ready")
        assert(!readyStates.contains(result)) {
            "Expected a non-ready state description, but got: $result"
        }
    }
}
