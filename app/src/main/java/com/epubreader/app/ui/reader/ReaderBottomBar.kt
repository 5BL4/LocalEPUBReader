package com.epubreader.app.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.epubreader.app.R

/**
 * Persistent bottom toolbar for the reader, mimicking the WeChat Reading layout.
 * 5 evenly-spaced icons: TOC, search, knowledge, progress, settings.
 * Same background as the reading area — no elevation, no shadow, no labels.
 */
@Composable
fun ReaderBottomBar(
    onToc: () -> Unit,
    onSearch: () -> Unit,
    onKnowledge: () -> Unit,
    onProgress: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToc) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = stringResource(R.string.reader_toc),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onSearch) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = stringResource(R.string.reader_search),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onKnowledge) {
                Icon(
                    Icons.AutoMirrored.Filled.Notes,
                    contentDescription = stringResource(R.string.reader_knowledge),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onProgress) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = stringResource(R.string.reader_progress),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.reader_settings),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
