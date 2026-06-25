package com.epubreader.app.core.sync

import com.epubreader.app.core.Syncable

/** Decision from LWW merge comparison. */
sealed interface MergeDecision<out T : Syncable> {
    /** Remote record wins — upsert it with syncedAt set by caller. */
    data class Upsert<T : Syncable>(val record: T) : MergeDecision<T>
    /** Local record wins or is current — skip. */
    object Skip : MergeDecision<Nothing>
}

/**
 * Last-Write-Wins merge (symmetric, timestamp-based).
 * Oracle M1 fix: symmetric deletion priority (not "remote tombstone always wins").
 * Council RISK-E2: UUID tie-breaker for same-millisecond ties.
 * Council RISK-D3: clock skew clamping for future timestamps.
 *
 * Rules (in order):
 * 1. local == null → Upsert(remote)  (new from server)
 * 2. Clamp remote.updatedAt if it's more than SKEW_TOLERANCE_MS in the future
 * 3. remote.updatedAt > local.updatedAt → Upsert(remote)  (remote newer)
 * 4. remote.updatedAt < local.updatedAt → Skip  (local newer)
 * 5. Tie (equal updatedAt):
 *    a. if remote.isDeleted → Upsert(remote)  (deletion wins on tie)
 *    b. if local.isDeleted → Skip  (local deletion wins on tie)
 *    c. else: UUID tie-breaker — if remote.uuid > local.uuid lexicographically → Upsert(remote), else Skip
 *
 * @param remote The remote record from pullSince
 * @param local The local record from getById, or null if not present locally
 * @param nowMs Current wall-clock time for skew detection (injectable for testing)
 */
fun <T : Syncable> lwwMerge(remote: T, local: T?, nowMs: Long = System.currentTimeMillis()): MergeDecision<T> {
    // Rule 1: new from server
    if (local == null) return MergeDecision.Upsert(remote)

    // Rule 2: clamp clock skew (Council RISK-D3)
    val clampedRemoteUpdatedAt = if (remote.updatedAt > nowMs + SKEW_TOLERANCE_MS) {
        nowMs
    } else {
        remote.updatedAt
    }

    // Rule 3: remote newer
    if (clampedRemoteUpdatedAt > local.updatedAt) return MergeDecision.Upsert(remote)

    // Rule 4: local newer
    if (clampedRemoteUpdatedAt < local.updatedAt) return MergeDecision.Skip

    // Rule 5: tie — symmetric deletion priority (Oracle M1)
    // 5a: remote deleted on tie → upsert
    if (remote.isDeleted) return MergeDecision.Upsert(remote)
    // 5b: local deleted on tie → skip
    if (local.isDeleted) return MergeDecision.Skip
    // 5c: UUID tie-breaker (Council RISK-E2)
    return if (remote.uuid > local.uuid) MergeDecision.Upsert(remote) else MergeDecision.Skip
}

const val SKEW_TOLERANCE_MS = 60_000L // 1 minute — records with updatedAt > now + this are clamped
