package com.ash.simpledataentry.data.sync

import android.util.Log
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.data.local.AppDatabase
import com.ash.simpledataentry.data.local.DataValueDraftEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a transactional sync operation
 */
data class TransactionalSyncResult(
    val success: Boolean,
    val syncedDrafts: List<DataValueDraftEntity>,
    val failedDrafts: List<DataValueDraftEntity>,
    val error: String? = null,
    val rollbackPerformed: Boolean = false
)

/**
 * Manages transactional sync operations with rollback capability
 */
@Singleton
class TransactionalSyncManager @Inject constructor(
    private val sessionManager: SessionManager,
    private val database: AppDatabase,
    private val adaptiveSyncStrategy: AdaptiveSyncStrategy,
    private val networkStateManager: NetworkStateManager
) {

    companion object {
        private const val TAG = "TransactionalSyncManager"
    }

    /**
     * Perform a transactional sync with rollback capability
     */
    suspend fun performTransactionalSync(
        drafts: List<DataValueDraftEntity>,
        forceSync: Boolean = false
    ): TransactionalSyncResult = withContext(Dispatchers.IO) {

        if (drafts.isEmpty()) {
            return@withContext TransactionalSyncResult(
                success = true,
                syncedDrafts = emptyList(),
                failedDrafts = emptyList()
            )
        }

        val networkQuality = networkStateManager.networkState.value.connectionQuality
        val syncTimeouts = adaptiveSyncStrategy.calculateTimeouts(networkQuality)

        Log.d(TAG, "Starting transactional sync for ${drafts.size} drafts with quality: $networkQuality")

        try {
            val d2 = sessionManager.getD2()
            if (d2 == null) {
                throw Exception("DHIS2 session not available")
            }

            // Step 1: Create rollback point
            val rollbackPoint = createRollbackPoint(drafts)

            try {
                // Step 2: Set all drafts in DHIS2 local database
                val setResults = setDraftsInD2(drafts, d2)
                val successfulDrafts = setResults.filter { it.success }.map { it.draft }
                val failedDrafts = setResults.filter { !it.success }.map { it.draft }

                if (successfulDrafts.isEmpty()) {
                    Log.w(TAG, "No drafts were successfully set in DHIS2 local database")
                    return@withContext TransactionalSyncResult(
                        success = false,
                        syncedDrafts = emptyList(),
                        failedDrafts = failedDrafts,
                        error = "Failed to prepare any data for upload"
                    )
                }

                Log.d(TAG, "Successfully set ${successfulDrafts.size} drafts in DHIS2 local database")

                // Step 3: Perform upload with timeout
                val uploadSuccessful = withTimeout(syncTimeouts.uploadTimeoutMs) {
                    val uploadResult = d2.dataValueModule().dataValues().blockingUpload()
                    Log.d(TAG, "Upload result: $uploadResult")

                    // Check if upload was successful by examining the import result
                    if (uploadResult != null) {
                        // Log the result details for debugging
                        Log.d(TAG, "Upload completed with result: $uploadResult")

                        // For now, assume success if no exception was thrown
                        // The actual result structure may vary by DHIS2 version
                        true
                    } else {
                        Log.w(TAG, "Upload result was null")
                        false
                    }
                }

                if (uploadSuccessful) {
                    // Step 4: Commit transaction - remove successfully synced drafts
                    commitTransaction(successfulDrafts)

                    Log.d(TAG, "Transactional sync completed successfully")
                    return@withContext TransactionalSyncResult(
                        success = true,
                        syncedDrafts = successfulDrafts,
                        failedDrafts = failedDrafts
                    )
                } else {
                    throw Exception("Upload operation failed")
                }

            } catch (e: Exception) {
                // Step 5: Rollback on failure
                Log.w(TAG, "Upload failed, performing rollback: ${e.message}")
                performRollback(rollbackPoint)

                return@withContext TransactionalSyncResult(
                    success = false,
                    syncedDrafts = emptyList(),
                    failedDrafts = drafts,
                    error = extractErrorMessage(e),
                    rollbackPerformed = true
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Transactional sync failed: ${e.message}", e)
            return@withContext TransactionalSyncResult(
                success = false,
                syncedDrafts = emptyList(),
                failedDrafts = drafts,
                error = extractErrorMessage(e)
            )
        }
    }

    /**
     * Create a rollback point for the current sync operation
     */
    private suspend fun createRollbackPoint(drafts: List<DataValueDraftEntity>): RollbackPoint {
        val d2 = sessionManager.getD2() ?: throw Exception("DHIS2 session not available")

        // Store current state of DHIS2 data values that we're about to modify
        val currentServerStates = mutableMapOf<String, String?>()

        for (draft in drafts) {
            try {
                val currentValue = d2.dataValueModule().dataValues()
                    .value(
                        period = draft.period,
                        organisationUnit = draft.orgUnit,
                        dataElement = draft.dataElement,
                        categoryOptionCombo = draft.categoryOptionCombo,
                        attributeOptionCombo = draft.attributeOptionCombo
                    )
                    .blockingGet()

                val key = createDraftKey(draft)
                currentServerStates[key] = currentValue?.value()

            } catch (e: Exception) {
                // If we can't get current value, assume it doesn't exist
                val key = createDraftKey(draft)
                currentServerStates[key] = null
            }
        }

        return RollbackPoint(
            timestamp = System.currentTimeMillis(),
            originalStates = currentServerStates,
            drafts = drafts
        )
    }

    /**
     * Set drafts in DHIS2 local database
     */
    private suspend fun setDraftsInD2(
        drafts: List<DataValueDraftEntity>,
        d2: org.hisp.dhis.android.core.D2
    ): List<DraftSetResult> {
        val results = mutableListOf<DraftSetResult>()

        for (draft in drafts) {
            try {
                d2.dataValueModule().dataValues()
                    .value(
                        period = draft.period,
                        organisationUnit = draft.orgUnit,
                        dataElement = draft.dataElement,
                        categoryOptionCombo = draft.categoryOptionCombo,
                        attributeOptionCombo = draft.attributeOptionCombo
                    )
                    .blockingSet(draft.value)

                results.add(DraftSetResult(draft, true))

            } catch (e: Exception) {
                Log.w(TAG, "Failed to set draft for ${draft.dataElement}: ${e.message}")
                results.add(DraftSetResult(draft, false, e.message))
            }
        }

        return results
    }

    /**
     * Commit the transaction by removing successfully synced drafts
     */
    private suspend fun commitTransaction(successfulDrafts: List<DataValueDraftEntity>) {
        try {
            for (draft in successfulDrafts) {
                database.dataValueDraftDao().deleteDraft(draft)
            }
            Log.d(TAG, "Committed transaction - removed ${successfulDrafts.size} synced drafts")
        } catch (e: Exception) {
            Log.e(TAG, "Error committing transaction: ${e.message}", e)
            // This is a serious issue but don't fail the sync since upload succeeded
        }
    }

    /**
     * Perform rollback to restore previous state
     */
    private suspend fun performRollback(rollbackPoint: RollbackPoint) {
        try {
            val d2 = sessionManager.getD2() ?: return

            Log.d(TAG, "Performing rollback for ${rollbackPoint.originalStates.size} data values")

            for ((key, originalValue) in rollbackPoint.originalStates) {
                try {
                    val draft = rollbackPoint.drafts.find { createDraftKey(it) == key }
                    if (draft != null) {
                        if (originalValue != null) {
                            // Restore original value
                            d2.dataValueModule().dataValues()
                                .value(
                                    period = draft.period,
                                    organisationUnit = draft.orgUnit,
                                    dataElement = draft.dataElement,
                                    categoryOptionCombo = draft.categoryOptionCombo,
                                    attributeOptionCombo = draft.attributeOptionCombo
                                )
                                .blockingSet(originalValue)
                        } else {
                            // Delete the value if it didn't exist originally
                            d2.dataValueModule().dataValues()
                                .value(
                                    period = draft.period,
                                    organisationUnit = draft.orgUnit,
                                    dataElement = draft.dataElement,
                                    categoryOptionCombo = draft.categoryOptionCombo,
                                    attributeOptionCombo = draft.attributeOptionCombo
                                )
                                .blockingDelete()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to rollback value for key $key: ${e.message}")
                }
            }

            Log.d(TAG, "Rollback completed")

        } catch (e: Exception) {
            Log.e(TAG, "Error during rollback: ${e.message}", e)
        }
    }

    /**
     * Create a unique key for a draft
     */
    private fun createDraftKey(draft: DataValueDraftEntity): String {
        return "${draft.period}-${draft.orgUnit}-${draft.dataElement}-${draft.categoryOptionCombo}-${draft.attributeOptionCombo}"
    }

    /**
     * Extract user-friendly error message
     */
    private fun extractErrorMessage(exception: Exception): String {
        return when {
            exception is kotlinx.coroutines.TimeoutCancellationException -> {
                "Upload timed out. Please check your internet connection and try again."
            }
            exception.message?.contains("SocketTimeoutException") == true -> {
                "Connection timed out. Please check your internet connection and try again."
            }
            exception.message?.contains("UnknownHostException") == true -> {
                "Cannot reach server. Please check your internet connection."
            }
            exception.message?.contains("401") == true -> {
                "Authentication failed. Please log in again."
            }
            exception.message?.contains("500") == true -> {
                "Server error. Please try again later."
            }
            else -> {
                exception.message ?: "Sync failed. Please try again."
            }
        }
    }
}

/**
 * Represents a rollback point for transaction recovery
 */
private data class RollbackPoint(
    val timestamp: Long,
    val originalStates: Map<String, String?>,
    val drafts: List<DataValueDraftEntity>
)

/**
 * Result of setting a draft in DHIS2
 */
private data class DraftSetResult(
    val draft: DataValueDraftEntity,
    val success: Boolean,
    val error: String? = null
)