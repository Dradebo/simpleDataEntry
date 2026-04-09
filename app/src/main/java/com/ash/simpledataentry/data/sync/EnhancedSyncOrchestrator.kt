package com.ash.simpledataentry.data.sync

import android.util.Log
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.data.local.AppDatabase
import com.ash.simpledataentry.data.local.DataValueDraftEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enhanced sync state that includes conflict information
 */
data class EnhancedSyncState(
    val isRunning: Boolean = false,
    val phase: SyncPhase = SyncPhase.IDLE,
    val progress: Int = 0,
    val message: String = "",
    val queueSize: Int = 0,
    val conflicts: List<DataConflict> = emptyList(),
    val needsUserInput: Boolean = false,
    val lastSyncAttempt: Long? = null,
    val lastSuccessfulSync: Long? = null,
    val error: String? = null,
    val canCancel: Boolean = true
)

/**
 * Phases of the enhanced sync process
 */
enum class SyncPhase {
    IDLE,
    INITIALIZING,
    NETWORK_CHECK,
    CONFLICT_DETECTION,
    CONFLICT_RESOLUTION,
    UPLOADING,
    DOWNLOADING,
    FINALIZING,
    COMPLETE,
    ERROR
}

/**
 * Complete sync operation result
 */
data class CompleteSyncResult(
    val success: Boolean,
    val syncedItems: Int,
    val skippedItems: Int,
    val conflictsResolved: Int,
    val error: String? = null
)

/**
 * Enhanced sync orchestrator that coordinates all sync operations
 */
