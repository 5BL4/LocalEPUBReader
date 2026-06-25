package com.epubreader.app.ui.bookshelf

sealed interface ImportState {
    data object Idle : ImportState
    data object Importing : ImportState
    data object Success : ImportState
    data class Error(val message: String) : ImportState
}
