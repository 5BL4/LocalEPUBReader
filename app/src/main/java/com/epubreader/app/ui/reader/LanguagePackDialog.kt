package com.epubreader.app.ui.reader

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.epubreader.app.R

/**
 * Dialog shown when TTS language data is missing (NEVER #28, Council #4).
 *
 * Two variants:
 * - [LanguageMissing]: prompts user to install TTS language data.
 * - [EngineError]: generic TTS engine error (e.g., engine not installed).
 */
@Composable
fun LanguagePackDialog(
    isError: Boolean = false,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isError) stringResource(R.string.tts_error_title)
                else stringResource(R.string.tts_language_missing_title)
            )
        },
        text = {
            Text(
                if (isError) stringResource(R.string.tts_error_message)
                else stringResource(R.string.tts_language_missing_message)
            )
        },
        confirmButton = {
            if (!isError) {
                TextButton(onClick = onInstall) {
                    Text(stringResource(R.string.tts_install_language))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.tts_dismiss))
            }
        }
    )
}
