package com.ash.simpledataentry.data.sync

import android.util.Log
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.data.local.AppDatabase
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
    val error: String? = null,
    val progress: Int = 0,
    val step: String? = null
)

@Singleton
class SyncQueueManager @Inject constructor(
    private val networkStateManager: NetworkStateManager,
    private val sessionManager: SessionManager,
    private val database: AppDatabase
) {
    
    private val tag = "SyncQueueManager"
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    
    private var syncJob: Job? = null
    private var networkMonitorJob: Job? = null
    
    // Timeout configuration - optimized for upload vs download operations
    private val uploadTimeoutMs = 180000L // 3 minutes for uploads (server processing intensive)
    private val downloadTimeoutMs = 60000L // 1 minute for downloads (typically faster)
    private val connectionTimeoutMs = 30000L // 30 seconds for connection establishment

    // Retry configuration with progressive backoff
    private val maxRetryAttempts = 3 // Reduced attempts but longer timeouts
    private val baseRetryDelayMs = 5000L // Start with 5 seconds
    private val maxRetryDelayMs = 120000L // Up to 2 minutes between retries

    // Chunk configuration for large data uploads - adaptive sizing based on network conditions
    private val baseChunkSize = 25 // Base chunk size for stable networks
    private val maxChunkSize = 50 // Maximum data values per upload chunk
    private val minChunkSize = 10 // Minimum chunk size for poor networks
    
    suspend fun queueForSync() {
        val currentQueueSize = database.dataValueDraftDao().getAllDrafts().size
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
            performSync()
        }

        return try {
            syncJob?.join()
            if (_syncState.value.error == null) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(_syncState.value.error))
            }
        } catch (e: Exception) {
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
            performSyncForInstance(datasetId, period, orgUnit, attributeOptionCombo)
        }

        return try {
            syncJob?.join()
            if (_syncState.value.error == null) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(_syncState.value.error))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private suspend fun performSync() {
        _syncState.value = _syncState.value.copy(
            isRunning = true,
            lastSyncAttempt = System.currentTimeMillis(),
            error = null,
            progress = 0,
            step = "Initializing sync..."
        )
        
        try {
            val d2 = sessionManager.getD2()
            if (d2 == null) {
                throw Exception("DHIS2 session not available")
            }


            if (!networkStateManager.isOnline()) {
                throw Exception("No internet connection available")
            }
            
            // Get all draft data values to sync
            val drafts = database.dataValueDraftDao().getAllDrafts()
            Log.d(tag, "Starting sync of ${drafts.size} draft values")
            _syncState.value = _syncState.value.copy(progress = 10, step = "Found ${drafts.size} local changes")

            if (drafts.isEmpty()) {
                _syncState.value = _syncState.value.copy(
                    isRunning = false,
                    queueSize = 0,
                    lastSuccessfulSync = System.currentTimeMillis(),
                    failedAttempts = 0,
                    progress = 100,
                    step = "No local changes to sync"
                )
                return
            }

            // Step 1: Set ALL drafts first in DHIS2 local database
            _syncState.value = _syncState.value.copy(progress = 20, step = "Preparing data for upload...")
            Log.d(tag, "Setting ${drafts.size} draft values in DHIS2 local database")
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
                Log.w(tag, "No valid drafts to upload, skipping upload")
                return
            }

            Log.d(tag, "Successfully set ${allSuccessfulDrafts.size} values, starting upload")

            // Step 2: Upload ALL set values ONCE with retry logic
            var lastException: Exception? = null
            var attempt = 0
            var uploadSuccessful = false

            _syncState.value = _syncState.value.copy(progress = 40, step = "Uploading data to server...")

            while (attempt < maxRetryAttempts && !uploadSuccessful) {
                try {
                    Log.d(tag, "Upload attempt ${attempt + 1} of $maxRetryAttempts for ${allSuccessfulDrafts.size} values")

                    // Use withTimeout to handle long-running upload operations
                    val uploadResult = withTimeout(uploadTimeoutMs) {
                        d2.dataValueModule().dataValues().blockingUpload()
                    }
                    Log.d(tag, "Upload result: $uploadResult")

                    // If upload successful, mark as uploaded
                    uploadSuccessful = true

                    // Remove successfully synced drafts
                    for (draft in allSuccessfulDrafts) {
                        database.dataValueDraftDao().deleteDraft(draft)
                    }

                    Log.d(tag, "Upload completed successfully for ${allSuccessfulDrafts.size} values")

                } catch (e: Exception) {
                    lastException = e
                    attempt++

                    // Extract meaningful error message
                    val errorMessage = extractErrorMessage(e)
                    Log.w(tag, "Upload attempt $attempt failed: $errorMessage", e)

                    // Check if we should retry based on error type
                    val shouldRetry = shouldRetryForError(e) && attempt < maxRetryAttempts

                    if (shouldRetry) {
                        // Calculate progressive backoff delay - longer for network issues
                        val baseDelay = if (isNetworkTimeoutError(e)) baseRetryDelayMs * 2 else baseRetryDelayMs
                        val delay = minOf(
                            baseDelay * (1L shl (attempt - 1)),
                            maxRetryDelayMs
                        )

                        Log.w(tag, "Retrying upload in ${delay}ms (attempt ${attempt + 1} of $maxRetryAttempts)")
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
                Log.e(tag, "Failed to upload after $maxRetryAttempts attempts: $errorMessage")
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
                queueSize = database.dataValueDraftDao().getAllDrafts().size,
                lastSuccessfulSync = if (allSuccessfulDrafts.isNotEmpty()) System.currentTimeMillis() else _syncState.value.lastSuccessfulSync,
                failedAttempts = if (allSuccessfulDrafts.isNotEmpty()) 0 else _syncState.value.failedAttempts + 1,
                error = if (allSuccessfulDrafts.isEmpty()) "Upload failed for all data values" else null,
                progress = 100,
                step = "Sync complete"
            )

            Log.d(tag, "Sync completed - ${allSuccessfulDrafts.size} values uploaded successfully")

            if (allSuccessfulDrafts.isEmpty()) {
                throw Exception("Failed to upload any data values")
            }

        } catch (e: Exception) {
            val userFriendlyError = extractErrorMessage(e)
            Log.e(tag, "Sync failed: $userFriendlyError", e)
            _syncState.value = _syncState.value.copy(
                isRunning = false,
                failedAttempts = _syncState.value.failedAttempts + 1,
                error = userFriendlyError
            )
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
            error = null,
            progress = 0,
            step = "Initializing sync..."
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
            val drafts = database.dataValueDraftDao().getDraftsForInstance(
                datasetId, period, orgUnit, attributeOptionCombo
            )
            Log.d(tag, "Starting sync for dataset instance: $datasetId, period: $period, orgUnit: $orgUnit")
            Log.d(tag, "Found ${drafts.size} draft values for this instance")
            _syncState.value = _syncState.value.copy(progress = 10, step = "Found ${drafts.size} local changes")

            if (drafts.isEmpty()) {
                _syncState.value = _syncState.value.copy(
                    isRunning = false,
                    queueSize = database.dataValueDraftDao().getAllDrafts().size,
                    lastSuccessfulSync = System.currentTimeMillis(),
                    failedAttempts = 0,
                    progress = 100,
                    step = "No local changes to sync"
                )
                return
            }

            // Step 1: Set ALL drafts for this instance in DHIS2 local database
            _syncState.value = _syncState.value.copy(progress = 20, step = "Preparing data for upload...")
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

            _syncState.value = _syncState.value.copy(progress = 40, step = "Uploading data to server...")

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
                        database.dataValueDraftDao().deleteDraft(draft)
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
                queueSize = database.dataValueDraftDao().getAllDrafts().size,
                lastSuccessfulSync = if (allSuccessfulDrafts.isNotEmpty()) System.currentTimeMillis() else _syncState.value.lastSuccessfulSync,
                failedAttempts = if (allSuccessfulDrafts.isNotEmpty()) 0 else _syncState.value.failedAttempts + 1,
                error = if (allSuccessfulDrafts.isEmpty()) "Upload failed for dataset instance" else null,
                progress = 100,
                step = "Sync complete"
            )

            Log.d(tag, "Sync completed for instance - ${allSuccessfulDrafts.size} values uploaded successfully")

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
        }
    }

    suspend fun cancelSync() {
        syncJob?.cancel()
        _syncState.value = _syncState.value.copy(
            isRunning = false,
            error = "Sync cancelled by user"
        )
    }
    
    suspend fun clearQueue() {
        database.dataValueDraftDao().getAllDrafts().forEach { draft ->
            database.dataValueDraftDao().deleteDraft(draft)
        }
        _syncState.value = _syncState.value.copy(queueSize = 0)
    }

    fun cleanup() {
        syncJob?.cancel()
        networkMonitorJob?.cancel()
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
}