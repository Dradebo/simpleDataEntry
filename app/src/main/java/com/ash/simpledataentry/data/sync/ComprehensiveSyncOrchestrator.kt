package com.ash.simpledataentry.data.sync

import android.util.Log
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.data.local.AppDatabase
import com.ash.simpledataentry.data.session.EnhancedSessionManager
import com.ash.simpledataentry.domain.repository.DatasetInstancesRepository
import com.ash.simpledataentry.domain.repository.DatasetsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Comprehensive sync operation state
 */
data class ComprehensiveSyncState(
    val isRunning: Boolean = false,
    val phase: ComprehensiveSyncPhase = ComprehensiveSyncPhase.IDLE,
    val overallProgress: Int = 0,
    val currentOperation: String = "",
    val metadataProgress: Int = 0,
    val dataProgress: Int = 0,
    val uploadProgress: Int = 0,
    val totalOperations: Int = 0,
    val completedOperations: Int = 0,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val canCancel: Boolean = true,
    val lastFullSyncTime: Long? = null,
    val nextScheduledSync: Long? = null
)

/**
 * Phases of comprehensive sync
 */
enum class ComprehensiveSyncPhase {
    IDLE,
    INITIALIZING,
    SESSION_VALIDATION,
    METADATA_SYNC,
    DATA_DOWNLOAD,
    CONFLICT_RESOLUTION,
    DATA_UPLOAD,
    FINALIZATION,
    COMPLETE,
    ERROR,
    CANCELLED
}

/**
 * Sync operation scope
 */
enum class SyncScope {
    METADATA_ONLY,
    DATA_ONLY,
    FULL_SYNC,
    UPLOAD_ONLY
}

/**
 * Comprehensive sync result
 */
