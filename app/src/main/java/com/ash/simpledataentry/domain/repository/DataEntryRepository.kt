package com.ash.simpledataentry.domain.repository

import com.ash.simpledataentry.domain.model.DataValue
import com.ash.simpledataentry.domain.model.OrganisationUnit
import com.ash.simpledataentry.domain.model.Period
import com.ash.simpledataentry.domain.model.DataValueValidationResult
import kotlinx.coroutines.flow.Flow

interface DataEntryRepository {
    suspend fun getDataValues(
        datasetId: String,
        period: String,
        orgUnit: String,
        attributeOptionCombo: String
    ): Flow<List<DataValue>>

    suspend fun saveDataValue(
        datasetId: String,
        period: String,
        orgUnit: String,
        attributeOptionCombo: String,
        dataElement: String,
        categoryOptionCombo: String,
        value: String?,
        comment: String?
    ): Result<DataValue>

    suspend fun validateValue(
        datasetId: String,
        dataElement: String,
        value: String
    ): DataValueValidationResult

    suspend fun getAvailablePeriods(datasetId: String, limit: Int = 5, showAll: Boolean = false): List<Period>
    suspend fun getUserOrgUnit(datasetId: String): OrganisationUnit
    suspend fun getUserOrgUnits(datasetId: String): List<OrganisationUnit>
    suspend fun getScopedOrgUnits(): List<OrganisationUnit>
    suspend fun getOrgUnitsAttachedToDataSets(datasetIds: List<String>): Set<String>
    suspend fun isDatasetAttachedToScopedOrgUnits(datasetId: String): Boolean
    suspend fun expandOrgUnitSelection(targetId: String, orgUnitId: String): Set<String>
    suspend fun getDefaultAttributeOptionCombo(): String
    suspend fun getAttributeOptionCombos(datasetId: String): List<Pair<String, String>>
    suspend fun getCategoryComboStructure(categoryComboUid: String): List<Pair<String, List<Pair<String, String>>>>
    suspend fun getCategoryOptionCombos(categoryComboUid: String): List<Pair<String, List<String>>>

    // Push all local drafts/unsynced data to the server
    suspend fun pushAllLocalData()

    // Push data for specific dataset instance only
    suspend fun pushDataForInstance(
        datasetId: String,
        period: String,
        orgUnit: String,
        attributeOptionCombo: String
    )

    suspend fun syncCurrentEntryForm()

    // Option set support for data entry
    suspend fun getOptionSetForDataElement(dataElementId: String): com.ash.simpledataentry.domain.model.OptionSet?
    suspend fun getAllOptionSetsForDataset(datasetId: String): Map<String, com.ash.simpledataentry.domain.model.OptionSet>

    // Validation rules for intelligent grouping
    suspend fun getValidationRulesForDataset(datasetId: String): List<org.hisp.dhis.android.core.validation.ValidationRule>
}
