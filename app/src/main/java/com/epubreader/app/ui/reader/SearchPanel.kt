package com.epubreader.app.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.epubreader.app.R
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class SearchResult(
    val text: String,
    val before: String,
    val after: String
)

@Immutable
data class SearchState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: PersistentList<SearchResult> = persistentListOf(),
    val currentIndex: Int = -1,
    val error: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPanel(
    state: SearchState,
    onQueryChange: (String) -> Unit,
    onResultClick: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(onDismissRequest = onClose, modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.reader_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.reader_search_close)
                        )
                    }
                },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            when {
                state.isSearching -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null -> {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                state.results.isEmpty() && state.query.isNotBlank() -> {
                    Text(
                        text = stringResource(R.string.reader_search_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                state.results.isNotEmpty() -> {
                    Text(
                        text = stringResource(R.string.reader_search_results_count, state.results.size),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(state.results.size) { index ->
                            val result = state.results[index]
                            SearchResultItem(
                                result = result,
                                isCurrent = index == state.currentIndex,
                                onClick = { onResultClick(index) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    result: SearchResult,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = result.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = "${result.before}${result.text}${result.after}",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        },
        colors = if (isCurrent) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
