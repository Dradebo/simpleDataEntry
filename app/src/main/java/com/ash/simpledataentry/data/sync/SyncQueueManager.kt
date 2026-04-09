package com.ash.simpledataentry.data.sync

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.data.DatabaseProvider
import com.ash.simpledataentry.data.D2Provider
import com.ash.simpledataentry.data.local.DataValueDraftEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class SyncState(
    val isRunning: Boolean = false,
    val queueSize: Int = 0,
    val lastSyncAttempt: Long? = null,
    val lastSuccessfulSync: Long? = null,
    val failedAttempts: Int = 0,
    val error: String? = null
)

enum class SyncPhase(val title: String, val defaultDetail: String) {
    INITIALIZING("Initializing Sync", "Preparing data for upload..."),
    PREPARING("Preparing", "Preparing data for sync..."),
    VALIDATING_CONNECTION("Validating Connection", "Checking server connection..."),
    SYNCING_METADATA("Syncing Metadata", "Downloading metadata structure..."),
    DOWNLOADING_METADATA("Downloading Metadata", "Getting metadata structure..."),
    UPLOADING_DATA("Uploading Data", "Sending data to server..."),
    DOWNLOADING_DATA("Downloading Data", "Getting data from server..."),
    DOWNLOADING_DATASETS("Downloading Datasets", "Getting aggregate data..."),
    DOWNLOADING_TRACKER_DATA("Downloading Tracker Data", "Getting enrollment data..."),
    DOWNLOADING_EVENTS("Downloading Events", "Getting event data..."),
    FINALIZING("Finalizing", "Completing sync process...")
}

sealed class SyncError {
    data class NetworkError(val message: String, val canAutoRetry: Boolean = true) : SyncError()
    data class ValidationError(val message: String) : SyncError()
    data class ServerError(val message: String, val statusCode: Int? = null) : SyncError()
    data class TimeoutError(val message: String, val canAutoRetry: Boolean = true) : SyncError()
    data class AuthenticationError(val message: String) : SyncError()
    data class UnknownError(val message: String) : SyncError()
}

data class DetailedSyncProgress(
    val phase: SyncPhase,
    val overallPercentage: Int,
    val phaseTitle: String,
    val phaseDetail: String,
    val canNavigateBack: Boolean = false,
    val error: SyncError? = null,
    val isAutoRetrying: Boolean = false,
    val autoRetryCountdown: Int? = null,
    // Enhanced progress tracking
    val currentItem: Int = 0,
    val totalItems: Int = 0,
    val processedItems: Int = currentItem, // Alias for compatibility
    val itemDescription: String? = null,
    val estimatedTimeRemaining: Long? = null // milliseconds
) {
    companion object {
        fun initial() = DetailedSyncProgress(
            phase = SyncPhase.INITIALIZING,
            overallPercentage = 0,
            phaseTitle = SyncPhase.INITIALIZING.title,
            phaseDetail = SyncPhase.INITIALIZING.defaultDetail
        )

        fun error(error: SyncError) = DetailedSyncProgress(
            phase = SyncPhase.INITIALIZING,
            overallPercentage = 0,
            phaseTitle = "Sync Failed",
            phaseDetail = when (error) {
                is SyncError.NetworkError -> error.message
                is SyncError.ValidationError -> error.message
                is SyncError.ServerError -> error.message
                is SyncError.TimeoutError -> error.message
                is SyncError.AuthenticationError -> error.message
                is SyncError.UnknownError -> error.message
            },
            canNavigateBack = true,
            error = error
        )
    }
}

