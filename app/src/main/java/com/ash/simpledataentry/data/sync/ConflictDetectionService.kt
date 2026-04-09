package com.ash.simpledataentry.data.sync

import android.util.Log
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.data.local.AppDatabase
import com.ash.simpledataentry.data.local.DataValueDraftEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data class representing a conflict between local and server data
 */
data class DataConflict(
    val draft: DataValueDraftEntity,
    val serverValue: String?,
    val localValue: String,
    val lastModified: Long?,
    val conflictType: ConflictType
)

/**
 * Types of conflicts that can occur
 */
enum class ConflictType {
    SERVER_NEWER,    // Server has newer data than local
    LOCAL_NEWER,     // Local data is newer than server
    BOTH_MODIFIED,   // Both local and server have been modified
    SERVER_DELETED,  // Data exists locally but was deleted on server
    UNKNOWN         // Cannot determine conflict type
}

/**
 * Resolution strategy for conflicts
 */
enum class ResolutionStrategy {
    CANCEL_SYNC,           // Cancel the entire sync operation
    KEEP_LOCAL_ALL,        // Keep all local changes, overwrite server
    KEEP_SERVER_ALL,       // Keep all server data, discard local changes
    RESOLVE_INDIVIDUALLY,  // Resolve each conflict individually
    MERGE_COMPATIBLE      // Merge compatible changes automatically
}

/**
 * Individual conflict resolution
 */
enum class ConflictResolution {
    KEEP_LOCAL,    // Keep the local value
    KEEP_SERVER,   // Keep the server value
    SKIP          // Skip this value (don't sync)
}

/**
 * Service for detecting and managing data conflicts
 */
