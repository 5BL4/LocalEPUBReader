package com.epubreader.app.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.epubreader.app.R
import com.epubreader.app.core.tts.TtsPlaybackState

/**
 * TTS control panel (ModalBottomSheet).
 *
 * Shows: play/pause/stop, sentence progress, speed/pitch sliders, sleep timer.
 *
 * UserSettings lock (Council M12, Architect 4.3):
 * When TTS is active, the caller disables font/theme controls via
 * `alpha = 0.5f` + click interception with a Toast — NOT here, but in
 * [ReaderScreen]'s settings controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsControlPanel(
    panelState: TtsPanelState,
    sleepTimerState: SleepTimerState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSleepTimer: (Long?) -> Unit,
    onSleepTimerEndOfChapter: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.tts_panel_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(Modifier.height(16.dp))

            // Show progress when playing or paused
            if (panelState.totalSentences > 0 && (panelState.currentSentence >= 0 || panelState.playbackState is TtsPlaybackState.Paused)) {
                Text(
                    text = "${panelState.currentSentence + 1} / ${panelState.totalSentences}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            // Playback controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onSeekBackward) {
                    Icon(Icons.Default.FastRewind, contentDescription = stringResource(R.string.tts_prev_sentence))
                }
                val isPlaying = panelState.playbackState is TtsPlaybackState.Playing
                IconButton(onClick = { if (isPlaying) onPause() else onPlay() }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.tts_play_pause)
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.tts_stop))
                }
                IconButton(onClick = onSeekForward) {
                    Icon(Icons.Default.FastForward, contentDescription = stringResource(R.string.tts_next_sentence))
                }
            }

            Spacer(Modifier.height(16.dp))

            // Speed slider
            Text(
                text = "${stringResource(R.string.tts_speed)}: ${"%.1f".format(panelState.speed)}x",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = panelState.speed,
                onValueChange = onSpeedChange,
                valueRange = 0.5f..3.0f,
                steps = 24
            )

            // Pitch slider
            Text(
                text = "${stringResource(R.string.tts_pitch)}: ${"%.1f".format(panelState.pitch)}",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = panelState.pitch,
                onValueChange = onPitchChange,
                valueRange = 0.5f..2.0f,
                steps = 14
            )

            Spacer(Modifier.height(8.dp))

            // Sleep timer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Bedtime, contentDescription = null)
                Text(
                    text = when (sleepTimerState) {
                        is SleepTimerState.Active -> stringResource(R.string.tts_sleep_remaining, sleepTimerState.remainingMs / 60000 + 1)
                        is SleepTimerState.EndOfChapter -> stringResource(R.string.tts_sleep_end_of_chapter)
                        SleepTimerState.Inactive -> stringResource(R.string.tts_sleep_timer)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = { onSleepTimer(5 * 60_000L) }) {
                    Text("5m")
                }
                TextButton(onClick = { onSleepTimer(10 * 60_000L) }) {
                    Text("10m")
                }
                TextButton(onClick = { onSleepTimer(15 * 60_000L) }) {
                    Text("15m")
                }
                TextButton(onClick = { onSleepTimer(30 * 60_000L) }) {
                    Text("30m")
                }
                TextButton(onClick = onSleepTimerEndOfChapter) {
                    Text(stringResource(R.string.tts_sleep_chapter_end))
                }
                if (sleepTimerState !is SleepTimerState.Inactive) {
                    TextButton(onClick = { onSleepTimer(null) }) {
                        Text(stringResource(R.string.tts_sleep_cancel))
                    }
                }
            }
        }
    }
}
