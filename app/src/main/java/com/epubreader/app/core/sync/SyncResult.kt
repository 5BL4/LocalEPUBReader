package com.epubreader.app.core.sync

/** Result of a sync cycle (Oracle S8). */
data class SyncResult(
    val pulled: Int,
    val pushed: Int,
    val conflicts: Int = 0,
    val errors: List<SyncError> = emptyList()
)

/** Per-type error during sync. */
data class SyncError(
    val type: SyncType,
    val cause: Throwable
)

/** Conflict detected during LWW merge (e.g., remote deleted but local edited). */
data class SyncConflict(
    val type: SyncType,
    val uuid: String,
    val description: String
)
