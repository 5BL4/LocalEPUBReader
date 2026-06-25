package com.epubreader.app.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Type discriminator for sync records. */
enum class SyncType { BOOK, READING_PROGRESS, BOOKMARK, HIGHLIGHT, NOTE }

/** Sync metadata shared by all entity types (maps to Syncable fields). */
@Serializable
data class SyncMeta(
    val uuid: String,
    val isDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val syncedAt: Long? = null,
    val userId: String? = null
)

/**
 * Slim sync transport record (SF-2 domain mapping layer).
 * Oracle S3: type + meta + payload (JsonObject) — one mapper, not 5 sealed subclasses.
 * TODO Phase 8: consider typed sealed SyncPayload for security when real backend arrives (Council RISK-S1).
 */
@Serializable
data class SyncRecord(
    val type: SyncType,
    val meta: SyncMeta,
    val payload: JsonObject
)