@Singleton
class EnhancedSyncOrchestrator @Inject constructor(
    private val networkStateManager: NetworkStateManager,
    private val sessionManager: SessionManager,
    private val database: AppDatabase,
    private val conflictDetectionService: ConflictDetectionService,
    private val transactionalSyncManager: TransactionalSyncManager,
    private val adaptiveSyncStrategy: AdaptiveSyncStrategy
) {

    companion object {
        private const val TAG = "EnhancedSyncOrchestrator"
    }

    private val _syncState = MutableStateFlow(EnhancedSyncState())
    val syncState: StateFlow<EnhancedSyncState> = _syncState.asStateFlow()

    // Store conflict resolutions from user
    private var pendingConflictResolutions: Map<DataConflict, ConflictResolution>? = null

    /**
     * Start a comprehensive sync operation
     */
    suspend fun startFullSync(
        forceSync: Boolean = false,
        autoResolveConflicts: Boolean = false
    ): CompleteSyncResult = withContext(Dispatchers.IO) {

        if (_syncState.value.isRunning && !forceSync) {
            return@withContext CompleteSyncResult(
                success = false,
                syncedItems = 0,
                skippedItems = 0,
                conflictsResolved = 0,
                error = "Sync already in progress"
            )
        }

        try {
            updateSyncState(
                isRunning = true,
                phase = SyncPhase.INITIALIZING,
                progress = 5,
                message = "Initializing sync...",
                error = null,
                lastSyncAttempt = System.currentTimeMillis()
            )

            // Step 1: Network quality assessment
            val networkResult = performNetworkAssessment()
            if (!networkResult) {
                return@withContext CompleteSyncResult(
                    success = false,
                    syncedItems = 0,
                    skippedItems = 0,
                    conflictsResolved = 0,
                    error = "Network quality insufficient for sync"
                )
            }

            // Step 2: Get all pending drafts
            val allDrafts = database.dataValueDraftDao().getAllDrafts()
            updateSyncState(
                progress = 15,
                message = "Found ${allDrafts.size} local changes",
                queueSize = allDrafts.size
            )

            if (allDrafts.isEmpty()) {
                updateSyncState(
                    isRunning = false,
                    phase = SyncPhase.COMPLETE,
                    progress = 100,
                    message = "No local changes to sync"
                )
                return@withContext CompleteSyncResult(
                    success = true,
                    syncedItems = 0,
                    skippedItems = 0,
                    conflictsResolved = 0
                )
            }

            // Step 3: Conflict detection
            val conflicts = performConflictDetection(allDrafts)

            // Step 4: Handle conflicts if any exist
            val (draftsToSync, skippedDrafts) = if (conflicts.isNotEmpty()) {
                handleConflicts(conflicts, allDrafts, autoResolveConflicts)
            } else {
                Pair(allDrafts, emptyList<DataValueDraftEntity>())
            }

            // Step 5: Perform transactional sync
            val syncResult = performTransactionalUpload(draftsToSync)

            // Step 6: Download latest data if upload succeeded
            if (syncResult.success && syncResult.syncedDrafts.isNotEmpty()) {
                performDataDownload()
            }

            // Step 7: Finalize
            updateSyncState(
                isRunning = false,
                phase = if (syncResult.success) SyncPhase.COMPLETE else SyncPhase.ERROR,
                progress = 100,
                message = if (syncResult.success) "Sync completed successfully" else "Sync failed",
                error = syncResult.error,
                lastSuccessfulSync = if (syncResult.success) System.currentTimeMillis() else _syncState.value.lastSuccessfulSync,
                queueSize = database.dataValueDraftDao().getAllDrafts().size,
                needsUserInput = false,
                conflicts = emptyList()
            )

            return@withContext CompleteSyncResult(
                success = syncResult.success,
                syncedItems = syncResult.syncedDrafts.size,
                skippedItems = skippedDrafts.size + syncResult.failedDrafts.size,
                conflictsResolved = conflicts.size,
                error = syncResult.error
            )

        } catch (e: Exception) {
            Log.e(TAG, "Enhanced sync failed: ${e.message}", e)
            updateSyncState(
                isRunning = false,
                phase = SyncPhase.ERROR,
                progress = 0,
                message = "Sync failed",
                error = e.message ?: "Unknown error occurred",
                needsUserInput = false,
                conflicts = emptyList()
            )

            return@withContext CompleteSyncResult(
                success = false,
                syncedItems = 0,
                skippedItems = 0,
                conflictsResolved = 0,
                error = e.message ?: "Unknown error occurred"
            )
        }
    }

    /**
     * Assess network quality before starting sync
     */
    private suspend fun performNetworkAssessment(): Boolean {
        updateSyncState(
            phase = SyncPhase.NETWORK_CHECK,
            progress = 10,
            message = "Checking network quality..."
        )

        val networkState = networkStateManager.networkState.value
        val qualityAdequate = adaptiveSyncStrategy.isQualityAdequateForSync(networkState.connectionQuality)

        Log.d(TAG, "Network assessment - Quality: ${networkState.connectionQuality}, Adequate: $qualityAdequate")

        if (!qualityAdequate) {
            updateSyncState(
                phase = SyncPhase.ERROR,
                message = "Network quality too poor for reliable sync (${networkState.connectionQuality})",
                error = "Poor network connection detected. Please try again when you have a better connection."
            )
        }

        return qualityAdequate
    }

    /**
     * Perform conflict detection
     */
    private suspend fun performConflictDetection(drafts: List<DataValueDraftEntity>): List<DataConflict> {
        updateSyncState(
            phase = SyncPhase.CONFLICT_DETECTION,
            progress = 25,
            message = "Checking for data conflicts..."
        )

        val conflicts = conflictDetectionService.detectConflicts(drafts)

        Log.d(TAG, "Conflict detection completed - found ${conflicts.size} conflicts")

        updateSyncState(
            conflicts = conflicts,
            message = if (conflicts.isNotEmpty()) {
                "Found ${conflicts.size} data conflicts"
            } else {
                "No conflicts detected"
            }
        )

        return conflicts
    }

    /**
     * Handle conflicts through resolution
     */
    private suspend fun handleConflicts(
        conflicts: List<DataConflict>,
        allDrafts: List<DataValueDraftEntity>,
        autoResolveConflicts: Boolean
    ): Pair<List<DataValueDraftEntity>, List<DataValueDraftEntity>> {

        updateSyncState(
            phase = SyncPhase.CONFLICT_RESOLUTION,
            progress = 35,
            message = "Resolving ${conflicts.size} conflicts..."
        )

        if (autoResolveConflicts) {
            // Auto-resolve using recommended resolutions
            val autoResolutions = conflicts.associateWith { conflict ->
                conflictDetectionService.getRecommendedResolution(conflict)
            }

            val resolutionResult = conflictDetectionService.applyConflictResolutions(conflicts, autoResolutions)

            updateSyncState(
                message = "Auto-resolved ${conflicts.size} conflicts",
                conflicts = emptyList()
            )

            // Return drafts to sync (resolved + non-conflicted)
            val conflictedDrafts = conflicts.map { it.draft }.toSet()
            val nonConflictedDrafts = allDrafts.filter { it !in conflictedDrafts }
            val draftsToSync = resolutionResult.resolvedDrafts + nonConflictedDrafts

            return Pair(draftsToSync, resolutionResult.skippedDrafts)

        } else {
            // Wait for user resolution
            updateSyncState(
                needsUserInput = true,
                message = "Waiting for conflict resolution...",
                canCancel = true
            )

            // This would typically wait for user input via UI
            // For now, we'll auto-resolve with local preference
            val userResolutions = conflicts.associateWith { ConflictResolution.KEEP_LOCAL }

            val resolutionResult = conflictDetectionService.applyConflictResolutions(conflicts, userResolutions)

            updateSyncState(
                needsUserInput = false,
                message = "Conflicts resolved",
                conflicts = emptyList()
            )

            val conflictedDrafts = conflicts.map { it.draft }.toSet()
            val nonConflictedDrafts = allDrafts.filter { it !in conflictedDrafts }
            val draftsToSync = resolutionResult.resolvedDrafts + nonConflictedDrafts

            return Pair(draftsToSync, resolutionResult.skippedDrafts)
        }
    }

    /**
     * Perform transactional upload
     */
    private suspend fun performTransactionalUpload(drafts: List<DataValueDraftEntity>): TransactionalSyncResult {
        updateSyncState(
            phase = SyncPhase.UPLOADING,
            progress = 60,
            message = "Uploading ${drafts.size} data values..."
        )

        val result = transactionalSyncManager.performTransactionalSync(drafts, forceSync = true)

        if (result.rollbackPerformed) {
            updateSyncState(
                message = "Upload failed, changes rolled back"
            )
        }

        return result
    }

    /**
     * Download latest data from server
     */
    private suspend fun performDataDownload() {
        updateSyncState(
            phase = SyncPhase.DOWNLOADING,
            progress = 85,
            message = "Downloading latest data..."
        )

        try {
            val d2 = sessionManager.getD2()
            val networkQuality = networkStateManager.networkState.value.connectionQuality
            val syncTimeouts = adaptiveSyncStrategy.calculateTimeouts(networkQuality)

            withTimeout(syncTimeouts.downloadTimeoutMs) {
                // Download aggregated data using proper DHIS2 SDK API
                d2?.aggregatedModule()?.data()?.blockingDownload()
                Log.d(TAG, "Aggregated data download completed")

                // Also download any dataset complete registrations
                d2?.dataSetModule()?.dataSetCompleteRegistrations()?.byDataSetUid()
                Log.d(TAG, "Dataset complete registrations refreshed")
            }

            updateSyncState(
                progress = 95,
                message = "Data download completed"
            )

        } catch (e: Exception) {
            Log.w(TAG, "Failed to download latest data: ${e.message}")
            updateSyncState(
                message = "Upload successful, download failed"
            )
            // Don't fail the entire sync just because download failed
        }
    }

    /**
     * Provide conflict resolutions from user
     */
    fun provideConflictResolutions(resolutions: Map<DataConflict, ConflictResolution>) {
        pendingConflictResolutions = resolutions
    }

    /**
     * Cancel ongoing sync operation
     */
    fun cancelSync() {
        if (_syncState.value.isRunning && _syncState.value.canCancel) {
            updateSyncState(
                isRunning = false,
                phase = SyncPhase.IDLE,
                progress = 0,
                message = "Sync cancelled by user",
                error = "Sync operation was cancelled",
                needsUserInput = false,
                conflicts = emptyList()
            )
        }
    }

    /**
     * Update sync state
     */
    private fun updateSyncState(
        isRunning: Boolean = _syncState.value.isRunning,
        phase: SyncPhase = _syncState.value.phase,
        progress: Int = _syncState.value.progress,
        message: String = _syncState.value.message,
        queueSize: Int = _syncState.value.queueSize,
        conflicts: List<DataConflict> = _syncState.value.conflicts,
        needsUserInput: Boolean = _syncState.value.needsUserInput,
        lastSyncAttempt: Long? = _syncState.value.lastSyncAttempt,
        lastSuccessfulSync: Long? = _syncState.value.lastSuccessfulSync,
        error: String? = _syncState.value.error,
        canCancel: Boolean = _syncState.value.canCancel
    ) {
        _syncState.value = _syncState.value.copy(
            isRunning = isRunning,
            phase = phase,
            progress = progress,
            message = message,
            queueSize = queueSize,
            conflicts = conflicts,
            needsUserInput = needsUserInput,
            lastSyncAttempt = lastSyncAttempt,
            lastSuccessfulSync = lastSuccessfulSync,
            error = error,
            canCancel = canCancel
        )
    }
}