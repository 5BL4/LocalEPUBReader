package com.epubreader.app.core

import kotlinx.coroutines.CoroutineExceptionHandler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backstop coroutine exception handler. Logs and emits to [ErrorChannel].
 * PRIMARY guard is local try-catch in repositories (NEVER #26); this is the safety net for
 * any exception that escapes a viewModelScope.launch child coroutine.
 */
@Singleton
class AppCoroutineExceptionHandler @Inject constructor(
    private val errorChannel: ErrorChannel
) {
    val handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("EpubReader", "Uncaught coroutine exception", throwable)
        errorChannel.tryEmit(AppError(throwable.message ?: "Unexpected error", throwable))
    }
}
