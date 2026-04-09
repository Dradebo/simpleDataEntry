package com.ash.simpledataentry.domain.repository

import com.ash.simpledataentry.domain.model.DatasetInstance
import com.ash.simpledataentry.domain.model.ProgramInstance
import com.ash.simpledataentry.domain.model.ProgramType
import kotlinx.coroutines.flow.Flow

interface DatasetInstancesRepository {
    // Existing dataset methods
    suspend fun getDatasetInstances(datasetId: String): List<DatasetInstance>
    suspend fun getDatasetInstanceCount(datasetId: String, orgUnitIds: Set<String>? = null): Int
    /**
     * Batch fetch instance counts for multiple datasets in a single query.
     * This avoids the N+1 query problem when loading the dataset list.
     */
    suspend fun getDatasetInstanceCounts(datasetIds: List<String>): Map<String, Int>
    suspend fun syncDatasetInstances()
    suspend fun completeDatasetInstance(datasetId: String, period: String, orgUnit: String, attributeOptionCombo: String): Result<Unit>
    suspend fun markDatasetInstanceIncomplete(datasetId: String, period: String, orgUnit: String, attributeOptionCombo: String): Result<Unit>

    // New unified program instance methods
    suspend fun getProgramInstances(programId: String, programType: ProgramType): Flow<List<ProgramInstance>>
    suspend fun getProgramInstanceCount(programId: String, programType: ProgramType): Int
    suspend fun syncProgramInstances(programId: String, programType: ProgramType): Result<Unit>

    // Tracker-specific methods
    suspend fun getTrackerEnrollments(programId: String): Flow<List<ProgramInstance.TrackerEnrollment>>
    suspend fun createTrackerEnrollment(programId: String, trackedEntityId: String, orgUnitId: String): Result<String>
    suspend fun completeEnrollment(enrollmentId: String): Result<Unit>
    suspend fun cancelEnrollment(enrollmentId: String): Result<Unit>

    // Event-specific methods
    suspend fun getEventInstances(programId: String): Flow<List<ProgramInstance.EventInstance>>
    suspend fun createEventInstance(programId: String, programStageId: String, orgUnitId: String): Result<String>
    suspend fun completeEvent(eventId: String): Result<Unit>
    suspend fun skipEvent(eventId: String): Result<Unit>
}