@Singleton
class SyncQueueManager @Inject constructor(
    private val networkStateManager: NetworkStateManager,
    private val sessionManager: SessionManager,
    private val databaseProvider: DatabaseProvider,
    private val context: Context,
    private val d2Provider: D2Provider
) {

    private val tag = "SyncQueueManager"
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Wake lock for sync resilience
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _detailedProgress = MutableStateFlow<DetailedSyncProgress?>(null)
    val detailedProgress: StateFlow<DetailedSyncProgress?> = _detailedProgress.asStateFlow()

    private var syncJob: Job? = null
    private var networkMonitorJob: Job? = null
    private var autoRetryJob: Job? = null

    // DHIS2 SDK standard timeout configuration (v1.3.1+)
    private val uploadTimeoutMs = 600000L // 10 minutes - DHIS2 Android SDK standard since v1.3.1
    private val downloadTimeoutMs = 300000L // 5 minutes - proportional to upload timeout
    private val connectionTimeoutMs = 45000L // 45 seconds - reasonable for connection establishment
    private val connectionValidationTimeoutMs = 15000L // 15 seconds for validation calls
    private val overallSyncTimeoutMs = 600000L // 10 minutes max - aligned with DHIS2 standard

    // Robust retry configuration for field conditions
    private val maxRetryAttempts = 5 // Multiple attempts for robust sync resilience
    private val baseRetryDelayMs = 3000L // Shorter initial delay
    private val maxRetryDelayMs = 15000L // Allow longer delays for network recovery
    private val maxConsecutiveFailures = 3 // Allow more failures before giving up

    // Chunked upload configuration for reliability
    private val maxChunkSize = 50 // Maximum data values per chunk to prevent timeouts
    private val minChunkSize = 10 // Minimum chunk size for failed conditions
    private val baseChunkSize = 30 // Base chunk size for normal conditions
    private val chunkUploadTimeoutMs = 45000L // 45 seconds per chunk

    // Wake lock timeout aligned with DHIS2 sync timeout (10 minutes)
    private val wakeLockTimeoutMs = 600000L
    
    init {
        startNetworkMonitoring()
    }
    
    private fun startNetworkMonitoring() {
        networkMonitorJob = syncScope.launch {
            networkStateManager.networkState.collect { networkState ->
                if (networkState.hasInternet && _syncState.value.queueSize > 0 && !_syncState.value.isRunning) {
                    Log.d(tag, "Network available, starting queued sync")
                    startSync()
                }
            }
        }
    }
    
    suspend fun queueForSync() {
        val currentQueueSize = databaseProvider.getCurrentDatabase().dataValueDraftDao().getAllDrafts().size
        _syncState.value = _syncState.value.copy(queueSize = currentQueueSize)
        
        if (networkStateManager.isOnline() && !_syncState.value.isRunning) {
            startSync()
        } else {
            Log.d(tag, "Queued for sync - Network available: ${networkStateManager.isOnline()}, Sync running: ${_syncState.value.isRunning}")
        }
    }
    
    suspend fun startSync(forceSync: Boolean = false): Result<Unit> {
        if (_syncState.value.isRunning && !forceSync) {
            return Result.failure(Exception("Sync already in progress"))
        }

        syncJob?.cancel()
        syncJob = syncScope.launch {
            try {
                // Add overall sync timeout to prevent hanging during screen lock
                withTimeout(overallSyncTimeoutMs) { // 15 minutes maximum for entire sync operation
                    performSync()
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(tag, "Sync operation timed out after ${overallSyncTimeoutMs/1000} seconds")
                setErrorState(SyncError.TimeoutError("Sync timed out - may be caused by screen lock or network issues"))
            } catch (e: Exception) {
                Log.e(tag, "Sync operation failed", e)
                setErrorState(classifyError(e))
            }
        }

        return try {
            syncJob?.join()
            if (_syncState.value.error == null) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(_syncState.value.error))
            }
        } catch (e: Exception) {
            // Ensure state is reset even if join() fails
            _syncState.value = _syncState.value.copy(
                isRunning = false,
                error = e.message ?: "Sync failed"
            )
            Result.failure(e)
        }
    }

    suspend fun startDownloadOnlySync(forceSync: Boolean = false): Result<Unit> {
        if (_syncState.value.isRunning && !forceSync) {
            return Result.failure(Exception("Sync already in progress"))
        }

        syncJob?.cancel()
        syncJob = syncScope.launch {
            try {
                withTimeout(downloadTimeoutMs) { // 5 minutes for download-only
                    performDownloadOnlySync()
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(tag, "Download-only sync timed out after ${downloadTimeoutMs/1000} seconds")
                setErrorState(SyncError.TimeoutError("Download sync timed out"))
            } catch (e: Exception) {
                Log.e(tag, "Download-only sync failed", e)
                setErrorState(classifyError(e))
            }
        }

        return try {
            syncJob?.join()
            if (_syncState.value.error == null) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(_syncState.value.error))
            }
        } catch (e: Exception) {
            _syncState.value = _syncState.value.copy(
                isRunning = false,
                error = e.message ?: "Download sync failed"
            )
            Result.failure(e)
        }
    }

    suspend fun startSyncForInstance(
        datasetId: String,
        period: String,
        orgUnit: String,
        attributeOptionCombo: String,
        forceSync: Boolean = false
    ): Result<Unit> {
        if (_syncState.value.isRunning && !forceSync) {
            return Result.failure(Exception("Sync already in progress"))
        }

        syncJob?.cancel()
        syncJob = syncScope.launch {
            try {
                // Reasonable timeout for instance sync aligned with upload timeout
                withTimeout(uploadTimeoutMs) { // Use same timeout as regular uploads
                    performSyncForInstance(datasetId, period, orgUnit, attributeOptionCombo)
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(tag, "Instance sync operation timed out after ${uploadTimeoutMs/1000} seconds")
                _syncState.value = _syncState.value.copy(
                    isRunning = false,
                    error = "Sync timed out - may be caused by screen lock or network issues",
                    failedAttempts = _syncState.value.failedAttempts + 1
                )
                clearDetailedProgress() // CRITICAL: Clear overlay on timeout
            } catch (e: Exception) {
                Log.e(tag, "Instance sync operation failed", e)
                val errorMessage = when (e) {
                    is kotlinx.coroutines.TimeoutCancellationException -> "Sync timed out"
                    else -> classifyError(e).let { error ->
                        when (error) {
                            is SyncError.NetworkError -> error.message
                            is SyncError.TimeoutError -> error.message
                            is SyncError.AuthenticationError -> error.message
                            is SyncError.ServerError -> error.message
                            is SyncError.ValidationError -> error.message
                            is SyncError.UnknownError -> error.message
                        }
                    }
                }
                _syncState.value = _syncState.value.copy(
                    isRunning = false,
                    error = errorMessage,
                    failedAttempts = _syncState.value.failedAttempts + 1
                )
                clearDetailedProgress() // CRITICAL: Clear overlay on any error
            }
        }

        return try {
            syncJob?.join()
            if (_syncState.value.error == null) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(_syncState.value.error))
            }
        } catch (e: Exception) {
            // Ensure state is reset even if join() fails
            _syncState.value = _syncState.value.copy(
                isRunning = false,
                error = e.message ?: "Instance sync failed"
            )
            Result.failure(e)
        }
    }
    
    private suspend fun performSync() {
        _syncState.value = _syncState.value.copy(
            isRunning = true,
            lastSyncAttempt = System.currentTimeMillis(),
            error = null
        )

        // Acquire wake lock to prevent interruption
        acquireWakeLock()

        try {
            // Phase 1: Initialize sync (0-10%)
            updateProgress(SyncPhase.INITIALIZING, 0, "Acquiring sync lock...")

            // Phase 2: Validate connection (5-10%)
            if (!validateConnection()) {
                return // Error state already set in validateConnection()
            }

            val d2 = sessionManager.getD2() ?: run {
                Log.e(tag, "D2 session became null after validation")
                setErrorState(SyncError.AuthenticationError("DHIS2 session lost. Please log in again."))
                return
            }

            // Get all draft data values to sync
            val drafts = databaseProvider.getCurrentDatabase().dataValueDraftDao().getAllDrafts()
            Log.d(tag, "Starting sync of ${drafts.size} draft values")

            updateProgress(
                phase = SyncPhase.INITIALIZING,
                percentage = 15,
                detail = "Found ${drafts.size} data values to sync",
                totalItems = drafts.size,
                itemDescription = "data values"
            )

            if (drafts.isEmpty()) {
                updateProgress(SyncPhase.FINALIZING, 100, "No data to sync")
                _syncState.value = _syncState.value.copy(
                    isRunning = false,
                    queueSize = 0,
                    lastSuccessfulSync = System.currentTimeMillis(),
                    failedAttempts = 0
                )
                clearDetailedProgress()
                return
            }

            // Phase 3: Prepare data for upload (15-30%)
            updateProgress(SyncPhase.INITIALIZING, 20, "Preparing ${drafts.size} values for upload...")
            val allSuccessfulDrafts = mutableListOf<DataValueDraftEntity>()

            for ((index, draft) in drafts.withIndex()) {
                try {
                    // Set the data value in DHIS2 local database
                    d2.dataValueModule().dataValues()
                        .value(
                            period = draft.period,
                            organisationUnit = draft.orgUnit,
                            dataElement = draft.dataElement,
                            categoryOptionCombo = draft.categoryOptionCombo,
                            attributeOptionCombo = draft.attributeOptionCombo
                        )
                        .blockingSet(draft.value)

                    // DHIS2 Security: Validate data state before marking for upload
                    val dataValue = d2.dataValueModule().dataValues()
                        .value(
                            period = draft.period,
                            organisationUnit = draft.orgUnit,
                            dataElement = draft.dataElement,
                            categoryOptionCombo = draft.categoryOptionCombo,
                            attributeOptionCombo = draft.attributeOptionCombo
                        )
                        .blockingGet()

                    // Only add to upload queue if state allows upload (not ERROR or WARNING)
                    val syncState = dataValue?.syncState()
                    if (syncState != null && (syncState.name == "ERROR" || syncState.name == "WARNING")) {
                        Log.w(tag, "Skipping data value in ${syncState.name} state: ${draft.dataElement}")
                        Log.d(tag, "Data value details - Period: ${draft.period}, OrgUnit: ${draft.orgUnit}, Value: ${draft.value}")
                        // Don't add to successful drafts - requires resolution first
                    } else {
                        allSuccessfulDrafts.add(draft)
                        Log.d(tag, "Added data value for upload: ${draft.dataElement} (State: ${syncState?.name ?: "UNKNOWN"})")
                    }

                    // Update progress for data preparation
                    val prepProgress = 20 + ((index + 1) * 10 / drafts.size)
                    updateProgress(
                        phase = SyncPhase.INITIALIZING,
                        percentage = prepProgress,
                        detail = "Preparing data values for upload",
                        currentItem = index + 1,
                        totalItems = drafts.size,
                        itemDescription = "data values"
                    )

                } catch (e: Exception) {
                    Log.w(tag, "Failed to set draft value for ${draft.dataElement}: ${e.message}")
                }
            }

            if (allSuccessfulDrafts.isEmpty()) {
                setErrorState(SyncError.ValidationError("No valid data values to upload"))
                return
            }

            Log.d(tag, "Successfully set ${allSuccessfulDrafts.size} values, starting upload")

            // Phase 4: Upload data with chunked strategy (30-80%)
            // Create chunks for reliable upload
            val chunks = chunkDataValues(allSuccessfulDrafts)
            Log.d(tag, "Uploading ${allSuccessfulDrafts.size} values in ${chunks.size} chunks")

            updateProgress(
                phase = SyncPhase.UPLOADING_DATA,
                percentage = 35,
                detail = "Uploading data to server",
                currentItem = 0,
                totalItems = chunks.size,
                itemDescription = "chunks"
            )

            // Upload data using chunked strategy with retry logic
            val uploadResult = uploadDataValuesInChunks(chunks) { phase, percentage, detail, currentChunk, totalChunks ->
                updateProgress(
                    phase = phase,
                    percentage = percentage,
                    detail = detail,
                    currentItem = currentChunk,
                    totalItems = totalChunks,
                    itemDescription = "chunks"
                )
            }

            uploadResult.fold(
                onSuccess = {
                    updateProgress(SyncPhase.UPLOADING_DATA, 80, "Upload completed successfully")
                    Log.d(tag, "Chunked upload completed successfully for ${allSuccessfulDrafts.size} values")
                },
                onFailure = { error ->
                    Log.e(tag, "Chunked upload failed: ${error.message}")
                    setErrorState(classifyError(error as Exception))
                    return
                }
            )

            // Phase 5: Download updates (80-95%)
            try {
                updateProgress(SyncPhase.DOWNLOADING_DATASETS, 85, "Downloading latest data...")

                withTimeout(downloadTimeoutMs) {
                    d2.dataValueModule().dataValues().get()
                }

                updateProgress(SyncPhase.DOWNLOADING_DATASETS, 95, "Download completed")
            } catch (e: Exception) {
                Log.w(tag, "Failed to download latest data after upload: ${e.message}")
                // Don't fail the entire sync just because download failed
                updateProgress(SyncPhase.DOWNLOADING_DATASETS, 95, "Download failed but upload succeeded")
            }

            // Phase 6: Finalize (95-100%)
            updateProgress(SyncPhase.FINALIZING, 98, "Finalizing sync...")

            _syncState.value = _syncState.value.copy(
                isRunning = false,
                queueSize = databaseProvider.getCurrentDatabase().dataValueDraftDao().getAllDrafts().size,
                lastSuccessfulSync = System.currentTimeMillis(),
                failedAttempts = 0,
                error = null
            )

            updateProgress(SyncPhase.FINALIZING, 100, "Sync completed successfully")

            Log.d(tag, "Sync completed - ${allSuccessfulDrafts.size} values uploaded successfully")

            // Clear progress after short delay for user to see completion
            delay(1500)
            clearDetailedProgress()

        } catch (e: Exception) {
            val error = classifyError(e)
            Log.e(tag, "Sync failed: $error", e)

            _syncState.value = _syncState.value.copy(
                isRunning = false,
                failedAttempts = _syncState.value.failedAttempts + 1,
                error = when (error) {
                    is SyncError.NetworkError -> error.message
                    is SyncError.TimeoutError -> error.message
                    is SyncError.AuthenticationError -> error.message
                    is SyncError.ServerError -> error.message
                    is SyncError.ValidationError -> error.message
                    is SyncError.UnknownError -> error.message
                }
            )

            // Don't clear progress on error - let user see error state and navigate back
            if (_detailedProgress.value?.error == null) {
                setErrorState(error)
            }
        } finally {
            // Always release wake lock
            releaseWakeLock()
        }
    }

    private suspend fun performDownloadOnlySync() {
        _syncState.value = _syncState.value.copy(
            isRunning = true,
            lastSyncAttempt = System.currentTimeMillis(),
            error = null
        )

        try {
            // Phase 1: Initialize download sync (0-20%)
            updateProgress(SyncPhase.INITIALIZING, 0, "Starting download sync...")

            // Phase 2: Validate connection (10-20%)
            if (!validateConnection()) {
                return // Error state already set in validateConnection()
            }

            val d2 = sessionManager.getD2() ?: run {
                Log.e(tag, "D2 session became null after validation")
                setErrorState(SyncError.AuthenticationError("DHIS2 session lost. Please log in again."))
                return
            }

            // Count available items to show granular progress
            val datasetCount = try {
                d2.dataSetModule().dataSets().blockingCount()
            } catch (e: Exception) {
                Log.w(tag, "Could not count datasets: ${e.message}")
                0
            }

            val trackerProgramCount = try {
                d2.programModule().programs().byProgramType().eq(org.hisp.dhis.android.core.program.ProgramType.WITH_REGISTRATION).blockingCount()
            } catch (e: Exception) {
                Log.w(tag, "Could not count tracker programs: ${e.message}")
                0
            }

            val eventProgramCount = try {
                d2.programModule().programs().byProgramType().eq(org.hisp.dhis.android.core.program.ProgramType.WITHOUT_REGISTRATION).blockingCount()
            } catch (e: Exception) {
                Log.w(tag, "Could not count event programs: ${e.message}")
                0
            }

            val totalItems = datasetCount + trackerProgramCount + eventProgramCount

            updateProgress(
                SyncPhase.DOWNLOADING_DATASETS,
                30,
                "Downloading data for programs...",
                currentItem = 0,
                totalItems = totalItems,
                itemDescription = "programs"
            )

            try {
                // Download aggregate data
                if (datasetCount > 0) {
                    updateProgress(
                        SyncPhase.DOWNLOADING_DATASETS,
                        40,
                        "Downloading aggregate data...",
                        currentItem = datasetCount,
                        totalItems = totalItems,
                        itemDescription = "datasets"
                    )
                    withTimeout(downloadTimeoutMs) {
                        d2.aggregatedModule().data().blockingDownload()
                    }
                }

                // Download tracker data
                if (trackerProgramCount > 0) {
                    updateProgress(
                        SyncPhase.DOWNLOADING_TRACKER_DATA,
                        60,
                        "Downloading tracker enrollments...",
                        currentItem = datasetCount + trackerProgramCount,
                        totalItems = totalItems,
                        itemDescription = "enrollments"
                    )
                    withTimeout(downloadTimeoutMs) {
                        d2.trackedEntityModule().trackedEntityInstanceDownloader()
                            .limit(200)
                            .limitByProgram(true)
                            .blockingDownload()
                    }
                }

                // Download event data
                if (eventProgramCount > 0) {
                    updateProgress(
                        SyncPhase.DOWNLOADING_EVENTS,
                        80,
                        "Downloading event data...",
                        currentItem = totalItems,
                        totalItems = totalItems,
                        itemDescription = "events"
                    )
                    withTimeout(downloadTimeoutMs) {
                        d2.eventModule().eventDownloader()
                            .limit(200)
                            .limitByProgram(true)
                            .blockingDownload()
                    }
                }

                updateProgress(SyncPhase.DOWNLOADING_DATASETS, 95, "Download completed")
            } catch (e: Exception) {
                Log.w(tag, "Failed to download latest data: ${e.message}")
                // Continue anyway - this is just a refresh operation
            }

            // Phase 3: Finalize (80-100%)
            updateProgress(SyncPhase.FINALIZING, 100, "Download sync completed")

            _syncState.value = _syncState.value.copy(
                isRunning = false,
                lastSuccessfulSync = System.currentTimeMillis(),
                error = null
            )

            // Clear progress after successful completion
            _detailedProgress.value = null

            Log.d(tag, "Download-only sync completed successfully")
        } catch (e: Exception) {
            Log.e(tag, "Download-only sync failed", e)
            val error = classifyError(e)
            setErrorState(error)
        }
    }

    private suspend fun performSyncForInstance(
        datasetId: String,
        period: String,
        orgUnit: String,
        attributeOptionCombo: String
    ) {
        _syncState.value = _syncState.value.copy(
            isRunning = true,
            lastSyncAttempt = System.currentTimeMillis(),
            error = null
        )

        try {
            val d2 = sessionManager.getD2()
            if (d2 == null) {
                throw Exception("DHIS2 session not available")
            }


            if (!networkStateManager.isOnline()) {
                throw Exception("No internet connection available")
            }

            // Get drafts for specific dataset instance only
            val drafts = databaseProvider.getCurrentDatabase().dataValueDraftDao().getDraftsForInstance(
                datasetId, period, orgUnit, attributeOptionCombo
            )
            Log.d(tag, "Starting sync for dataset instance: $datasetId, period: $period, orgUnit: $orgUnit")
            Log.d(tag, "Found ${drafts.size} draft values for this instance")

            if (drafts.isEmpty()) {
                _syncState.value = _syncState.value.copy(
                    isRunning = false,
                    queueSize = databaseProvider.getCurrentDatabase().dataValueDraftDao().getAllDrafts().size,
                    lastSuccessfulSync = System.currentTimeMillis(),
                    failedAttempts = 0
                )
                return
            }

            // Step 1: Set ALL drafts for this instance in DHIS2 local database
            Log.d(tag, "Setting ${drafts.size} draft values for instance in DHIS2 local database")
            val allSuccessfulDrafts = mutableListOf<DataValueDraftEntity>()

            for (draft in drafts) {
                try {
                    // Set the data value in DHIS2 local database
                    d2.dataValueModule().dataValues()
                        .value(
                            period = draft.period,
                            organisationUnit = draft.orgUnit,
                            dataElement = draft.dataElement,
                            categoryOptionCombo = draft.categoryOptionCombo,
                            attributeOptionCombo = draft.attributeOptionCombo
                        )
                        .blockingSet(draft.value)

                    allSuccessfulDrafts.add(draft)
                } catch (e: Exception) {
                    Log.w(tag, "Failed to set draft value for ${draft.dataElement}: ${e.message}")
                }
            }

            if (allSuccessfulDrafts.isEmpty()) {
                Log.w(tag, "No valid drafts to upload for instance, skipping upload")
                return
            }

            Log.d(tag, "Successfully set ${allSuccessfulDrafts.size} values for instance, starting upload")

            // Step 2: Upload ALL set values ONCE with retry logic
            var lastException: Exception? = null
            var attempt = 0
            var uploadSuccessful = false

            while (attempt < maxRetryAttempts && !uploadSuccessful) {
                try {
                    Log.d(tag, "Upload attempt ${attempt + 1} of $maxRetryAttempts for ${allSuccessfulDrafts.size} values from instance")

                    // Use withTimeout to handle long-running upload operations
                    val uploadResult = withTimeout(uploadTimeoutMs) {
                        d2.dataValueModule().dataValues().blockingUpload()
                    }
                    Log.d(tag, "Upload result for instance: $uploadResult")

                    // If upload successful, mark as uploaded
                    uploadSuccessful = true

                    // Remove successfully synced drafts for this instance
                    for (draft in allSuccessfulDrafts) {
                        databaseProvider.getCurrentDatabase().dataValueDraftDao().deleteDraft(draft)
                    }

                    Log.d(tag, "Upload completed successfully for ${allSuccessfulDrafts.size} values from instance")

                } catch (e: Exception) {
                    lastException = e
                    attempt++

                    // Extract meaningful error message
                    val errorMessage = extractErrorMessage(e)
                    Log.w(tag, "Upload attempt $attempt failed for instance: $errorMessage", e)

                    // Check if we should retry based on error type
                    val shouldRetry = shouldRetryForError(e) && attempt < maxRetryAttempts

                    if (shouldRetry) {
                        // Calculate progressive backoff delay - longer for network issues
                        val baseDelay = if (isNetworkTimeoutError(e)) baseRetryDelayMs * 2 else baseRetryDelayMs
                        val delay = minOf(
                            baseDelay * (1L shl (attempt - 1)),
                            maxRetryDelayMs
                        )

                        Log.w(tag, "Retrying upload for instance in ${delay}ms (attempt ${attempt + 1} of $maxRetryAttempts)")
                        delay(delay)

                        // Check if network is still available before retry
                        if (!networkStateManager.isOnline()) {
                            throw Exception("Network connection lost during retry")
                        }
                    } else {
                        // Don't retry for certain error types
                        break
                    }
                }
            }

            // If upload failed after all retries, throw the last exception
            if (!uploadSuccessful && lastException != null) {
                val errorMessage = extractErrorMessage(lastException)
                Log.e(tag, "Failed to upload instance after $maxRetryAttempts attempts: $errorMessage")
                throw lastException
            }

            // Download latest data to sync (with timeout) - only if some uploads succeeded
            if (allSuccessfulDrafts.isNotEmpty()) {
                try {
                    withTimeout(downloadTimeoutMs) {
                        d2.dataValueModule().dataValues().get()
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to download latest data after upload: ${e.message}")
                    // Don't fail the entire sync just because download failed
                }
            }

            _syncState.value = _syncState.value.copy(
                isRunning = false,
                queueSize = databaseProvider.getCurrentDatabase().dataValueDraftDao().getAllDrafts().size,
                lastSuccessfulSync = if (allSuccessfulDrafts.isNotEmpty()) System.currentTimeMillis() else _syncState.value.lastSuccessfulSync,
                failedAttempts = if (allSuccessfulDrafts.isNotEmpty()) 0 else _syncState.value.failedAttempts + 1,
                error = if (allSuccessfulDrafts.isEmpty()) "Upload failed for dataset instance" else null
            )

            Log.d(tag, "Sync completed for instance - ${allSuccessfulDrafts.size} values uploaded successfully")

            // CRITICAL: Always clear detailed progress on successful completion
            clearDetailedProgress()

            if (allSuccessfulDrafts.isEmpty()) {
                throw Exception("Failed to upload any data values for dataset instance")
            }

        } catch (e: Exception) {
            val userFriendlyError = extractErrorMessage(e)
            Log.e(tag, "Sync failed for instance: $userFriendlyError", e)
            _syncState.value = _syncState.value.copy(
                isRunning = false,
                failedAttempts = _syncState.value.failedAttempts + 1,
                error = userFriendlyError
            )
            // CRITICAL: Always clear detailed progress on instance sync failure
            clearDetailedProgress()
        }
    }

    suspend fun cancelSync() {
        syncJob?.cancel()
        autoRetryJob?.cancel()
        _syncState.value = _syncState.value.copy(
            isRunning = false,
            error = "Sync cancelled by user"
        )
        clearDetailedProgress()
    }
    
    suspend fun clearQueue() {
        databaseProvider.getCurrentDatabase().dataValueDraftDao().getAllDrafts().forEach { draft ->
            databaseProvider.getCurrentDatabase().dataValueDraftDao().deleteDraft(draft)
        }
        _syncState.value = _syncState.value.copy(queueSize = 0)
    }

    fun cleanup() {
        syncJob?.cancel()
        networkMonitorJob?.cancel()
        autoRetryJob?.cancel()
    }

    /**
     * Clear detailed progress state
     */
    fun clearDetailedProgress() {
        _detailedProgress.value = null
    }

    /**
     * Clear error state and reset sync state - helps dismiss persistent failed sync dialogues
     */
    fun clearErrorState() {
        _detailedProgress.value = null
        _syncState.value = _syncState.value.copy(
            error = null,
            isRunning = false
        )
        Log.d(tag, "Error state cleared - sync dialogues should be dismissible")
    }

    /**
     * Update detailed progress with phase information
     */
    private suspend fun updateProgress(
        phase: SyncPhase,
        percentage: Int,
        detail: String? = null,
        canNavigateBack: Boolean = false,
        currentItem: Int = 0,
        totalItems: Int = 0,
        itemDescription: String? = null,
        estimatedTimeRemaining: Long? = null
    ) {
        _detailedProgress.value = DetailedSyncProgress(
            phase = phase,
            overallPercentage = percentage,
            phaseTitle = phase.title,
            phaseDetail = detail ?: phase.defaultDetail,
            canNavigateBack = canNavigateBack,
            currentItem = currentItem,
            totalItems = totalItems,
            itemDescription = itemDescription,
            estimatedTimeRemaining = estimatedTimeRemaining
        )
        delay(100) // Small delay for UI feedback
    }

    /**
     * Set error state with navigation capability and reset running state
     */
    private fun setErrorState(error: SyncError) {
        _detailedProgress.value = DetailedSyncProgress.error(error)
        // Critical fix: Reset isRunning to false when setting error state
        _syncState.value = _syncState.value.copy(
            isRunning = false,
            error = when (error) {
                is SyncError.NetworkError -> error.message
                is SyncError.TimeoutError -> error.message
                is SyncError.AuthenticationError -> error.message
                is SyncError.ValidationError -> error.message
                is SyncError.ServerError -> error.message
                is SyncError.UnknownError -> error.message
            },
            failedAttempts = _syncState.value.failedAttempts + 1
        )
    }

    /**
     * Enhanced connection validation using DHIS2 patterns
     */
    private suspend fun validateConnection(): Boolean {
        return try {
            updateProgress(SyncPhase.VALIDATING_CONNECTION, 5, "Checking DHIS2 session...")

            val d2 = sessionManager.getD2()
            if (d2 == null) {
                Log.e(tag, "DHIS2 session is null - authentication required")
                setErrorState(SyncError.AuthenticationError("DHIS2 session not available. Please log in again."))
                return false
            }

            // Validate session is still active (DHIS2 security best practice)
            try {
                val isAuthenticated = d2.userModule().isLogged().blockingGet()
                if (!isAuthenticated) {
                    Log.e(tag, "DHIS2 session expired - re-authentication required")
                    setErrorState(SyncError.AuthenticationError("Session expired. Please log in again."))
                    return false
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to validate session status", e)
                setErrorState(SyncError.AuthenticationError("Unable to validate session. Please log in again."))
                return false
            }

            updateProgress(SyncPhase.VALIDATING_CONNECTION, 7, "Checking network connectivity...")

            // More permissive network check - allow any connection type including 2G
            val networkState = networkStateManager.networkState.value
            if (!networkState.isConnected || !networkState.hasInternet) {
                Log.w(tag, "Network not available for sync")
                setErrorState(SyncError.NetworkError("No internet connection available"))
                return false
            }

            // Log network type but don't block - allow 2G connections
            Log.d(tag, "Network type: ${networkState.networkType}, metered: ${networkState.isMetered}")

            updateProgress(SyncPhase.VALIDATING_CONNECTION, 8, "Testing server connection...")

            // Test connection using DHIS2 pattern - lightweight system info call
            withTimeout(connectionValidationTimeoutMs) {
                try {
                    val systemInfo = d2.systemInfoModule().systemInfo().blockingGet()
                    Log.d(tag, "Connection validated - server version: ${systemInfo?.version()}")
                } catch (e: Exception) {
                    Log.w(tag, "System info call failed, trying alternative validation")
                    // Fallback: try to access user info
                    d2.userModule().user().blockingGet()
                }
            }

            updateProgress(SyncPhase.VALIDATING_CONNECTION, 10, "Connection validated successfully")
            Log.d(tag, "Connection validation successful")
            true

        } catch (e: Exception) {
            Log.e(tag, "Connection validation failed", e)

            val error = when {
                e is TimeoutCancellationException -> {
                    Log.w(tag, "Connection validation timed out")
                    SyncError.TimeoutError("Server connection timed out - check your internet connection")
                }
                e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true -> {
                    Log.e(tag, "Authentication failed during validation")
                    SyncError.AuthenticationError("Authentication failed. Please log in again.")
                }
                e.message?.contains("SocketTimeoutException") == true -> {
                    Log.w(tag, "Socket timeout during validation")
                    SyncError.NetworkError("Connection timed out - check your internet connection")
                }
                e.message?.contains("Software caused connection abort") == true -> {
                    Log.w(tag, "Connection abort during validation")
                    SyncError.NetworkError("Network connection was interrupted")
                }
                e.message?.contains("ConnectException") == true -> {
                    Log.w(tag, "Cannot connect to server")
                    SyncError.NetworkError("Cannot connect to DHIS2 server")
                }
                e.message?.contains("UnknownHostException") == true -> {
                    Log.w(tag, "Cannot resolve server hostname")
                    SyncError.NetworkError("Cannot reach DHIS2 server - check internet connection")
                }
                else -> {
                    Log.e(tag, "Unknown validation error: ${e.message}")
                    SyncError.NetworkError("Connection validation failed: ${e.message}")
                }
            }

            setErrorState(error)
            false
        }
    }

    /**
     * Enhanced error classification using DHIS2 patterns for better retry logic
     */
    private fun classifyError(exception: Exception): SyncError {
        Log.d(tag, "Classifying error: ${exception.javaClass.simpleName} - ${exception.message}")

        return when {
            // Handle D2Error specifically (DHIS2 SDK errors) - proper detection including wrapped errors
            exception.message?.contains("D2Error") == true ||
            exception.cause?.javaClass?.name?.contains("D2Error") == true ||
            exception.javaClass.name.contains("D2Error") -> {
                Log.w(tag, "D2Error detected - DHIS2 SDK specific error")

                // Extract the actual error message from nested D2Error
                val actualMessage = exception.message ?: exception.cause?.message ?: "Unknown D2Error"

                // Check if it's a network/timeout-related D2Error that can be retried
                val canRetry = actualMessage.let { msg ->
                    msg.contains("Network", ignoreCase = true) ||
                    msg.contains("Connection", ignoreCase = true) ||
                    msg.contains("Timeout", ignoreCase = true) ||
                    msg.contains("Socket", ignoreCase = true) ||
                    msg.contains("SocketTimeoutException", ignoreCase = true)
                }

                if (canRetry) {
                    SyncError.TimeoutError("DHIS2 upload timeout - ${actualMessage}", canAutoRetry = true)
                } else {
                    SyncError.ServerError("DHIS2 server error - ${actualMessage}")
                }
            }

            // Timeout errors - can auto-retry (common during screen lock)
            exception is kotlinx.coroutines.TimeoutCancellationException -> {
                Log.w(tag, "Coroutine timeout - likely caused by screen lock or slow network")
                SyncError.TimeoutError("Sync timed out - may be caused by screen lock or slow network", canAutoRetry = true)
            }
            exception.message?.contains("SocketTimeoutException") == true -> {
                Log.w(tag, "Socket timeout detected")
                SyncError.TimeoutError("Network timeout - check your internet connection", canAutoRetry = true)
            }
            exception.message?.contains("timeout") == true -> {
                Log.w(tag, "General timeout detected")
                SyncError.TimeoutError("Operation timed out", canAutoRetry = true)
            }

            // Socket errors - specific handling for connection abort (main issue from logs)
            exception.message?.contains("Software caused connection abort") == true ||
            exception.message?.contains("SocketException") == true -> {
                Log.w(tag, "Socket connection abort - likely screen lock during sync")
                SyncError.NetworkError("Network connection was interrupted (often caused by screen lock)", canAutoRetry = true)
            }

            // Network connection errors - can auto-retry
            exception.message?.contains("ConnectException") == true -> {
                Log.w(tag, "Connection exception - cannot connect to server")
                SyncError.NetworkError("Cannot connect to server", canAutoRetry = true)
            }
            exception.message?.contains("UnknownHostException") == true -> {
                Log.w(tag, "Unknown host - DNS or network issue")
                SyncError.NetworkError("Cannot reach server - check internet connection", canAutoRetry = true)
            }

            // Authentication errors - require user action
            exception.message?.contains("401") == true || exception.message?.contains("Unauthorized") == true -> {
                Log.e(tag, "Authentication failed")
                SyncError.AuthenticationError("Authentication failed. Please log in again.")
            }

            // Server errors - some can retry
            exception.message?.contains("500") == true -> {
                Log.w(tag, "Server error 500")
                SyncError.ServerError("Server error - please try again", statusCode = 500)
            }
            exception.message?.contains("503") == true -> {
                Log.w(tag, "Server unavailable 503")
                SyncError.ServerError("Server temporarily unavailable", statusCode = 503)
            }
            exception.message?.contains("400") == true -> {
                Log.e(tag, "Bad request 400")
                SyncError.ValidationError("Invalid data submitted")
            }
            exception.message?.contains("404") == true -> {
                Log.e(tag, "Not found 404")
                SyncError.ValidationError("Resource not found")
            }

            // Default handling - be more conservative about retrying
            else -> {
                Log.e(tag, "Unknown error type: ${exception.javaClass.simpleName}")
                SyncError.UnknownError(exception.message ?: "Sync failed. Please try again.")
            }
        }
    }

    /**
     * Calculate exponential backoff delay for retries
     */
    private fun calculateRetryDelay(attempt: Int): Long {
        return minOf(
            baseRetryDelayMs * (1L shl (attempt - 1)), // Exponential: 2s, 4s, 8s
            maxRetryDelayMs
        )
    }

    /**
     * Show retry countdown to user with progress updates
     */
    private suspend fun showRetryCountdown(delayMs: Long, attempt: Int) {
        val seconds = (delayMs / 1000).toInt()
        for (remaining in seconds downTo 1) {
            _detailedProgress.value = _detailedProgress.value?.copy(
                isAutoRetrying = true,
                autoRetryCountdown = remaining,
                phaseDetail = "Network error - retrying in $remaining seconds... (attempt ${attempt + 1} of $maxRetryAttempts)"
            )
            delay(1000)
        }
    }

    /**
     * Auto-retry logic for network errors
     */
    private suspend fun handleAutoRetry(error: SyncError, attempt: Int): Boolean {
        val canAutoRetry = when (error) {
            is SyncError.NetworkError -> error.canAutoRetry && attempt < maxRetryAttempts
            is SyncError.TimeoutError -> error.canAutoRetry && attempt < maxRetryAttempts
            else -> false
        }

        if (!canAutoRetry) return false

        val delay = minOf(
            baseRetryDelayMs * (1L shl (attempt - 1)),
            maxRetryDelayMs
        )

        // Show auto-retry countdown
        repeat((delay / 1000).toInt()) { second ->
            val remaining = (delay / 1000).toInt() - second
            _detailedProgress.value = _detailedProgress.value?.copy(
                isAutoRetrying = true,
                autoRetryCountdown = remaining,
                phaseDetail = "Auto-retrying in $remaining seconds... (attempt ${attempt + 1} of $maxRetryAttempts)"
            )
            delay(1000)
        }

        // Check network before retry
        if (!networkStateManager.isOnline()) {
            setErrorState(SyncError.NetworkError("Network connection lost during retry"))
            return false
        }

        return true
    }

    /**
     * Determine optimal chunk size based on failure history and network conditions
     */
    private fun getAdaptiveChunkSize(): Int {
        val currentFailures = _syncState.value.failedAttempts
        return when {
            // If we've had multiple failures, use smaller chunks
            currentFailures >= 3 -> minChunkSize
            currentFailures >= 1 -> baseChunkSize / 2
            // If sync has been successful recently, use larger chunks
            else -> baseChunkSize
        }
    }

    /**
     * Extract a user-friendly error message from various exception types
     */
    private fun extractErrorMessage(exception: Exception): String {
        return when {
            // Handle D2Error from DHIS2 SDK
            exception.message?.contains("D2Error") == true -> {
                "Server connection issue. Please check your internet connection and try again."
            }
            // Handle timeout exceptions
            exception is kotlinx.coroutines.TimeoutCancellationException -> {
                "Upload timed out. Please check your internet connection and try again."
            }
            exception.message?.contains("SocketTimeoutException") == true -> {
                "Connection timed out. Please check your internet connection and try again."
            }
            exception.message?.contains("UnknownHostException") == true -> {
                "Cannot reach server. Please check your internet connection."
            }
            exception.message?.contains("ConnectException") == true -> {
                "Cannot connect to server. Please check your internet connection."
            }
            // Handle authentication issues
            exception.message?.contains("401") == true || exception.message?.contains("Unauthorized") == true -> {
                "Authentication failed. Please log in again."
            }
            // Handle server errors
            exception.message?.contains("500") == true -> {
                "Server error. Please try again later."
            }
            exception.message?.contains("503") == true -> {
                "Server temporarily unavailable. Please try again later."
            }
            // Default message
            else -> {
                exception.message ?: "Sync failed. Please try again."
            }
        }
    }

    /**
     * Determine if an error should trigger a retry attempt
     */
    private fun shouldRetryForError(exception: Exception): Boolean {
        return when {
            // Retry for network/timeout issues
            exception is kotlinx.coroutines.TimeoutCancellationException -> true
            exception.message?.contains("SocketTimeoutException") == true -> true
            exception.message?.contains("ConnectException") == true -> true
            exception.message?.contains("D2Error") == true -> true
            exception.message?.contains("500") == true -> true
            exception.message?.contains("503") == true -> true
            // Don't retry for authentication issues
            exception.message?.contains("401") == true -> false
            exception.message?.contains("Unauthorized") == true -> false
            // Don't retry for client errors
            exception.message?.contains("400") == true -> false
            exception.message?.contains("404") == true -> false
            // Default to retry for unknown errors
            else -> true
        }
    }

    /**
     * Check if error is specifically network timeout related for longer backoff
     */
    private fun isNetworkTimeoutError(exception: Exception): Boolean {
        return when {
            exception is kotlinx.coroutines.TimeoutCancellationException -> true
            exception.message?.contains("SocketTimeoutException") == true -> true
            exception.message?.contains("ConnectTimeoutException") == true -> true
            exception.message?.contains("timeout") == true -> true
            else -> false
        }
    }

    /**
     * Acquire wake lock to prevent sync interruption during screen lock
     * Includes proper permission validation for security compliance
     */
    private fun acquireWakeLock() {
        try {
            // Check for WAKE_LOCK permission (security best practice)
            if (!hasWakeLockPermission()) {
                Log.w(tag, "WAKE_LOCK permission not granted - sync may be interrupted during screen lock")
                return
            }

            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SimpleDataEntry:SyncQueueManager"
                ).apply {
                    acquire(wakeLockTimeoutMs)
                }
                Log.d(tag, "Wake lock acquired for sync operation")
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to acquire wake lock: ${e.message}")
        }
    }

    /**
     * Check if the app has WAKE_LOCK permission
     */
    private fun hasWakeLockPermission(): Boolean {
        return try {
            context.checkSelfPermission(android.Manifest.permission.WAKE_LOCK) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(tag, "Failed to check WAKE_LOCK permission", e)
            false
        }
    }

    /**
     * Release wake lock after sync completion
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(tag, "Wake lock released")
                }
                wakeLock = null
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to release wake lock: ${e.message}")
        }
    }

    /**
     * Split data values into chunks for reliable upload
     */
    private fun chunkDataValues(drafts: List<DataValueDraftEntity>): List<List<DataValueDraftEntity>> {
        if (drafts.size <= maxChunkSize) {
            return listOf(drafts)
        }

        val chunks = mutableListOf<List<DataValueDraftEntity>>()
        var startIndex = 0

        while (startIndex < drafts.size) {
            val endIndex = minOf(startIndex + maxChunkSize, drafts.size)
            chunks.add(drafts.subList(startIndex, endIndex))
            startIndex = endIndex
        }

        Log.d(tag, "Split ${drafts.size} data values into ${chunks.size} chunks")
        return chunks
    }

    /**
     * Upload data values in chunks with retry logic
     */
    private suspend fun uploadDataValuesInChunks(
        chunks: List<List<DataValueDraftEntity>>,
        updateProgress: suspend (SyncPhase, Int, String, Int, Int) -> Unit
    ): Result<Unit> {
        var successfulChunks = 0
        val totalChunks = chunks.size

        for ((chunkIndex, chunk) in chunks.withIndex()) {
            var chunkAttempt = 0
            var chunkUploaded = false

            while (chunkAttempt < maxRetryAttempts && !chunkUploaded) {
                try {
                    val progressPercentage = 40 + (30 * (successfulChunks + 0.5f) / totalChunks).toInt()
                    val progressDetail = if (chunkAttempt > 0) {
                        "Uploading (attempt ${chunkAttempt + 1})"
                    } else {
                        "Uploading data to server"
                    }

                    updateProgress(SyncPhase.UPLOADING_DATA, progressPercentage, progressDetail, chunkIndex + 1, totalChunks)

                    // Set chunk data values in DHIS2
                    for (draft in chunk) {
                        val d2 = sessionManager.getD2() ?: throw Exception("DHIS2 session not available")
                        d2.dataValueModule().dataValues()
                            .value(
                                period = draft.period,
                                organisationUnit = draft.orgUnit,
                                dataElement = draft.dataElement,
                                categoryOptionCombo = draft.categoryOptionCombo,
                                attributeOptionCombo = draft.attributeOptionCombo
                            )
                            .blockingSet(draft.value)
                    }

                    // Upload chunk with timeout
                    withTimeout(chunkUploadTimeoutMs) {
                        val d2 = sessionManager.getD2() ?: throw Exception("DHIS2 session not available")
                        d2.dataValueModule().dataValues().blockingUpload()
                    }

                    chunkUploaded = true
                    successfulChunks++

                    // Remove successfully uploaded drafts
                    for (draft in chunk) {
                        databaseProvider.getCurrentDatabase().dataValueDraftDao().deleteDraft(draft)
                    }

                    Log.d(tag, "Successfully uploaded chunk ${chunkIndex + 1}/${totalChunks} with ${chunk.size} values")

                } catch (e: Exception) {
                    chunkAttempt++
                    val error = classifyError(e)

                    Log.w(tag, "Chunk ${chunkIndex + 1} upload attempt $chunkAttempt failed: ${error.javaClass.simpleName} - ${e.message}")

                    if (chunkAttempt >= maxRetryAttempts) {
                        Log.e(tag, "Failed to upload chunk ${chunkIndex + 1} after $maxRetryAttempts attempts")
                        return Result.failure(Exception("Chunk upload failed: ${e.message}"))
                    }

                    // Progressive retry delay for chunks
                    val retryDelay = calculateRetryDelay(chunkAttempt)
                    Log.w(tag, "Retrying chunk ${chunkIndex + 1} in ${retryDelay}ms")

                    val retryProgressPercentage = 40 + (30 * successfulChunks / totalChunks).toInt()
                    val retryProgressDetail = "Chunk ${chunkIndex + 1} failed, retrying in ${retryDelay / 1000} seconds..."
                    updateProgress(SyncPhase.UPLOADING_DATA, retryProgressPercentage, retryProgressDetail)

                    delay(retryDelay)
                }
            }
        }

        return Result.success(Unit)
    }
}