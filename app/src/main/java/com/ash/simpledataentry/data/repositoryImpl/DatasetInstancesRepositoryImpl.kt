package com.ash.simpledataentry.data.repositoryImpl

import android.util.Log
import com.ash.simpledataentry.domain.model.DatasetInstance
import com.ash.simpledataentry.domain.model.DatasetInstanceState
import com.ash.simpledataentry.domain.model.Period
import com.ash.simpledataentry.domain.repository.DatasetInstancesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.rx2.await
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.data.DatabaseProvider
import com.ash.simpledataentry.data.D2Provider
import com.ash.simpledataentry.data.cache.OrgUnitCacheService
import com.ash.simpledataentry.data.local.DraftInstanceSummary
import org.hisp.dhis.android.core.D2
import org.hisp.dhis.android.core.organisationunit.OrganisationUnit
import javax.inject.Inject

private const val TAG = "DatasetInstancesRepo"

class DatasetInstancesRepositoryImpl @Inject constructor(
    private val sessionManager: SessionManager,
    private val databaseProvider: DatabaseProvider,
    private val d2Provider: D2Provider,
    private val orgUnitCache: OrgUnitCacheService
) : DatasetInstancesRepository {

    /**
     * Use d2Provider.awaitD2() in suspend functions for safe, non-racing access.
     */
    private val d2: D2? get() = d2Provider.getD2OrNull()

    // Lazy DAO accessors - always get from current account's database
    private val trackerEnrollmentDao get() = databaseProvider.getCurrentDatabase().trackerEnrollmentDao()
    private val eventInstanceDao get() = databaseProvider.getCurrentDatabase().eventInstanceDao()

    override suspend fun getDatasetInstances(datasetId: String): List<DatasetInstance> {
        Log.d(TAG, "Fetching dataset instances for dataset: $datasetId")
        return withContext(Dispatchers.IO) {
            val d2Instance = d2Provider.awaitD2()
            try {
                Log.d(TAG, "Fetching scoped org units and dataset attachments for $datasetId")

                // Use cached scoped org units
                val scopedOrgUnits = orgUnitCache.getScopedOrgUnits()

                if (scopedOrgUnits.isEmpty()) {
                    Log.e(TAG, "No organization units found in data capture scope")
                    return@withContext emptyList()
                }

                val attachedOrgUnits = d2Instance.organisationUnitModule().organisationUnits()
                    .byDataSetUids(listOf(datasetId))
                    .get().await()

                val scopedIds = scopedOrgUnits.map { it.uid() }.toSet()
                val attachedIds = attachedOrgUnits.map { it.uid() }.toSet()
                val relevantOrgUnitIds = scopedIds.intersect(attachedIds).ifEmpty { scopedIds }

                Log.d(
                    TAG,
                    "Scoped=${scopedIds.size}, Attached=${attachedIds.size}, Relevant=${relevantOrgUnitIds.size}"
                )

                // Get dataset instances across all relevant org units
                val instances = d2Instance.dataSetModule()
                    .dataSetInstances()
                    .byDataSetUid().eq(datasetId)
                    .byOrganisationUnitUid().`in`(relevantOrgUnitIds.toList())
                    .get().await()

                Log.d(TAG, "Loaded ${instances.size} instances for dataset")


                val sdkInstances = instances.map { instance ->
                    Log.d(TAG, "Processing SDK instance: ${instance.id()} | datasetId: ${instance.dataSetUid()} | period: ${instance.period()} | orgUnit: ${instance.organisationUnitUid()} | attributeOptionComboUid: ${instance.attributeOptionComboUid()} | attributeOptionComboDisplayName: ${instance.attributeOptionComboDisplayName()}")
                    DatasetInstance(
                        id = instance.id().toString(),
                        datasetId = instance.dataSetUid(),
                        period = Period(id = instance.period()),
                        organisationUnit = com.ash.simpledataentry.domain.model.OrganisationUnit(
                            id = instance.organisationUnitUid(),
                            name = instance.organisationUnitDisplayName() ?: ""
                        ),
                        attributeOptionCombo = instance.attributeOptionComboUid() ?: "",
                        state = when {
                            instance.completed() == true -> DatasetInstanceState.COMPLETE
                            else -> DatasetInstanceState.OPEN
                        },
                        lastUpdated = instance.lastUpdated()?.toString()
                    )
                }
                
                // Get draft instances that don't have corresponding SDK instances
                val draftInstances = databaseProvider.getCurrentDatabase().dataValueDraftDao().getDistinctDraftInstances(datasetId)
                Log.d(TAG, "Found ${draftInstances.size} draft instances")
                
                val sdkInstanceKeys = sdkInstances.map { "${it.period.id}-${it.organisationUnit.id}-${it.attributeOptionCombo}" }.toSet()
                
                val draftOnlyInstances = draftInstances.filter { draft ->
                    val key = "${draft.period}-${draft.orgUnit}-${draft.attributeOptionCombo}"
                    !sdkInstanceKeys.contains(key)
                }.map { draft ->
                    Log.d(TAG, "Processing draft-only instance: datasetId: ${draft.datasetId} | period: ${draft.period} | orgUnit: ${draft.orgUnit} | attributeOptionCombo: ${draft.attributeOptionCombo}")
                    
                    // Get org unit name from DHIS2 SDK
                    val orgUnitName = try {
                        d2Instance.organisationUnitModule().organisationUnits()
                            .uid(draft.orgUnit)
                            .get().await()?.displayName() ?: draft.orgUnit
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not get org unit name for ${draft.orgUnit}", e)
                        draft.orgUnit
                    }
                    
                    DatasetInstance(
                        id = "draft-${draft.datasetId}-${draft.period}-${draft.orgUnit}-${draft.attributeOptionCombo}",
                        datasetId = draft.datasetId,
                        period = Period(id = draft.period),
                        organisationUnit = com.ash.simpledataentry.domain.model.OrganisationUnit(
                            id = draft.orgUnit,
                            name = orgUnitName
                        ),
                        attributeOptionCombo = draft.attributeOptionCombo,
                        state = DatasetInstanceState.OPEN, // Draft instances are always open
                        lastUpdated = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault())
                            .format(java.util.Date(draft.lastModified))
                    )
                }
                
                val allInstances = (sdkInstances + draftOnlyInstances).sortedByDescending { it.lastUpdated }
                Log.d(TAG, "Returning ${allInstances.size} total instances (${sdkInstances.size} SDK + ${draftOnlyInstances.size} draft-only)")
                allInstances
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching dataset instances", e)
                throw e
            }
        }
    }

    override suspend fun syncDatasetInstances() {
        Log.d(TAG, "Starting complete data sync (datasets, tracker, events)")
        withContext(Dispatchers.IO) {
            val d2Instance = d2 ?: run {
                Log.w(TAG, "D2 session not available for syncDatasetInstances")
                return@withContext
            }
            try {
                // Upload local changes to dataset instances
               // d2Instance.dataSetModule().dataSetInstances().blockingUpload()

                // Download latest dataset instances from server
                Log.d(TAG, "Syncing dataset instances...")
                d2Instance.dataSetModule().dataSetInstances().get().await()

                // Download tracker data (enrollments and their events)
                Log.d(TAG, "Syncing tracker data...")

                // Get tracker programs
                val trackerPrograms = d2Instance.programModule().programs()
                    .byProgramType().eq(org.hisp.dhis.android.core.program.ProgramType.WITH_REGISTRATION)
                    .get().await()

                Log.d(TAG, "Found ${trackerPrograms.size} tracker programs to sync")

                // Use EXACT official Android Capture app patterns
                Log.d(TAG, "Using EXACT official Android Capture app patterns")

                if (trackerPrograms.isNotEmpty()) {
                    // Download for each tracker program using official pattern
                    trackerPrograms.forEach { program ->
                        val programUid = program.uid()
                        Log.d(TAG, "Syncing tracker data for program: $programUid")

                        d2Instance.trackedEntityModule().trackedEntityInstanceDownloader()
                            .byProgramUid(programUid)
                            .download()
                    }
                } else {
                    Log.d(TAG, "No tracker programs found, downloading all tracker data")
                    // Fallback: download all tracker data without program filter
                    d2Instance.trackedEntityModule().trackedEntityInstanceDownloader()
                        .download()
                }

                // Download standalone events using official pattern
                Log.d(TAG, "Syncing event data...")
                d2Instance.eventModule().eventDownloader()
                    .download()

                Log.d(TAG, "Complete sync completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error during sync", e)
                throw e
            }
        }
    }

    override suspend fun completeDatasetInstance(datasetId: String, period: String, orgUnit: String, attributeOptionCombo: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val d2Instance = d2Provider.awaitD2()
                Log.d(TAG, "Marking dataset as complete: $datasetId, $period, $orgUnit, $attributeOptionCombo")
                // These are mutation operations - use blocking versions (run on IO dispatcher)
                d2Instance.dataSetModule().dataSetCompleteRegistrations().value(period,orgUnit,datasetId,attributeOptionCombo).blockingSet()
                d2Instance.dataSetModule().dataSetCompleteRegistrations().blockingUpload()
                Log.d(TAG, "Dataset marked as complete successfully.")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete dataset instance", e)
                Result.failure(e)
            }
        }
    }
    
    override suspend fun getDatasetInstanceCount(datasetId: String, orgUnitIds: Set<String>?): Int {
        return withContext(Dispatchers.IO) {
            try {
                val d2Instance = d2Provider.awaitD2()
                Log.d(TAG, "Getting instance count for dataset: $datasetId")

                // Use cached scoped org units
                val scopedOrgUnits = orgUnitCache.getScopedOrgUnits()

                if (scopedOrgUnits.isEmpty()) {
                    Log.e(TAG, "No organization units found in data capture scope")
                    return@withContext 0
                }

                val scopedIds = scopedOrgUnits.map { it.uid() }.toSet()
                val relevantOrgUnitIds = if (orgUnitIds != null) {
                    orgUnitIds.intersect(scopedIds)
                } else {
                    val attachedOrgUnits = d2Instance.organisationUnitModule().organisationUnits()
                        .byDataSetUids(listOf(datasetId))
                        .get().await()
                    val attachedIds = attachedOrgUnits.map { it.uid() }.toSet()
                    scopedIds.intersect(attachedIds).ifEmpty { scopedIds }
                }

                if (relevantOrgUnitIds.isEmpty()) {
                    Log.d(TAG, "No relevant org units for dataset $datasetId under current filter")
                    return@withContext 0
                }

                // Count dataset instances across all relevant org units
                val instances = d2Instance.dataSetModule()
                    .dataSetInstances()
                    .byDataSetUid().eq(datasetId)
                    .byOrganisationUnitUid().`in`(relevantOrgUnitIds.toList())
                    .get().await()

                val count = instances.size
                Log.d(TAG, "Found $count instances for dataset $datasetId")
                count
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get instance count for dataset $datasetId", e)
                0
            }
        }
    }

    /**
     * Batch fetch instance counts for multiple datasets in a single query.
     * This avoids the N+1 query problem when loading the dataset list.
     * Gets all scoped org units once, then all instances for all datasets,
     * and groups by dataset ID.
     */
    override suspend fun getDatasetInstanceCounts(datasetIds: List<String>): Map<String, Int> {
        if (datasetIds.isEmpty()) return emptyMap()

        return withContext(Dispatchers.IO) {
            try {
                val d2Instance = d2Provider.awaitD2()
                Log.d(TAG, "Getting batch instance counts for ${datasetIds.size} datasets")

                // Use cached scoped org units
                val scopedOrgUnits = orgUnitCache.getScopedOrgUnits()

                if (scopedOrgUnits.isEmpty()) {
                    Log.e(TAG, "No organization units found in data capture scope")
                    return@withContext emptyMap()
                }

                val scopedIds = scopedOrgUnits.map { it.uid() }.toSet()

                // Get all instances for all datasets in ONE query
                val allInstances = d2Instance.dataSetModule().dataSetInstances()
                    .byDataSetUid().`in`(datasetIds)
                    .byOrganisationUnitUid().`in`(scopedIds.toList())
                    .get().await()

                // Group by dataset ID and count
                val counts = allInstances
                    .groupBy { it.dataSetUid() }
                    .mapValues { it.value.size }

                Log.d(TAG, "Batch count complete: ${counts.size} datasets have instances, total ${allInstances.size} instances")
                counts
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get batch instance counts", e)
                emptyMap()
            }
        }
    }

    override suspend fun markDatasetInstanceIncomplete(datasetId: String, period: String, orgUnit: String, attributeOptionCombo: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val d2Instance = d2Provider.awaitD2()
                Log.d(TAG, "Marking dataset as incomplete: $datasetId, $period, $orgUnit, $attributeOptionCombo")
                // These are mutation operations - use blocking versions (run on IO dispatcher)
                d2Instance.dataSetModule().dataSetCompleteRegistrations()
                    .value(period, orgUnit, datasetId, attributeOptionCombo).blockingDeleteIfExist()
                d2Instance.dataSetModule().dataSetCompleteRegistrations().blockingUpload()
                Log.d(TAG, "Dataset marked as incomplete successfully.")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark dataset as incomplete", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Dedicated tracker data synchronization method
     * Handles tracker-specific requirements separately from aggregate datasets
     */
    private suspend fun syncTrackerData(programId: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== TRACKER DATA SYNC START for program: $programId ===")
        val d2Instance = d2Provider.awaitD2()

        try {
            // Validate authentication state (quick check, blocking is fine on IO)
            val isLoggedIn = try {
                d2Instance.userModule().isLogged().blockingGet()
            } catch (e: Exception) {
                false
            }

            if (!isLoggedIn) {
                Log.e(TAG, "User not authenticated - cannot sync tracker data in offline mode")
                throw Exception("Cannot sync tracker data in offline mode. Please login online first.")
            }

            val user = d2Instance.userModule().user().blockingGet()
            if (user?.uid() == null) {
                Log.e(TAG, "Invalid authentication state - user UID is null")
                throw Exception("Invalid authentication state")
            }

            Log.d(TAG, "User authenticated: ${user.uid()}")

            // STEP 1: Ensure tracker-specific metadata is complete
            Log.d(TAG, "STEP 1: Ensuring tracker metadata is complete...")
            try {
                // Download complete metadata with special focus on tracker dependencies
                d2Instance.metadataModule().blockingDownload()
                Log.d(TAG, "Metadata sync completed")
            } catch (e: Exception) {
                Log.e(TAG, "Metadata sync failed: ${e.message}", e)
                throw e
            }

            // STEP 2: Check and resolve foreign key violations BEFORE download
            Log.d(TAG, "STEP 2: Pre-download foreign key violation check... (Skipped - using standard SDK handling)")
            // sessionManager.checkForeignKeyViolations() - Removed as part of simplification

            // STEP 3: Get user's org unit scope for tracker data
            val userOrgUnits = d2Instance.organisationUnitModule().organisationUnits()
                .byOrganisationUnitScope(OrganisationUnit.Scope.SCOPE_DATA_CAPTURE)
                .get().await()

            Log.d(TAG, "User has data capture access to ${userOrgUnits.size} org units")
            userOrgUnits.forEach { orgUnit ->
                Log.d(TAG, "  - ${orgUnit.uid()}: ${orgUnit.displayName()}")
            }

            if (userOrgUnits.isEmpty()) {
                Log.e(TAG, "No org units with data capture scope found")
                throw Exception("No organization units found for tracker data access")
            }

            // STEP 4: Perform tracker-specific download
            Log.d(TAG, "STEP 4: Downloading tracker data for program $programId...")

            // Use TrackedEntityInstanceDownloader with specific program filter
            val downloader = d2Instance.trackedEntityModule().trackedEntityInstanceDownloader()
                .byProgramUid(programId)

            // Execute download
            val downloadResult = downloader.download()
            Log.d(TAG, "Tracker download completed with result: $downloadResult")

            // STEP 5: Post-download validation and foreign key violation resolution
            Log.d(TAG, "STEP 5: Post-download validation...")

            // Check for any foreign key violations that may have occurred during download
            // sessionManager.checkForeignKeyViolations() - Removed as part of simplification

            // STEP 6: Verify what was actually stored
            Log.d(TAG, "STEP 6: Verifying stored tracker data...")

            val storedTEIs = d2Instance.trackedEntityModule().trackedEntityInstances()
                .byProgramUids(listOf(programId))
                .get().await()
            Log.d(TAG, "Found ${storedTEIs.size} TrackedEntityInstances in local storage")

            val storedEnrollments = d2Instance.enrollmentModule().enrollments()
                .byProgram().eq(programId)
                .get().await()
            Log.d(TAG, "Found ${storedEnrollments.size} Enrollments in local storage")

            val storedEvents = d2Instance.eventModule().events()
                .byProgramUid().eq(programId)
                .get().await()
            Log.d(TAG, "Found ${storedEvents.size} Events in local storage")

            // STEP 7: Additional diagnostics if no data was stored despite successful API call
            if (storedTEIs.isEmpty() && storedEnrollments.isEmpty()) {
                Log.w(TAG, "DIAGNOSTIC: No tracker data stored despite successful download")

                // Check if program exists and is accessible
                val program = d2Instance.programModule().programs().uid(programId).get().await()
                Log.d(TAG, "DIAGNOSTIC: Program details - UID: ${program?.uid()}, Name: ${program?.displayName()}, Type: ${program?.programType()}")

                // Check if there are any TEIs at all for any program in accessible org units
                val allTEIs = d2Instance.trackedEntityModule().trackedEntityInstances()
                    .get().await()
                Log.d(TAG, "DIAGNOSTIC: Total TEIs in database: ${allTEIs.size}")

                // Check if the issue is org unit scope mismatch
                val allOrgUnitsWithTEIs = d2Instance.trackedEntityModule().trackedEntityInstances()
                    .get().await()
                    .map { it.organisationUnit() }
                    .distinct()
                Log.d(TAG, "DIAGNOSTIC: Org units that have TEI data: ${allOrgUnitsWithTEIs.joinToString(", ")}")

                val userAccessibleOrgUnitUIDs = userOrgUnits.map { it.uid() }
                Log.d(TAG, "DIAGNOSTIC: User accessible org units: ${userAccessibleOrgUnitUIDs.joinToString(", ")}")

                val overlap = allOrgUnitsWithTEIs.intersect(userAccessibleOrgUnitUIDs.toSet())
                Log.d(TAG, "DIAGNOSTIC: Org unit overlap (data exists + user access): ${overlap.joinToString(", ")}")
            }

            // PHASE 2: Populate Room cache after successful SDK sync
            Log.d(TAG, "STEP 7: Populating Room cache...")
            populateTrackerEnrollmentsCache(programId)
            populateEventInstancesCache(programId)

            Log.d(TAG, "=== TRACKER DATA SYNC COMPLETED ===")

        } catch (e: Exception) {
            Log.d(TAG, "=== TRACKER DATA SYNC FAILED ===")
            Log.e(TAG, "Error: ${e.message}", e)
            throw e
        }
    }

    // New unified program instance methods
    override suspend fun getProgramInstances(
        programId: String,
        programType: com.ash.simpledataentry.domain.model.ProgramType
    ): kotlinx.coroutines.flow.Flow<List<com.ash.simpledataentry.domain.model.ProgramInstance>> =
        kotlinx.coroutines.flow.flow {
            when (programType) {
                com.ash.simpledataentry.domain.model.ProgramType.DATASET -> {
                    val datasetInstances = getDatasetInstances(programId)
                    val programInstances = datasetInstances.map { datasetInstance ->
                        com.ash.simpledataentry.domain.model.ProgramInstance.DatasetInstance(
                            id = datasetInstance.id,
                            programId = datasetInstance.datasetId,
                            programName = "Dataset", // TODO: Get actual dataset name
                            organisationUnit = datasetInstance.organisationUnit,
                            lastUpdated = try {
                                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault())
                                    .parse(datasetInstance.lastUpdated ?: java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date()))
                            } catch (e: Exception) {
                                java.util.Date()
                            },
                            state = when (datasetInstance.state) {
                                DatasetInstanceState.OPEN -> com.ash.simpledataentry.domain.model.ProgramInstanceState.ACTIVE
                                DatasetInstanceState.COMPLETE -> com.ash.simpledataentry.domain.model.ProgramInstanceState.COMPLETED
                                DatasetInstanceState.APPROVED -> com.ash.simpledataentry.domain.model.ProgramInstanceState.APPROVED
                                DatasetInstanceState.LOCKED -> com.ash.simpledataentry.domain.model.ProgramInstanceState.LOCKED
                            },
                            syncStatus = com.ash.simpledataentry.domain.model.SyncStatus.SYNCED, // TODO: Determine actual sync status
                            period = datasetInstance.period,
                            attributeOptionCombo = datasetInstance.attributeOptionCombo,
                            originalDatasetInstance = datasetInstance
                        )
                    }
                    emit(programInstances)
                }
                com.ash.simpledataentry.domain.model.ProgramType.TRACKER -> {
                    getTrackerEnrollments(programId).collect { enrollments ->
                        emit(enrollments.map { it as com.ash.simpledataentry.domain.model.ProgramInstance })
                    }
                }
                com.ash.simpledataentry.domain.model.ProgramType.EVENT -> {
                    getEventInstances(programId).collect { events ->
                        emit(events.map { it as com.ash.simpledataentry.domain.model.ProgramInstance })
                    }
                }
                else -> emit(emptyList())
            }
        }

    override suspend fun getProgramInstanceCount(
        programId: String,
        programType: com.ash.simpledataentry.domain.model.ProgramType
    ): Int {
        return when (programType) {
            com.ash.simpledataentry.domain.model.ProgramType.DATASET -> getDatasetInstanceCount(programId)
            com.ash.simpledataentry.domain.model.ProgramType.TRACKER -> {
                withContext(Dispatchers.IO) {
                    try {
                        val d2Instance = d2Provider.awaitD2()
                        val scopedOrgUnits = d2Instance.organisationUnitModule().organisationUnits()
                            .byOrganisationUnitScope(OrganisationUnit.Scope.SCOPE_DATA_CAPTURE)
                            .get().await()
                        if (scopedOrgUnits.isEmpty()) {
                            return@withContext 0
                        }

                        val attachedOrgUnits = d2Instance.organisationUnitModule().organisationUnits()
                            .byProgramUids(listOf(programId))
                            .get().await()

                        val scopedIds = scopedOrgUnits.map { it.uid() }.toSet()
                        val attachedIds = attachedOrgUnits.map { it.uid() }.toSet()
                        val relevantOrgUnitIds = scopedIds.intersect(attachedIds).ifEmpty { scopedIds }

                        val instances = d2Instance.trackedEntityModule().trackedEntityInstances()
                            .byProgramUids(listOf(programId))
                            .byOrganisationUnitUid().`in`(relevantOrgUnitIds.toList())
                            .get().await()
                        instances.size
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get tracker enrollment count for program $programId", e)
                        0
                    }
                }
            }
            com.ash.simpledataentry.domain.model.ProgramType.EVENT -> {
                withContext(Dispatchers.IO) {
                    try {
                        val d2Instance = d2Provider.awaitD2()
                        val scopedOrgUnits = d2Instance.organisationUnitModule().organisationUnits()
                            .byOrganisationUnitScope(OrganisationUnit.Scope.SCOPE_DATA_CAPTURE)
                            .get().await()
                        if (scopedOrgUnits.isEmpty()) {
                            return@withContext 0
                        }

                        val attachedOrgUnits = d2Instance.organisationUnitModule().organisationUnits()
                            .byProgramUids(listOf(programId))
                            .get().await()

                        val scopedIds = scopedOrgUnits.map { it.uid() }.toSet()
                        val attachedIds = attachedOrgUnits.map { it.uid() }.toSet()
                        val relevantOrgUnitIds = scopedIds.intersect(attachedIds).ifEmpty { scopedIds }

                        val events = d2Instance.eventModule().events()
                            .byProgramUid().eq(programId)
                            .byOrganisationUnitUid().`in`(relevantOrgUnitIds.toList())
                            .get().await()
                        events.size
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get event count for program $programId", e)
                        0
                    }
                }
            }
            else -> 0
        }
    }

    override suspend fun syncProgramInstances(
        programId: String,
        programType: com.ash.simpledataentry.domain.model.ProgramType
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val d2Instance = d2Provider.awaitD2()
                when (programType) {
                    com.ash.simpledataentry.domain.model.ProgramType.DATASET -> {
                        syncDatasetInstances()
                    }
                    com.ash.simpledataentry.domain.model.ProgramType.TRACKER -> {
                        syncTrackerData(programId)
                    }
                    com.ash.simpledataentry.domain.model.ProgramType.EVENT -> {
                        // Sync events - download from server
                        Log.d(TAG, "=== EVENT DATA SYNC START for program: $programId ===")

                        // First ensure metadata is up to date (mutation, blocking is fine on IO)
                        d2Instance.metadataModule().blockingDownload()
                        Log.d(TAG, "EVENT SYNC: Metadata sync completed")

                        // Then download actual event data for this specific program
                        Log.d(TAG, "EVENT SYNC: Starting event download...")
                        d2Instance.eventModule().eventDownloader()
                            .byProgramUid(programId)
                            .blockingDownload()

                        // Verify what was actually stored
                        val storedEvents = d2Instance.eventModule().events()
                            .byProgramUid().eq(programId)
                            .get().await()
                        Log.d(TAG, "EVENT SYNC: Found ${storedEvents.size} events in local storage for program $programId")

                        storedEvents.forEach { event ->
                            Log.d(TAG, "EVENT SYNC: - Event ${event.uid()}: status=${event.status()}, orgUnit=${event.organisationUnit()}, date=${event.eventDate()}")
                        }

                        // PHASE 2: Populate Room cache after successful SDK sync
                        Log.d(TAG, "EVENT SYNC: Populating Room cache...")
                        populateEventInstancesCache(programId)

                        Log.d(TAG, "=== EVENT DATA SYNC COMPLETED for program $programId ===")
                    }
                    else -> { /* Do nothing */ }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync program instances for $programId", e)
                Result.failure(e)
            }
        }
    }

    // Tracker-specific methods
    // PHASE 2 REFACTOR: Now uses Room Flow for reactive, non-blocking data access
    override suspend fun getTrackerEnrollments(programId: String): kotlinx.coroutines.flow.Flow<List<com.ash.simpledataentry.domain.model.ProgramInstance.TrackerEnrollment>> =
        trackerEnrollmentDao.getByProgram(programId).map { entities ->
            withContext(Dispatchers.IO) {
                val d2Instance = d2 ?: run {
                    Log.w(TAG, "D2 session not available for getTrackerEnrollments")
                    return@withContext emptyList()
                }
                // PHASE 2 FIX: Read from Room cache (populated by sync)
                // Fetch program name once (metadata, not data-intensive)
                val program = d2Instance.programModule().programs()
                    .uid(programId)
                    .get().await()
                val programName = program?.displayName() ?: program?.name() ?: "Unknown Program"
                val stageNameById = d2Instance.programModule().programStages()
                    .byProgramUid().eq(programId)
                    .get().await()
                    .associate { stage ->
                        val name = stage.displayName() ?: stage.name() ?: stage.uid()
                        stage.uid() to name
                    }

                // Map Room entities to domain models
                entities.map { entity ->
                    val enrollmentEvents = if (entity.id.isNotBlank()) {
                        try {
                            val cachedEvents = eventInstanceDao.getByEnrollment(entity.id)
                            if (cachedEvents.isNotEmpty()) {
                                cachedEvents.map { eventEntity ->
                                    val stageName = stageNameById[eventEntity.programStageId]
                                        ?: eventEntity.programStageId
                                    com.ash.simpledataentry.domain.model.Event(
                                        id = eventEntity.id,
                                        programId = eventEntity.programId,
                                        programStageId = eventEntity.programStageId,
                                        programStageName = stageName,
                                        enrollmentId = eventEntity.enrollmentId,
                                        programStage = eventEntity.programStageId,
                                        organisationUnitId = eventEntity.organisationUnitId,
                                        organisationUnit = eventEntity.organisationUnitName,
                                        status = eventEntity.status,
                                        deleted = eventEntity.deleted
                                    )
                                }
                            } else {
                                d2Instance.eventModule().events()
                                    .byEnrollmentUid().eq(entity.id)
                                    .get().await()
                                    .map { event ->
                                        val stageId = event.programStage() ?: ""
                                        val stageName = stageNameById[stageId] ?: stageId
                                        com.ash.simpledataentry.domain.model.Event(
                                            id = event.uid(),
                                            programId = event.program() ?: programId,
                                            programStageId = stageId,
                                            programStageName = stageName,
                                            enrollmentId = event.enrollment(),
                                            programStage = stageId,
                                            organisationUnitId = event.organisationUnit() ?: "",
                                            status = event.status()?.name ?: "UNKNOWN",
                                            deleted = event.deleted() ?: false
                                        )
                                    }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to fetch events for enrollment ${entity.id}", e)
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                    // Fetch TEI attributes from SDK (still blocking but much faster than full enrollment query)
                    val teiUid = entity.trackedEntityInstanceId
                    val attributeValues = if (teiUid.isNotEmpty()) {
                        try {
                            d2Instance.trackedEntityModule().trackedEntityAttributeValues()
                                .byTrackedEntityInstance().eq(teiUid)
                                .get().await()
                                .mapNotNull { attrValue ->
                                    val attribute = d2Instance.trackedEntityModule().trackedEntityAttributes()
                                        .uid(attrValue.trackedEntityAttribute())
                                        .get().await()

                                    attribute?.let {
                                        com.ash.simpledataentry.domain.model.TrackedEntityAttributeValue(
                                            id = it.uid(),
                                            displayName = it.displayName() ?: it.name() ?: "",
                                            trackedEntityAttribute = it.uid(),
                                            trackedEntityInstance = teiUid,
                                            value = attrValue.value(),
                                            created = attrValue.created() ?: java.util.Date(),
                                            lastUpdated = attrValue.lastUpdated() ?: java.util.Date()
                                        )
                                    }
                                }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to fetch attributes for TEI $teiUid", e)
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }

                    // Parse dates from strings
                    val enrollmentDate = try {
                        entity.enrollmentDate?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(it) } ?: java.util.Date()
                    } catch (e: Exception) {
                        java.util.Date()
                    }

                    val incidentDate = try {
                        entity.incidentDate?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(it) }
                    } catch (e: Exception) {
                        null
                    }

                    val lastUpdated = try {
                        entity.lastUpdated?.let { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US).parse(it) } ?: java.util.Date()
                    } catch (e: Exception) {
                        java.util.Date()
                    }

                    com.ash.simpledataentry.domain.model.ProgramInstance.TrackerEnrollment(
                        id = entity.id,
                        programId = programId,
                        programName = programName,
                        organisationUnit = com.ash.simpledataentry.domain.model.OrganisationUnit(
                            id = entity.organisationUnitId,
                            name = entity.organisationUnitName
                        ),
                        lastUpdated = lastUpdated,
                        state = when (entity.status) {
                            "ACTIVE" -> com.ash.simpledataentry.domain.model.ProgramInstanceState.ACTIVE
                            "COMPLETED" -> com.ash.simpledataentry.domain.model.ProgramInstanceState.COMPLETED
                            "CANCELLED" -> com.ash.simpledataentry.domain.model.ProgramInstanceState.CANCELLED
                            else -> com.ash.simpledataentry.domain.model.ProgramInstanceState.ACTIVE
                        },
                        syncStatus = com.ash.simpledataentry.domain.model.SyncStatus.SYNCED,  // TODO: Track sync status in Room
                        trackedEntityInstance = teiUid,
                        enrollmentDate = enrollmentDate,
                        incidentDate = incidentDate,
                        followUp = entity.followUp,
                        completedDate = null,  // TODO: Add to Room entity if needed
                        attributes = attributeValues,
                        events = enrollmentEvents
                    )
                }
            }
        }

    override suspend fun createTrackerEnrollment(
        programId: String,
        trackedEntityId: String,
        orgUnitId: String
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            val d2Instance = d2 ?: run {
                Log.w(TAG, "D2 session not available for createTrackerEnrollment")
                return@withContext Result.failure(Exception("D2 session not available"))
            }
            try {
                val enrollmentUid = d2Instance.enrollmentModule().enrollments()
                    .blockingAdd(
                        org.hisp.dhis.android.core.enrollment.EnrollmentCreateProjection.builder()
                            .program(programId)
                            .organisationUnit(orgUnitId)
                            .trackedEntityInstance(trackedEntityId)
                            .build()
                    )
                Result.success(enrollmentUid)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create tracker enrollment", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun completeEnrollment(enrollmentId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val d2Instance = d2 ?: run {
                Log.w(TAG, "D2 session not available for completeEnrollment")
                return@withContext Result.failure(Exception("D2 session not available"))
            }
            try {
                d2Instance.enrollmentModule().enrollments().uid(enrollmentId)
                    .setStatus(org.hisp.dhis.android.core.enrollment.EnrollmentStatus.COMPLETED)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete enrollment", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun cancelEnrollment(enrollmentId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val d2Instance = d2 ?: run {
                Log.w(TAG, "D2 session not available for cancelEnrollment")
                return@withContext Result.failure(Exception("D2 session not available"))
            }
            try {
                d2Instance.enrollmentModule().enrollments().uid(enrollmentId)
                    .setStatus(org.hisp.dhis.android.core.enrollment.EnrollmentStatus.CANCELLED)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel enrollment", e)
                Result.failure(e)
            }
        }
    }

    // Event-specific methods
    override suspend fun getEventInstances(programId: String): kotlinx.coroutines.flow.Flow<List<com.ash.simpledataentry.domain.model.ProgramInstance.EventInstance>> =
        kotlinx.coroutines.flow.flow {
            val d2Instance = d2 ?: run {
                Log.w(TAG, "D2 session not available for getEventInstances")
                emit(emptyList())
                return@flow
            }
            try {
                Log.d(TAG, "Fetching event instances for program: $programId")

                // Get user's data capture org units AND their descendants (children)
                val userOrgUnits = d2Instance.organisationUnitModule().organisationUnits()
                    .byOrganisationUnitScope(OrganisationUnit.Scope.SCOPE_DATA_CAPTURE)
                    .get().await()

                Log.d(TAG, "Found ${userOrgUnits.size} user org units for event instances")

                if (userOrgUnits.isEmpty()) {
                    Log.e(TAG, "No organization units found for user")
                    emit(emptyList())
                    return@flow
                }

                // Get all descendant org units (children) of user's org units
                val allAccessibleOrgUnits = mutableSetOf<String>()
                userOrgUnits.forEach { userOrgUnit ->
                    // Add the user's org unit itself
                    allAccessibleOrgUnits.add(userOrgUnit.uid())

                    // Add all descendants using the path-based approach
                    val descendants = d2Instance.organisationUnitModule().organisationUnits()
                        .byPath().like("${userOrgUnit.path()}/%")
                        .get().await()

                    descendants.forEach { descendant ->
                        allAccessibleOrgUnits.add(descendant.uid())
                    }
                }

                Log.d(TAG, "Total accessible org units for events (including children): ${allAccessibleOrgUnits.size}")

                // Get events using the expanded org unit list
                // NOTE: Cannot add .limit() here - DHIS2 SDK event queries don't support it
                // Full fix in Phase 2: Convert to Room DAOs with proper pagination
                val events = d2Instance.eventModule().events()
                    .byProgramUid().eq(programId)
                    .byOrganisationUnitUid().`in`(allAccessibleOrgUnits.toList())
                    .byStatus().`in`(listOf(
                        org.hisp.dhis.android.core.event.EventStatus.ACTIVE,
                        org.hisp.dhis.android.core.event.EventStatus.COMPLETED,
                        org.hisp.dhis.android.core.event.EventStatus.SCHEDULE,
                        org.hisp.dhis.android.core.event.EventStatus.OVERDUE
                    ))
                    .byDeleted().eq(false)
                    .get().await()
                    .take(50)  // EMERGENCY FIX: Limit in-memory to prevent OOM until Phase 2 Room DAOs ready

                Log.d(TAG, "Found ${events.size} events for program $programId (filtered by ${allAccessibleOrgUnits.size} org units including children, valid statuses, not deleted)")

                val programInstances = events.map { event ->
                    val orgUnit = d2Instance.organisationUnitModule().organisationUnits()
                        .uid(event.organisationUnit())
                        .get().await()

                    val program = d2Instance.programModule().programs()
                        .uid(programId)
                        .get().await()

                    com.ash.simpledataentry.domain.model.ProgramInstance.EventInstance(
                        id = event.uid(),
                        programId = programId,
                        programName = program?.displayName() ?: program?.name() ?: "Unknown Program",
                        organisationUnit = com.ash.simpledataentry.domain.model.OrganisationUnit(
                            id = orgUnit?.uid() ?: "",
                            name = orgUnit?.displayName() ?: "Unknown"
                        ),
                        lastUpdated = event.lastUpdated() ?: java.util.Date(),
                        state = when (event.status()) {
                            org.hisp.dhis.android.core.event.EventStatus.ACTIVE ->
                                com.ash.simpledataentry.domain.model.ProgramInstanceState.ACTIVE
                            org.hisp.dhis.android.core.event.EventStatus.COMPLETED ->
                                com.ash.simpledataentry.domain.model.ProgramInstanceState.COMPLETED
                            org.hisp.dhis.android.core.event.EventStatus.OVERDUE ->
                                com.ash.simpledataentry.domain.model.ProgramInstanceState.OVERDUE
                            org.hisp.dhis.android.core.event.EventStatus.SCHEDULE ->
                                com.ash.simpledataentry.domain.model.ProgramInstanceState.SCHEDULED
                            org.hisp.dhis.android.core.event.EventStatus.SKIPPED ->
                                com.ash.simpledataentry.domain.model.ProgramInstanceState.SKIPPED
                            else -> com.ash.simpledataentry.domain.model.ProgramInstanceState.ACTIVE
                        },
                        syncStatus = when (event.aggregatedSyncState()) {
                            org.hisp.dhis.android.core.common.State.SYNCED -> com.ash.simpledataentry.domain.model.SyncStatus.SYNCED
                            org.hisp.dhis.android.core.common.State.TO_UPDATE -> com.ash.simpledataentry.domain.model.SyncStatus.NOT_SYNCED
                            org.hisp.dhis.android.core.common.State.TO_POST -> com.ash.simpledataentry.domain.model.SyncStatus.NOT_SYNCED
                            else -> com.ash.simpledataentry.domain.model.SyncStatus.SYNCED
                        },
                        programStage = event.programStage() ?: "",
                        eventDate = event.eventDate(),
                        dueDate = event.dueDate(),
                        completedDate = event.completedDate(),
                        coordinates = event.geometry()?.let { geometry ->
                            // Convert DHIS2 geometry to coordinates if needed
                            null // TODO: Implement geometry conversion
                        }
                    )
                }
                emit(programInstances)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get event instances for program $programId", e)
                emit(emptyList())
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun createEventInstance(
        programId: String,
        programStageId: String,
        orgUnitId: String
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            val d2Instance = d2 ?: run {
                Log.w(TAG, "D2 session not available for createEventInstance")
                return@withContext Result.failure(Exception("D2 session not available"))
            }
            try {
                val eventUid = d2Instance.eventModule().events()
                    .blockingAdd(
                        org.hisp.dhis.android.core.event.EventCreateProjection.builder()
                            .program(programId)
                            .programStage(programStageId)
                            .organisationUnit(orgUnitId)
                            .build()
                    )
                Result.success(eventUid)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create event instance", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun completeEvent(eventId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val d2Instance = d2 ?: run {
                Log.w(TAG, "D2 session not available for completeEvent")
                return@withContext Result.failure(Exception("D2 session not available"))
            }
            try {
                d2Instance.eventModule().events().uid(eventId)
                    .setStatus(org.hisp.dhis.android.core.event.EventStatus.COMPLETED)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete event", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun skipEvent(eventId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val d2Instance = d2 ?: run {
                Log.w(TAG, "D2 session not available for skipEvent")
                return@withContext Result.failure(Exception("D2 session not available"))
            }
            try {
                d2Instance.eventModule().events().uid(eventId)
                    .setStatus(org.hisp.dhis.android.core.event.EventStatus.SKIPPED)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to skip event", e)
                Result.failure(e)
            }
        }
    }

    // PHASE 2 HELPER: Populate Room cache from SDK data after sync
    private suspend fun populateTrackerEnrollmentsCache(programId: String) = withContext(Dispatchers.IO) {
        val d2Instance = d2 ?: run {
            Log.w(TAG, "D2 session not available for populateTrackerEnrollmentsCache")
            return@withContext
        }
        try {
            Log.d(TAG, "Populating Room cache for tracker enrollments (program: $programId)")

            // Fetch enrollments from SDK (already downloaded by sync)
            val sdkEnrollments = d2Instance.enrollmentModule().enrollments()
                .byProgram().eq(programId)
                .byDeleted().eq(false)
                .get().await()

            Log.d(TAG, "Found ${sdkEnrollments.size} enrollments in SDK to cache")

            // Convert to Room entities
            val entities = sdkEnrollments.map { enrollment ->
                val orgUnit = d2Instance.organisationUnitModule().organisationUnits()
                    .uid(enrollment.organisationUnit())
                    .get().await()

                com.ash.simpledataentry.data.local.TrackerEnrollmentEntity(
                    id = enrollment.uid(),
                    programId = programId,
                    trackedEntityInstanceId = enrollment.trackedEntityInstance() ?: "",
                    organisationUnitId = enrollment.organisationUnit() ?: "",
                    organisationUnitName = orgUnit?.displayName() ?: "Unknown",
                    enrollmentDate = enrollment.enrollmentDate()?.toString(),
                    incidentDate = enrollment.incidentDate()?.toString(),
                    status = enrollment.status()?.name ?: "ACTIVE",
                    followUp = enrollment.followUp() ?: false,
                    deleted = enrollment.deleted() ?: false,
                    lastUpdated = enrollment.lastUpdated()?.toString()
                )
            }

            // Clear old cache and insert new data
            trackerEnrollmentDao.deleteByProgram(programId)
            trackerEnrollmentDao.insertAll(entities)

            Log.d(TAG, "Room cache populated: ${entities.size} enrollments cached")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to populate tracker enrollments cache", e)
            // Don't throw - cache population failure shouldn't break sync
        }
    }

    private suspend fun populateEventInstancesCache(programId: String) = withContext(Dispatchers.IO) {
        val d2Instance = d2 ?: run {
            Log.w(TAG, "D2 session not available for populateEventInstancesCache")
            return@withContext
        }
        try {
            Log.d(TAG, "Populating Room cache for event instances (program: $programId)")

            // Fetch events from SDK (already downloaded by sync)
            val sdkEvents = d2Instance.eventModule().events()
                .byProgramUid().eq(programId)
                .byDeleted().eq(false)
                .get().await()

            Log.d(TAG, "Found ${sdkEvents.size} events in SDK to cache")

            // Convert to Room entities
            val entities = sdkEvents.map { event ->
                val orgUnit = d2Instance.organisationUnitModule().organisationUnits()
                    .uid(event.organisationUnit())
                    .get().await()

                com.ash.simpledataentry.data.local.EventInstanceEntity(
                    id = event.uid(),
                    programId = programId,
                    programStageId = event.programStage() ?: "",
                    organisationUnitId = event.organisationUnit() ?: "",
                    organisationUnitName = orgUnit?.displayName() ?: "Unknown",
                    eventDate = event.eventDate()?.toString(),
                    status = event.status()?.name ?: "ACTIVE",
                    deleted = event.deleted() ?: false,
                    lastUpdated = event.lastUpdated()?.toString(),
                    enrollmentId = event.enrollment()
                )
            }

            // Clear old cache and insert new data
            eventInstanceDao.deleteByProgram(programId)
            eventInstanceDao.insertAll(entities)

            Log.d(TAG, "Room cache populated: ${entities.size} events cached")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to populate event instances cache", e)
            // Don't throw - cache population failure shouldn't break sync
        }
    }
}
