package com.epubreader.app.ui.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.epubreader.app.R
import kotlinx.collections.immutable.PersistentList

/**
 * A flattened TOC entry. [level] indicates nesting depth (0 = top-level).
 * The parallel [org.readium.r2.shared.publication.Link] list is kept in [ReaderViewModel]
 * for navigation — this data class is display-only (Oracle S3).
 */
@Immutable
data class TocItem(
    val title: String,
    val level: Int
)

@Composable
fun TocDrawer(
    items: PersistentList<TocItem>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    ModalDrawerSheet(modifier = modifier) {
        Text(
            text = stringResource(R.string.reader_toc_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items.size) { index ->
                val item = items[index]
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = item.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    selected = false,
                    onClick = { onItemClick(index) },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(start = (item.level * 16).dp)
                )
            }
        }
    }
}
