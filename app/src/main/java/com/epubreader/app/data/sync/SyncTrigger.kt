package com.epubreader.app.data.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Triggers sync on app ON_START with debounce (Oracle M4).
 * Registered in MainActivity (Council RISK-L1 — Hilt ready by then).
 * Sync runs on SyncManager's ApplicationScope (survives Activity destruction, Council RISK-L2).
 */
@Singleton
class SyncTrigger @Inject constructor(
    private val syncManager: SyncManager
) : DefaultLifecycleObserver {

    private var lastSyncTimeMs = 0L

    override fun onStart(owner: LifecycleOwner) {
        val now = System.currentTimeMillis()
        if (now - lastSyncTimeMs > DEBOUNCE_MS) {
            lastSyncTimeMs = now
            syncManager.launchSync()
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 30_000L
    }
}
