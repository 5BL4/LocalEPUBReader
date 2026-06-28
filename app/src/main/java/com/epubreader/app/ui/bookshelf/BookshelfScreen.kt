package com.epubreader.app.ui.bookshelf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.epubreader.app.R
import com.epubreader.app.ui.bookshelf.components.BookCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onBookClick: (String) -> Unit,
    onLogViewerClick: () -> Unit,
    viewModel: BookshelfViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()

    // NEVER #12 — isolate high-frequency progress with derivedStateOf (threshold to 1%)
    val displayProgress by remember {
        derivedStateOf {
            val p = importProgress
            if (p < 0f) 0f else (p * 100).toInt() / 100f
        }
    }

    // SAF launcher (NEVER #15)
    val epubLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importBook(it) }
    }

    // Delete confirmation state
    var bookContextMenuUuid by remember { mutableStateOf<String?>(null) }
    var bookToDelete by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Hoist string resources out of LaunchedEffect (stringResource is @Composable)
    val successMessage = stringResource(R.string.bookshelf_import_success)

    LaunchedEffect(importState) {
        when (val state = importState) {
            is ImportState.Success -> {
                snackbarHostState.showSnackbar(message = successMessage)
                viewModel.resetImportState()
            }
            is ImportState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.resetImportState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bookshelf_title)) },
                actions = {
                    IconButton(onClick = onLogViewerClick) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = stringResource(R.string.log_viewer_title)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                epubLauncher.launch(arrayOf("application/epub+zip"))
            }) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.bookshelf_import)
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.books.isEmpty() && uiState.error == null -> {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.bookshelf_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = innerPadding.calculateBottomPadding() + 80.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = uiState.books,
                        key = { it.uuid }
                    ) { book ->
                        BookCard(
                            book = book,
                            onClick = { onBookClick(book.uuid) },
                            onLongClick = { bookContextMenuUuid = book.uuid }
                        )
                    }
                }
            }
        }

        // Error state (overrides loading/empty)
        uiState.error?.let { errorMsg ->
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(text = errorMsg, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    // Import progress dialog (S1 — with Cancel button)
    if (importState is ImportState.Importing) {
        AlertDialog(
            onDismissRequest = { /* don't dismiss on outside touch */ },
            title = { Text(stringResource(R.string.bookshelf_importing)) },
            text = {
                Column {
                    if (importProgress < 0f) {
                        // Indeterminate
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { displayProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelImport() }) {
                    Text(stringResource(R.string.bookshelf_cancel))
                }
            }
        )
    }

    // Context menu dialog (long-press)
    bookContextMenuUuid?.let { uuid ->
        AlertDialog(
            onDismissRequest = { bookContextMenuUuid = null },
            title = { Text(stringResource(R.string.bookshelf_delete)) },
            text = { Text(stringResource(R.string.bookshelf_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    bookContextMenuUuid = null
                    bookToDelete = uuid
                }) {
                    Text(stringResource(R.string.bookshelf_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.reparseMetadata(uuid)
                    bookContextMenuUuid = null
                }) {
                    Text(stringResource(R.string.bookshelf_reparse_metadata))
                }
            }
        )
    }

    // Delete confirmation dialog
    bookToDelete?.let { uuid ->
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text(stringResource(R.string.bookshelf_delete)) },
            text = { Text(stringResource(R.string.bookshelf_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.softDeleteBook(uuid)
                    bookToDelete = null
                }) {
                    Text(stringResource(R.string.bookshelf_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text(stringResource(R.string.bookshelf_cancel))
                }
            }
        )
    }
}
