package com.epubreader.app.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AppError(val message: String, val cause: Throwable? = null)

@Singleton
class ErrorChannel @Inject constructor() {
    private val _errors = MutableSharedFlow<AppError>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val errors: SharedFlow<AppError> = _errors.asSharedFlow()
    suspend fun emit(error: AppError) { _errors.emit(error) }
    fun tryEmit(error: AppError): Boolean = _errors.tryEmit(error)
}
