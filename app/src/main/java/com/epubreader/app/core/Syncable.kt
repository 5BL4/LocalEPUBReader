package com.epubreader.app.core

/** Contract implemented by all Room entities that participate in Offline-First sync. */
interface Syncable {
    val uuid: String
    val isDeleted: Boolean
    val createdAt: Long
    val updatedAt: Long
    val syncedAt: Long?
}

/** Cursor for incremental sync pull (Last-Write-Wins). */
data class SyncCursor(val updatedAt: Long, val uuid: String? = null)

/** One page of pulled sync items. */
data class SyncPage<out T>(val items: List<T>, val nextCursor: SyncCursor? = null)

/** Acknowledgement of a push batch: which uuids the server accepted + their server-side updatedAt. */
data class PushAck(val ackedUuids: List<String>, val serverUpdatedAt: Map<String, Long> = emptyMap())
