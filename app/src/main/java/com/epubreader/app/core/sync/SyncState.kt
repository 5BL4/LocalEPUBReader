package com.epubreader.app.core.sync

/** Sync state for UI observation. */
sealed interface SyncState {
    object Idle : SyncState
    object Syncing : SyncState
    data class Success(val result: SyncResult) : SyncState
    data class Error(val result: SyncResult) : SyncState  // S7: structured errors, not collapsed string
}