data class ComprehensiveSyncResult(
    val success: Boolean,
    val scope: SyncScope,
    val duration: Long,
    val metadataResult: SyncOperationResult? = null,
    val dataResult: SyncOperationResult? = null,
    val uploadResult: CompleteSyncResult? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

/**
 * Individual sync operation result
 */
data class SyncOperationResult(
    val success: Boolean,
    val itemsProcessed: Int,
    val duration: Long,
    val error: String? = null
)

/**
 * Comprehensive sync orchestrator that manages all aspects of data synchronization
 */
@Singleton
class ComprehensiveSyncOrchestrator @Inject constructor(
    private val sessionManager: SessionManager,
    private val enhancedSessionManager: EnhancedSessionManager,
    private val networkStateManager: NetworkStateManager,
    private val datasetsRepository: DatasetsRepository,
    private val datasetInstancesRepository: DatasetInstancesRepository,
    private val enhancedSyncOrchestrator: EnhancedSyncOrchestrator,
    private val database: AppDatabase
) {

    companion object {
        private const val TAG = "ComprehensiveSyncOrchestrator"
    }

    private val _syncState = MutableStateFlow(ComprehensiveSyncState())
    val syncState: StateFlow<ComprehensiveSyncState> = _syncState.asStateFlow()

    private var currentSyncJob: kotlinx.coroutines.Job? = null

    /**
     * Start comprehensive sync operation
     */
    suspend fun startComprehensiveSync(
        scope: SyncScope = SyncScope.FULL_SYNC,
        forceSync: Boolean = false,
        autoResolveConflicts: Boolean = false
    ): ComprehensiveSyncResult = withContext(Dispatchers.IO) {

        if (_syncState.value.isRunning && !forceSync) {
            return@withContext ComprehensiveSyncResult(
                success = false,
                scope = scope,
                duration = 0,
                errors = listOf("Sync already in progress")
            )
        }

        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        try {
            updateSyncState(
                isRunning = true,
                phase = ComprehensiveSyncPhase.INITIALIZING,
                overallProgress = 0,
                currentOperation = "Initializing comprehensive sync...",
                errors = emptyList(),
                warnings = emptyList()
            )

            // Phase 1: Session validation
            val sessionValid = validateSession()
            if (!sessionValid) {
                return@withContext ComprehensiveSyncResult(
                    success = false,
                    scope = scope,
                    duration = System.currentTimeMillis() - startTime,
                    errors = listOf("Session validation failed")
                )
            }

            // Phase 2: Network assessment
            val networkReady = assessNetworkReadiness()
            if (!networkReady && !scope.isOfflineCapable()) {
                return@withContext ComprehensiveSyncResult(
                    success = false,
                    scope = scope,
                    duration = System.currentTimeMillis() - startTime,
                    errors = listOf("Network not ready for sync operation")
                )
            }

            // Phase 3: Execute sync based on scope
            val operationResults = when (scope) {
                SyncScope.METADATA_ONLY -> executeMetadataSync()
                SyncScope.DATA_ONLY -> executeDataSync(autoResolveConflicts)
                SyncScope.FULL_SYNC -> executeFullSync(autoResolveConflicts)
                SyncScope.UPLOAD_ONLY -> executeUploadOnly(autoResolveConflicts)
            }

            // Phase 4: Finalization
            updateSyncState(
                phase = ComprehensiveSyncPhase.FINALIZATION,
                overallProgress = 95,
                currentOperation = "Finalizing sync..."
            )

            finalizeSync(operationResults)

            val duration = System.currentTimeMillis() - startTime
            val success = operationResults.all {
                when (it) {
                    is SyncOperationResult -> it.success
                    is CompleteSyncResult -> it.success
                    else -> false
                }
            }

            updateSyncState(
                isRunning = false,
                phase = if (success) ComprehensiveSyncPhase.COMPLETE else ComprehensiveSyncPhase.ERROR,
                overallProgress = 100,
                currentOperation = if (success) "Sync completed successfully" else "Sync completed with errors",
                lastFullSyncTime = if (success && scope == SyncScope.FULL_SYNC) System.currentTimeMillis() else _syncState.value.lastFullSyncTime
            )

            ComprehensiveSyncResult(
                success = success,
                scope = scope,
                duration = duration,
                metadataResult = operationResults.find { it is SyncOperationResult } as? SyncOperationResult,
                uploadResult = operationResults.find { it is CompleteSyncResult } as? CompleteSyncResult,
                errors = errors,
                warnings = warnings
            )

        } catch (e: Exception) {
            Log.e(TAG, "Comprehensive sync failed: ${e.message}", e)

            updateSyncState(
                isRunning = false,
                phase = ComprehensiveSyncPhase.ERROR,
                overallProgress = 0,
                currentOperation = "Sync failed: ${e.message}",
                errors = listOf(e.message ?: "Unknown error")
            )

            ComprehensiveSyncResult(
                success = false,
                scope = scope,
                duration = System.currentTimeMillis() - startTime,
                errors = listOf(e.message ?: "Unknown error")
            )
        }
    }

    /**
     * Cancel ongoing comprehensive sync
     */
    fun cancelSync() {
        currentSyncJob?.cancel()
        enhancedSyncOrchestrator.cancelSync()

        updateSyncState(
            isRunning = false,
            phase = ComprehensiveSyncPhase.CANCELLED,
            currentOperation = "Sync cancelled by user"
        )
    }

    /**
     * Private implementation methods
     */

    private suspend fun validateSession(): Boolean {
        updateSyncState(
            phase = ComprehensiveSyncPhase.SESSION_VALIDATION,
            overallProgress = 5,
            currentOperation = "Validating session..."
        )

        return try {
            if (!sessionManager.isSessionActive()) {
                // Attempt session restoration
                val restored = enhancedSessionManager.validateAndRefreshSession(
                    android.content.ContextWrapper(null) // This would need proper context injection
                )
                if (!restored) {
                    Log.w(TAG, "Session validation failed and could not be restored")
                    false
                } else {
                    true
                }
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session validation error: ${e.message}", e)
            false
        }
    }

    private suspend fun assessNetworkReadiness(): Boolean {
        updateSyncState(
            phase = ComprehensiveSyncPhase.INITIALIZING,
            overallProgress = 10,
            currentOperation = "Assessing network readiness..."
        )

        val networkState = networkStateManager.networkState.value
        return networkState.isConnected && networkState.connectionQuality != ConnectionQuality.POOR
    }

    private suspend fun executeMetadataSync(): List<Any> {
        updateSyncState(
            phase = ComprehensiveSyncPhase.METADATA_SYNC,
            overallProgress = 20,
            currentOperation = "Syncing metadata..."
        )

        val results = mutableListOf<Any>()

        try {
            // Sync datasets
            val datasetResult = datasetsRepository.syncDatasets()
            if (datasetResult.isSuccess) {
                updateSyncState(metadataProgress = 50)

                // Sync dataset instances
                datasetInstancesRepository.syncDatasetInstances()
                updateSyncState(metadataProgress = 100)

                results.add(
                    SyncOperationResult(
                        success = true,
                        itemsProcessed = 1, // Would need actual count
                        duration = 0 // Would need actual timing
                    )
                )
            } else {
                results.add(
                    SyncOperationResult(
                        success = false,
                        itemsProcessed = 0,
                        duration = 0,
                        error = datasetResult.exceptionOrNull()?.message
                    )
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Metadata sync failed: ${e.message}", e)
            results.add(
                SyncOperationResult(
                    success = false,
                    itemsProcessed = 0,
                    duration = 0,
                    error = e.message
                )
            )
        }

        return results
    }

    private suspend fun executeDataSync(autoResolveConflicts: Boolean): List<Any> {
        updateSyncState(
            phase = ComprehensiveSyncPhase.DATA_UPLOAD,
            overallProgress = 60,
            currentOperation = "Syncing data..."
        )

        val results = mutableListOf<Any>()

        try {
            val syncResult = enhancedSyncOrchestrator.startFullSync(
                forceSync = true,
                autoResolveConflicts = autoResolveConflicts
            )

            updateSyncState(dataProgress = 100)
            results.add(syncResult)

        } catch (e: Exception) {
            Log.e(TAG, "Data sync failed: ${e.message}", e)
            results.add(
                CompleteSyncResult(
                    success = false,
                    syncedItems = 0,
                    skippedItems = 0,
                    conflictsResolved = 0,
                    error = e.message
                )
            )
        }

        return results
    }

    private suspend fun executeFullSync(autoResolveConflicts: Boolean): List<Any> {
        val results = mutableListOf<Any>()

        // Execute metadata sync first
        results.addAll(executeMetadataSync())

        // Then execute data sync
        updateSyncState(overallProgress = 50)
        results.addAll(executeDataSync(autoResolveConflicts))

        return results
    }

    private suspend fun executeUploadOnly(autoResolveConflicts: Boolean): List<Any> {
        updateSyncState(
            phase = ComprehensiveSyncPhase.DATA_UPLOAD,
            overallProgress = 30,
            currentOperation = "Uploading local data..."
        )

        return executeDataSync(autoResolveConflicts)
    }

    private suspend fun finalizeSync(results: List<Any>) {
        try {
            // Perform any cleanup operations
            val successfulResults = results.count { result ->
                when (result) {
                    is SyncOperationResult -> result.success
                    is CompleteSyncResult -> result.success
                    else -> false
                }
            }

            updateSyncState(
                completedOperations = successfulResults,
                totalOperations = results.size
            )

        } catch (e: Exception) {
            Log.e(TAG, "Finalization failed: ${e.message}", e)
        }
    }

    private fun updateSyncState(
        isRunning: Boolean = _syncState.value.isRunning,
        phase: ComprehensiveSyncPhase = _syncState.value.phase,
        overallProgress: Int = _syncState.value.overallProgress,
        currentOperation: String = _syncState.value.currentOperation,
        metadataProgress: Int = _syncState.value.metadataProgress,
        dataProgress: Int = _syncState.value.dataProgress,
        uploadProgress: Int = _syncState.value.uploadProgress,
        totalOperations: Int = _syncState.value.totalOperations,
        completedOperations: Int = _syncState.value.completedOperations,
        errors: List<String> = _syncState.value.errors,
        warnings: List<String> = _syncState.value.warnings,
        canCancel: Boolean = _syncState.value.canCancel,
        lastFullSyncTime: Long? = _syncState.value.lastFullSyncTime,
        nextScheduledSync: Long? = _syncState.value.nextScheduledSync
    ) {
        _syncState.value = _syncState.value.copy(
            isRunning = isRunning,
            phase = phase,
            overallProgress = overallProgress,
            currentOperation = currentOperation,
            metadataProgress = metadataProgress,
            dataProgress = dataProgress,
            uploadProgress = uploadProgress,
            totalOperations = totalOperations,
            completedOperations = completedOperations,
            errors = errors,
            warnings = warnings,
            canCancel = canCancel,
            lastFullSyncTime = lastFullSyncTime,
            nextScheduledSync = nextScheduledSync
        )
    }

    private fun SyncScope.isOfflineCapable(): Boolean {
        return this == SyncScope.UPLOAD_ONLY
    }
}