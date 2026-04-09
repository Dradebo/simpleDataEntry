package com.ash.simpledataentry.data.sync

import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified sync service that provides both legacy and enhanced sync capabilities
 */
@Singleton
class SyncService @Inject constructor(
    private val enhancedSyncOrchestrator: EnhancedSyncOrchestrator,
    private val syncQueueManager: SyncQueueManager
) {

    companion object {
        private const val TAG = "SyncService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Enhanced sync state from the orchestrator
     */
    val enhancedSyncState: StateFlow<EnhancedSyncState> = enhancedSyncOrchestrator.syncState

    /**
     * Legacy sync state for backward compatibility
     */
    val legacySyncState: StateFlow<SyncState> = enhancedSyncOrchestrator.syncState.map { enhancedState ->
        SyncState(
            isRunning = enhancedState.isRunning,
            progress = enhancedState.progress,
            queueSize = enhancedState.queueSize,
            lastSyncAttempt = enhancedState.lastSyncAttempt,
            lastSuccessfulSync = enhancedState.lastSuccessfulSync,
            error = enhancedState.error,
            step = enhancedState.message
        )
    }.stateIn(
        scope = serviceScope,
        started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
        initialValue = SyncState()
    )

    /**
     * Start enhanced sync with conflict detection and transactional safety
     */
    suspend fun startEnhancedSync(
        forceSync: Boolean = false,
        autoResolveConflicts: Boolean = false
    ): CompleteSyncResult {
        Log.d(TAG, "Starting enhanced sync - force: $forceSync, autoResolve: $autoResolveConflicts")
        return enhancedSyncOrchestrator.startFullSync(forceSync, autoResolveConflicts)
    }

    /**
     * Start legacy sync for backward compatibility
     */
    suspend fun startLegacySync(forceSync: Boolean = false): Result<Unit> {
        Log.d(TAG, "Starting legacy sync - force: $forceSync")
        return syncQueueManager.startSync(forceSync)
    }

    /**
     * Provide conflict resolutions to the orchestrator
     */
    fun provideConflictResolutions(resolutions: Map<DataConflict, ConflictResolution>) {
        enhancedSyncOrchestrator.provideConflictResolutions(resolutions)
    }

    /**
     * Cancel ongoing sync operation
     */
    fun cancelSync() {
        enhancedSyncOrchestrator.cancelSync()
    }

    /**
     * Check if sync is currently running
     */
    fun isSyncRunning(): Boolean {
        return enhancedSyncState.value.isRunning
    }

    /**
     * Get queue size for pending data
     */
    fun getQueueSize(): Int {
        return enhancedSyncState.value.queueSize
    }

    /**
     * Get current conflicts requiring user resolution
     */
    fun getCurrentConflicts(): List<DataConflict> {
        return enhancedSyncState.value.conflicts
    }

    /**
     * Check if sync needs user input for conflict resolution
     */
    fun needsUserInput(): Boolean {
        return enhancedSyncState.value.needsUserInput
    }
}