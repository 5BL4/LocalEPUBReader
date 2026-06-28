package com.epubreader.app.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.epubreader.app.R

/**
 * Simple progress display panel showing the current reading position as a percentage
 * and a linear progress bar. Display-only — use the TOC for navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressPanel(
    progress: Float,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.reader_progress_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.reader_progress_percent, (progress * 100).toInt()),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )
        }
    }
}
