package com.ash.simpledataentry.data.repositoryImpl

import android.util.Log
import com.ash.simpledataentry.data.DatabaseProvider
import com.ash.simpledataentry.data.D2Provider
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.domain.model.*
import com.ash.simpledataentry.domain.repository.DatasetsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.hisp.dhis.android.core.D2
import org.hisp.dhis.android.core.dataset.DataSet
import org.hisp.dhis.android.core.period.PeriodType
import org.hisp.dhis.android.core.program.ProgramType as SdkProgramType
import java.util.Date
import com.ash.simpledataentry.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.rx2.await

class DatasetsRepositoryImpl(
    private val sessionManager: SessionManager,
    private val databaseProvider: DatabaseProvider,
    private val context: android.content.Context,
    private val datasetInstancesRepository: com.ash.simpledataentry.domain.repository.DatasetInstancesRepository,
    private val d2Provider: D2Provider
) : DatasetsRepository {

    private val TAG = "DatasetsRepositoryImpl"
    /**
     * Use d2Provider.awaitD2() in suspend functions for safe, non-racing access.
     */
    private val d2: D2? get() = d2Provider.getD2OrNull()

    // Lazy DAO accessors - always get from current account's database
    private val datasetDao get() = databaseProvider.getCurrentDatabase().datasetDao()
    private val trackerProgramDao get() = databaseProvider.getCurrentDatabase().trackerProgramDao()
    private val eventProgramDao get() = databaseProvider.getCurrentDatabase().eventProgramDao()
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /**
     * Fetches all datasets from Room database.
     * Account isolation ensures each account sees only their own data.
     * Uses batch count to avoid N+1 query problem (was making 1 SDK call per dataset).
     */
    override fun getDatasets(): Flow<List<Dataset>> {
        return datasetDao.getAll()
            .map { entities ->
                withContext(Dispatchers.IO) {
                    // Batch fetch all instance counts in ONE query instead of N queries
                    val datasetIds = entities.map { it.id }
                    val counts = datasetInstancesRepository.getDatasetInstanceCounts(datasetIds)

                    entities.map { entity ->
                        entity.toDomainModel().copy(instanceCount = counts[entity.id] ?: 0)
                    }
                }
            }
    }

    override suspend fun syncDatasets(): Result<Unit> {
        val d2Instance = d2 ?: run {
            Log.w(TAG, "D2 session not available for syncDatasets")
            return Result.failure(Exception("D2 session not available"))
        }
        return try {
            Log.d(TAG, "Syncing dataset DATA (metadata already downloaded during login)")
            // PHASE 3 FIX: Don't retrigger full metadata download - just sync data
            // Metadata is already downloaded by SessionManager during login
            // This method should only download AGGREGATE DATA, not metadata
            withContext(Dispatchers.IO) {
                d2Instance.aggregatedModule().data().blockingDownload()
            }
            Log.d(TAG, "Dataset data sync completed successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Dataset data sync failed", e)
            Result.failure(e)
        }
    }

    override suspend fun filterDatasets(period: String?, syncStatus: Boolean?): Result<List<Dataset>> {
        return try {
            val d2Instance = d2Provider.awaitD2()
            Log.d(TAG, "Filtering datasets - period: $period, syncStatus: $syncStatus")
            val datasets = withContext(Dispatchers.IO) {
                val rawDatasets = d2Instance.dataSetModule()
                    .dataSets()
                    .get().await()

                // Batch fetch all instance counts in ONE query
                val datasetIds = rawDatasets.map { it.uid() }
                val counts = datasetInstancesRepository.getDatasetInstanceCounts(datasetIds)

                rawDatasets.map { dataSet ->
                    val dataset = dataSet.toDomainModel()
                    dataset.copy(instanceCount = counts[dataset.id] ?: 0)
                }
            }

            Log.d(TAG, "Found ${datasets.size} datasets after filtering")
            Result.success(datasets)
        } catch (e: Exception) {
            Log.e(TAG, "Error filtering datasets", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches all tracker programs from Room database.
     * Account isolation ensures each account sees only their own data.
     */
    override fun getTrackerPrograms(): Flow<List<Program>> {
        return trackerProgramDao.getAll().map { entities ->
            withContext(Dispatchers.IO) {
                entities.map { entity ->
                    val program = entity.toDomainModel()
                    val enrollmentCount = datasetInstancesRepository.getProgramInstanceCount(
                        program.id,
                        com.ash.simpledataentry.domain.model.ProgramType.TRACKER
                    )
                    program.copy(enrollmentCount = enrollmentCount)
                }
            }
        }
    }

    /**
     * Fetches all event programs from Room database.
     * Account isolation ensures each account sees only their own data.
     */
    override fun getEventPrograms(): Flow<List<Program>> {
        return eventProgramDao.getAll().map { entities ->
            withContext(Dispatchers.IO) {
                entities.map { entity ->
                    val program = entity.toDomainModel()
                    val eventCount = datasetInstancesRepository.getProgramInstanceCount(
                        program.id,
                        com.ash.simpledataentry.domain.model.ProgramType.EVENT
                    )
                    program.copy(enrollmentCount = eventCount)
                }
            }
        }
    }

    override fun getAllPrograms(): Flow<List<ProgramItem>> = combine(
        getDatasets(),
        getTrackerPrograms(),
        getEventPrograms()
    ) { datasets, trackerPrograms, eventPrograms ->
        val programItems = mutableListOf<ProgramItem>()

        // Add datasets as program items
        programItems.addAll(datasets.map { ProgramItem.DatasetProgram(it) })

        // Add tracker programs
        programItems.addAll(trackerPrograms.map { ProgramItem.TrackerProgram(it) })

        // Add event programs
        programItems.addAll(eventPrograms.map { ProgramItem.EventProgram(it) })

        Log.d(TAG, "Combined programs: ${datasets.size} datasets, ${trackerPrograms.size} tracker, ${eventPrograms.size} event")
        programItems
    }

    override suspend fun syncPrograms(): Result<Unit> {
        val d2Instance = d2 ?: run {
            Log.w(TAG, "D2 session not available for syncPrograms")
            return Result.failure(Exception("D2 session not available"))
        }
        return try {
            Log.d(TAG, "Syncing program DATA (metadata already downloaded during login)")
            // PHASE 3 FIX: Don't retrigger full metadata download - just sync tracker/event data
            // Metadata is already downloaded by SessionManager during login
            // This method should only download TRACKER/EVENT DATA, not metadata
            withContext(Dispatchers.IO) {
                d2Instance.trackedEntityModule().trackedEntityInstanceDownloader()
                    .limit(500) // Reasonable limit to prevent timeouts
                    .blockingDownload()
            }
            Log.d(TAG, "Program data sync completed successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Program data sync failed", e)
            Result.failure(e)
        }
    }

    override suspend fun filterPrograms(
        programType: ProgramType?,
        searchQuery: String?
    ): Result<List<ProgramItem>> {
        return try {
            val allPrograms = mutableListOf<ProgramItem>()

            // Get filtered programs based on type
            when (programType) {
                ProgramType.DATASET -> {
                    val datasets = if (searchQuery.isNullOrBlank()) {
                        getDatasets()
                    } else {
                        flow {
                            getDatasets().collect { datasets ->
                                emit(datasets.filter {
                                    it.name.contains(searchQuery, ignoreCase = true) ||
                                    it.description?.contains(searchQuery, ignoreCase = true) == true
                                })
                            }
                        }
                    }
                    datasets.collect { allPrograms.addAll(it.map { ProgramItem.DatasetProgram(it) }) }
                }
                ProgramType.TRACKER -> {
                    getTrackerPrograms().collect { programs ->
                        val filtered = if (searchQuery.isNullOrBlank()) {
                            programs
                        } else {
                            programs.filter {
                                it.name.contains(searchQuery, ignoreCase = true) ||
                                it.description?.contains(searchQuery, ignoreCase = true) == true
                            }
                        }
                        allPrograms.addAll(filtered.map { ProgramItem.TrackerProgram(it) })
                    }
                }
                ProgramType.EVENT -> {
                    getEventPrograms().collect { programs ->
                        val filtered = if (searchQuery.isNullOrBlank()) {
                            programs
                        } else {
                            programs.filter {
                                it.name.contains(searchQuery, ignoreCase = true) ||
                                it.description?.contains(searchQuery, ignoreCase = true) == true
                            }
                        }
                        allPrograms.addAll(filtered.map { ProgramItem.EventProgram(it) })
                    }
                }
                ProgramType.ALL, null -> {
                    getAllPrograms().collect { programs ->
                        val filtered = if (searchQuery.isNullOrBlank()) {
                            programs
                        } else {
                            programs.filter {
                                it.name.contains(searchQuery, ignoreCase = true) ||
                                it.description?.contains(searchQuery, ignoreCase = true) == true
                            }
                        }
                        allPrograms.addAll(filtered)
                    }
                }
            }

            Result.success(allPrograms)
        } catch (e: Exception) {
            Log.e(TAG, "Error filtering programs", e)
            Result.failure(e)
        }
    }

}

/**
 * Extension function converting the DHIS2 DataSet object into our domain Dataset model.
 */
fun DataSet.toDomainModel(): Dataset {
    val datasetStyle = style()?.let { sdkStyle ->
        Log.d("DatasetsRepo", "Dataset ${uid()}: Found style - icon: ${sdkStyle.icon()}, color: ${sdkStyle.color()}")
        com.ash.simpledataentry.domain.model.DatasetStyle(
            icon = sdkStyle.icon(),
            color = sdkStyle.color()
        )
    } ?: run {
        Log.d("DatasetsRepo", "Dataset ${uid()}: No style found")
        null
    }

    return Dataset(
        id = uid(),
        name = displayName() ?: name() ?: "Unnamed Dataset",
        description = description() ?: "",
        periodType = when (periodType()?.name) {
            "Daily" -> PeriodType.Daily
            "Weekly" -> PeriodType.Weekly
            "Monthly" -> PeriodType.Monthly
            "Yearly" -> PeriodType.Yearly
            else -> PeriodType.Monthly // Default fallback, adjust as needed
        },
        style = datasetStyle
    )
}

// Extension functions for mapping
fun Dataset.toEntity(): com.ash.simpledataentry.data.local.DatasetEntity =
    com.ash.simpledataentry.data.local.DatasetEntity(
        id = id,
        name = name,
        description = description.toString(),
        periodType = periodType?.name ?: "Monthly",
        styleIcon = style?.icon,
        styleColor = style?.color
    )

fun com.ash.simpledataentry.data.local.DatasetEntity.toDomainModel(): Dataset =
    Dataset(
        id = id,
        name = name,
        description = description,
        periodType = when (periodType) {
            "Daily" -> PeriodType.Daily
            "Weekly" -> PeriodType.Weekly
            "Monthly" -> PeriodType.Monthly
            "Yearly" -> PeriodType.Yearly
            else -> PeriodType.Monthly
        },
        style = if (styleIcon != null || styleColor != null) {
            com.ash.simpledataentry.domain.model.DatasetStyle(
                icon = styleIcon,
                color = styleColor
            )
        } else null
    )


// Extension function to convert DHIS2 SDK Program to domain model
private fun org.hisp.dhis.android.core.program.Program.toDomainModel(): Program {
    val programStyle = style()?.let { sdkStyle ->
        ProgramStyle(
            icon = sdkStyle.icon(),
            color = sdkStyle.color()
        )
    }

    return Program(
        id = uid(),
        name = displayName() ?: name() ?: "Unnamed Program",
        description = description(),
        programType = when (programType()) {
            SdkProgramType.WITH_REGISTRATION -> ProgramType.TRACKER
            SdkProgramType.WITHOUT_REGISTRATION -> ProgramType.EVENT
            else -> ProgramType.EVENT
        },
        trackedEntityType = trackedEntityType()?.uid(),
        categoryCombo = categoryCombo()?.uid(),
        style = programStyle,
        enrollmentDateLabel = enrollmentDateLabel(),
        incidentDateLabel = incidentDateLabel(),
        displayIncidentDate = displayIncidentDate() ?: false,
        onlyEnrollOnce = onlyEnrollOnce() ?: false,
        selectEnrollmentDatesInFuture = selectEnrollmentDatesInFuture() ?: false,
        selectIncidentDatesInFuture = selectIncidentDatesInFuture() ?: false,
        featureType = when (featureType()?.name) {
            "POINT" -> FeatureType.POINT
            "POLYGON" -> FeatureType.POLYGON
            "MULTI_POLYGON" -> FeatureType.MULTI_POLYGON
            else -> FeatureType.NONE
        },
        minAttributesRequiredToSearch = minAttributesRequiredToSearch() ?: 1,
        maxTeiCountToReturn = maxTeiCountToReturn() ?: 50,
        // Note: enrollment count would need to be fetched separately
        enrollmentCount = 0
    )
}

// Extension functions for mapping Room entities to domain models
fun com.ash.simpledataentry.data.local.TrackerProgramEntity.toDomainModel(): Program =
    Program(
        id = id,
        name = name,
        description = description,
        programType = ProgramType.TRACKER,
        trackedEntityType = trackedEntityType,
        categoryCombo = categoryCombo,
        style = if (styleIcon != null || styleColor != null) {
            ProgramStyle(icon = styleIcon, color = styleColor)
        } else null,
        enrollmentDateLabel = enrollmentDateLabel,
        incidentDateLabel = incidentDateLabel,
        displayIncidentDate = displayIncidentDate,
        onlyEnrollOnce = onlyEnrollOnce,
        selectEnrollmentDatesInFuture = selectEnrollmentDatesInFuture,
        selectIncidentDatesInFuture = selectIncidentDatesInFuture,
        featureType = when (featureType) {
            "POINT" -> FeatureType.POINT
            "POLYGON" -> FeatureType.POLYGON
            "MULTI_POLYGON" -> FeatureType.MULTI_POLYGON
            else -> FeatureType.NONE
        },
        minAttributesRequiredToSearch = minAttributesRequiredToSearch,
        maxTeiCountToReturn = maxTeiCountToReturn,
        enrollmentCount = 0  // Will be filled separately
    )

fun com.ash.simpledataentry.data.local.EventProgramEntity.toDomainModel(): Program =
    Program(
        id = id,
        name = name,
        description = description,
        programType = ProgramType.EVENT,
        categoryCombo = categoryCombo,
        style = if (styleIcon != null || styleColor != null) {
            ProgramStyle(icon = styleIcon, color = styleColor)
        } else null,
        featureType = when (featureType) {
            "POINT" -> FeatureType.POINT
            "POLYGON" -> FeatureType.POLYGON
            "MULTI_POLYGON" -> FeatureType.MULTI_POLYGON
            else -> FeatureType.NONE
        },
        enrollmentCount = 0  // Will be filled separately
    )
