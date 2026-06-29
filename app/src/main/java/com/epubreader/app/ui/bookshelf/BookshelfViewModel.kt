package com.epubreader.app.ui.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epubreader.app.R
import com.epubreader.app.core.AppCoroutineExceptionHandler
import com.epubreader.app.core.AppError
import com.epubreader.app.core.ErrorChannel
import com.epubreader.app.core.StringProvider
import com.epubreader.app.core.fold
import com.epubreader.app.core.onFailure
import com.epubreader.app.data.bookimport.BookImporter
import com.epubreader.app.data.bookimport.InsufficientStorageException
import com.epubreader.app.data.local.entity.BookEntity
import com.epubreader.app.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookshelfViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val bookImporter: BookImporter,
    private val exceptionHandler: AppCoroutineExceptionHandler,
    private val stringProvider: StringProvider,
    private val errorChannel: ErrorChannel
) : ViewModel() {

    val uiState: StateFlow<BookshelfUiState> = bookRepository.observeBooks()
        .map { books ->
            BookshelfUiState(isLoading = false, books = books.toPersistentList())
        }
        .catch { e ->
            emit(BookshelfUiState(isLoading = false, error = e.message))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BookshelfUiState()
        )

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _importProgress = MutableStateFlow(0f)
    val importProgress: StateFlow<Float> = _importProgress.asStateFlow()

    private var importJob: Job? = null

    fun importBook(uri: Uri) {
        importJob?.cancel()
        importJob = viewModelScope.launch(exceptionHandler.handler) {
            _importState.value = ImportState.Importing
            _importProgress.value = 0f
            val result = bookImporter.importBook(uri) { progress ->
                // Callback runs on IO thread (S7). MutableStateFlow.value is thread-safe.
                _importProgress.value = progress
            }
            result.fold(
                onSuccess = {
                    _importState.value = ImportState.Success
                    _importProgress.value = 1f
                },
                onFailure = { cause, _ ->
                    _importState.value = ImportState.Error(mapImportError(cause))
                }
            )
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _importState.value = ImportState.Idle
        _importProgress.value = 0f
    }

    fun resetImportState() {
        _importState.value = ImportState.Idle
        _importProgress.value = 0f
    }

    fun reparseMetadata(uuid: String) {
        viewModelScope.launch(exceptionHandler.handler) {
            val result = bookRepository.reparseMetadata(uuid)
            result.onFailure { cause, msg ->
                errorChannel.tryEmit(
                    AppError(msg ?: cause.message ?: stringProvider.get(R.string.error_reparse_failed), cause)
                )
            }
        }
    }

    fun softDeleteBook(uuid: String) {
        viewModelScope.launch(exceptionHandler.handler) {
            // M6: Delete errors route through ErrorChannel (consumed by EpubReaderApp Snackbar),
            // NOT through importState — avoids clobbering import pipeline state.
            bookRepository.softDeleteBook(uuid).fold(
                onSuccess = { /* Room Flow auto-updates uiState */ },
                onFailure = { cause, _ ->
                    errorChannel.tryEmit(
                        AppError(cause.message ?: stringProvider.get(R.string.error_delete_failed), cause)
                    )
                }
            )
        }
    }

    private fun mapImportError(cause: Throwable): String {
        return when (cause) {
            is InsufficientStorageException -> stringProvider.get(R.string.error_insufficient_storage)
            else -> stringProvider.get(R.string.error_import_failed)
        }
    }
}