@Singleton
class ConflictDetectionService @Inject constructor(
    private val sessionManager: SessionManager,
    private val database: AppDatabase
) {

    companion object {
        private const val TAG = "ConflictDetectionService"
    }

    /**
     * Detect conflicts between local drafts and server data
     */
    suspend fun detectConflicts(localDrafts: List<DataValueDraftEntity>): List<DataConflict> = withContext(Dispatchers.IO) {
        val conflicts = mutableListOf<DataConflict>()

        if (localDrafts.isEmpty()) {
            return@withContext conflicts
        }

        try {
            val d2 = sessionManager.getD2()
            if (d2 == null) {
                Log.w(TAG, "DHIS2 session not available for conflict detection")
                return@withContext conflicts
            }

            Log.d(TAG, "Checking ${localDrafts.size} drafts for conflicts")

            // Fetch server values for all drafts
            val serverValues = fetchServerValues(localDrafts)

            // Compare each draft with server data
            for (draft in localDrafts) {
                val draftKey = createDraftKey(draft)
                val serverValue = serverValues[draftKey]

                val conflict = analyzeConflict(draft, serverValue)
                if (conflict != null) {
                    conflicts.add(conflict)
                }
            }

            Log.d(TAG, "Found ${conflicts.size} conflicts out of ${localDrafts.size} drafts")

        } catch (e: Exception) {
            Log.e(TAG, "Error during conflict detection: ${e.message}", e)
            // Return empty list on error - sync will proceed normally
        }

        conflicts
    }

    /**
     * Fetch server values for the given drafts
     */
    private suspend fun fetchServerValues(drafts: List<DataValueDraftEntity>): Map<String, ServerDataValue?> = withContext(Dispatchers.IO) {
        val serverValues = mutableMapOf<String, ServerDataValue?>()

        try {
            val d2 = sessionManager.getD2() ?: return@withContext serverValues

            // Group drafts by period and organization unit for efficient fetching
            val groupedDrafts = drafts.groupBy { "${it.period}-${it.orgUnit}" }

            for ((key, groupDrafts) in groupedDrafts) {
                try {
                    val period = groupDrafts.first().period
                    val orgUnit = groupDrafts.first().orgUnit

                    // Fetch data values for this period/org unit combination
                    val dataValues = d2.dataValueModule().dataValues()
                        .byPeriod().eq(period)
                        .byOrganisationUnitUid().eq(orgUnit)
                        .blockingGet()

                    // Map server values by our key format
                    for (dataValue in dataValues) {
                        val draftKey = "${period}-${orgUnit}-${dataValue.dataElement()}-${dataValue.categoryOptionCombo()}-${dataValue.attributeOptionCombo()}"
                        serverValues[draftKey] = ServerDataValue(
                            value = dataValue.value(),
                            lastUpdated = dataValue.lastUpdated()?.time
                        )
                    }

                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch server values for $key: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching server values: ${e.message}", e)
        }

        serverValues
    }

    /**
     * Analyze if there's a conflict between local draft and server data
     */
    private fun analyzeConflict(draft: DataValueDraftEntity, serverValue: ServerDataValue?): DataConflict? {
        val localValue = draft.value ?: ""

        // No conflict if no server data exists
        if (serverValue == null) {
            return null
        }

        val serverValueStr = serverValue.value ?: ""

        // No conflict if values are the same
        if (localValue == serverValueStr) {
            return null
        }

        // Determine conflict type
        val conflictType = when {
            serverValue.lastUpdated != null && draft.lastModified < serverValue.lastUpdated -> ConflictType.SERVER_NEWER
            serverValue.lastUpdated != null && draft.lastModified > serverValue.lastUpdated -> ConflictType.LOCAL_NEWER
            serverValue.lastUpdated != null -> ConflictType.BOTH_MODIFIED
            serverValueStr.isEmpty() -> ConflictType.SERVER_DELETED
            else -> ConflictType.UNKNOWN
        }

        return DataConflict(
            draft = draft,
            serverValue = serverValueStr,
            localValue = localValue,
            lastModified = serverValue.lastUpdated,
            conflictType = conflictType
        )
    }

    /**
     * Create a unique key for a draft to match with server data
     */
    private fun createDraftKey(draft: DataValueDraftEntity): String {
        return "${draft.period}-${draft.orgUnit}-${draft.dataElement}-${draft.categoryOptionCombo}-${draft.attributeOptionCombo}"
    }

    /**
     * Apply conflict resolutions to drafts
     */
    suspend fun applyConflictResolutions(
        conflicts: List<DataConflict>,
        resolutions: Map<DataConflict, ConflictResolution>
    ): ConflictResolutionResult = withContext(Dispatchers.IO) {
        val resolvedDrafts = mutableListOf<DataValueDraftEntity>()
        val skippedDrafts = mutableListOf<DataValueDraftEntity>()
        val modifiedDrafts = mutableListOf<DataValueDraftEntity>()

        for (conflict in conflicts) {
            val resolution = resolutions[conflict] ?: ConflictResolution.KEEP_LOCAL

            when (resolution) {
                ConflictResolution.KEEP_LOCAL -> {
                    // Keep the local draft as-is
                    resolvedDrafts.add(conflict.draft)
                }
                ConflictResolution.KEEP_SERVER -> {
                    // Update local draft with server value
                    val updatedDraft = conflict.draft.copy(
                        value = conflict.serverValue,
                        lastModified = System.currentTimeMillis()
                    )
                    // Remove the draft since server value should be used
                    database.dataValueDraftDao().deleteDraft(conflict.draft)
                    modifiedDrafts.add(updatedDraft)
                }
                ConflictResolution.SKIP -> {
                    // Don't sync this draft
                    skippedDrafts.add(conflict.draft)
                }
            }
        }

        ConflictResolutionResult(
            resolvedDrafts = resolvedDrafts,
            skippedDrafts = skippedDrafts,
            modifiedDrafts = modifiedDrafts
        )
    }

    /**
     * Get a user-friendly description of a conflict
     */
    fun getConflictDescription(conflict: DataConflict): String {
        return when (conflict.conflictType) {
            ConflictType.SERVER_NEWER -> "Server has newer data (${conflict.serverValue ?: "empty"})"
            ConflictType.LOCAL_NEWER -> "Your local data is newer (${conflict.localValue})"
            ConflictType.BOTH_MODIFIED -> "Both local and server data have been modified"
            ConflictType.SERVER_DELETED -> "Data was deleted on server"
            ConflictType.UNKNOWN -> "Conflict detected between local and server data"
        }
    }

    /**
     * Get recommended resolution for a conflict
     */
    fun getRecommendedResolution(conflict: DataConflict): ConflictResolution {
        return when (conflict.conflictType) {
            ConflictType.SERVER_NEWER -> ConflictResolution.KEEP_SERVER
            ConflictType.LOCAL_NEWER -> ConflictResolution.KEEP_LOCAL
            ConflictType.BOTH_MODIFIED -> ConflictResolution.KEEP_LOCAL // Default to local for user control
            ConflictType.SERVER_DELETED -> ConflictResolution.KEEP_LOCAL // Restore deleted data
            ConflictType.UNKNOWN -> ConflictResolution.KEEP_LOCAL
        }
    }
}

/**
 * Server data value representation
 */
private data class ServerDataValue(
    val value: String?,
    val lastUpdated: Long?
)

/**
 * Result of conflict resolution process
 */
data class ConflictResolutionResult(
    val resolvedDrafts: List<DataValueDraftEntity>,
    val skippedDrafts: List<DataValueDraftEntity>,
    val modifiedDrafts: List<DataValueDraftEntity>
)