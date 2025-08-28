package com.ash.simpledataentry.data.repositoryImpl

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.runtime.snapshots.SnapshotApplyResult.Failure
import com.ash.simpledataentry.domain.model.*
import com.ash.simpledataentry.domain.repository.DataEntryRepository
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.ash.simpledataentry.data.SessionManager
import org.hisp.dhis.android.core.D2
import com.ash.simpledataentry.data.local.DataValueDraftDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ash.simpledataentry.data.local.DataElementDao
import com.ash.simpledataentry.data.local.CategoryComboDao
import com.ash.simpledataentry.data.local.CategoryOptionComboDao
import com.ash.simpledataentry.data.local.OrganisationUnitDao
import com.ash.simpledataentry.util.NetworkUtils
import com.ash.simpledataentry.data.local.DataValueEntity
import com.ash.simpledataentry.data.local.DataValueDao
import com.ash.simpledataentry.presentation.datasets.DatasetsState.Success
import com.ash.simpledataentry.data.cache.MetadataCacheService
import com.ash.simpledataentry.data.sync.NetworkStateManager
import com.ash.simpledataentry.data.sync.SyncQueueManager
import javax.inject.Inject
import kotlin.Pair
import kotlin.Result
import org.hisp.dhis.android.core.dataelement.DataElement
import org.hisp.dhis.android.core.common.ValueType
import android.content.Context

@ViewModelScoped
class DataEntryRepositoryImpl @Inject constructor(
    private val sessionManager: SessionManager,
    private val draftDao: DataValueDraftDao,
    private val dataElementDao: DataElementDao,
    private val categoryComboDao: CategoryComboDao,
    private val categoryOptionComboDao: CategoryOptionComboDao,
    private val organisationUnitDao: OrganisationUnitDao,
    private val context: android.content.Context,
    private val dataValueDao: DataValueDao,
    private val metadataCacheService: MetadataCacheService,
    private val networkStateManager: NetworkStateManager,
    private val syncQueueManager: SyncQueueManager
) : DataEntryRepository {

    private val d2 get() = sessionManager.getD2()!!

    private fun getDataEntryType(dataElement: DataElement): DataEntryType {
        return when (dataElement.valueType()) {
            ValueType.TEXT -> DataEntryType.TEXT
            ValueType.LONG_TEXT -> DataEntryType.TEXT
            ValueType.NUMBER -> DataEntryType.NUMBER
            ValueType.INTEGER -> DataEntryType.INTEGER
            ValueType.INTEGER_POSITIVE -> DataEntryType.POSITIVE_INTEGER
            ValueType.INTEGER_NEGATIVE -> DataEntryType.NEGATIVE_INTEGER
            ValueType.INTEGER_ZERO_OR_POSITIVE -> DataEntryType.POSITIVE_INTEGER
            ValueType.PERCENTAGE -> DataEntryType.PERCENTAGE
            ValueType.DATE -> DataEntryType.DATE
            ValueType.BOOLEAN -> DataEntryType.YES_NO
            ValueType.COORDINATE -> DataEntryType.COORDINATES
            //ValueType.OPTION_SET -> DataEntryType.MULTIPLE_CHOICE
            else -> DataEntryType.TEXT
        }
    }

    private fun getValidationRules(dataElement: DataElement): List<ValidationRule> {
        val rules = mutableListOf<ValidationRule>()
        
        // Add value type specific validations
        when (dataElement.valueType()) {
            ValueType.NUMBER,
            ValueType.INTEGER,
            ValueType.INTEGER_POSITIVE,
            ValueType.INTEGER_NEGATIVE,
            ValueType.INTEGER_ZERO_OR_POSITIVE,
            ValueType.PERCENTAGE -> {
                rules.add(ValidationRule(
                    rule = "number",
                    message = "Please enter a valid number",
                    severity = ValidationState.ERROR
                ))
            }
            ValueType.COORDINATE -> {
                rules.add(ValidationRule(
                    rule = "coordinates",
                    message = "Please enter valid coordinates",
                    severity = ValidationState.ERROR
                ))
            }
            else -> {}
        }

        return rules
    }
    
    /**
     * Convert string value type to DataEntryType enum
     */
    private fun getDataEntryTypeFromString(valueTypeString: String): DataEntryType {
        return when (valueTypeString.uppercase()) {
            "TEXT" -> DataEntryType.TEXT
            "LONG_TEXT" -> DataEntryType.TEXT
            "NUMBER" -> DataEntryType.NUMBER
            "INTEGER" -> DataEntryType.INTEGER
            "INTEGER_POSITIVE" -> DataEntryType.POSITIVE_INTEGER
            "INTEGER_NEGATIVE" -> DataEntryType.NEGATIVE_INTEGER
            "INTEGER_ZERO_OR_POSITIVE" -> DataEntryType.POSITIVE_INTEGER
            "PERCENTAGE" -> DataEntryType.PERCENTAGE
            "DATE" -> DataEntryType.DATE
            "BOOLEAN" -> DataEntryType.YES_NO
            "COORDINATE" -> DataEntryType.COORDINATES
            else -> DataEntryType.TEXT
        }
    }
    
    /**
     * Get validation rules from string value type
     */
    private fun getValidationRulesFromString(valueTypeString: String): List<ValidationRule> {
        val rules = mutableListOf<ValidationRule>()
        
        when (valueTypeString.uppercase()) {
            "NUMBER",
            "INTEGER",
            "INTEGER_POSITIVE",
            "INTEGER_NEGATIVE", 
            "INTEGER_ZERO_OR_POSITIVE",
            "PERCENTAGE" -> {
                rules.add(ValidationRule(
                    rule = "number",
                    message = "Please enter a valid number",
                    severity = ValidationState.ERROR
                ))
            }
            "COORDINATE" -> {
                rules.add(ValidationRule(
                    rule = "coordinates", 
                    message = "Please enter valid coordinates",
                    severity = ValidationState.ERROR
                ))
            }
        }
        
        return rules
    }

    @SuppressLint("SuspiciousIndentation")
    override suspend fun getDataValues(
        datasetId: String,
        period: String,
        orgUnit: String,
        attributeOptionCombo: String
    ): Flow<List<DataValue>> = flow {
        Log.d("DataEntryRepositoryImpl", "Starting optimized getDataValues for dataset: $datasetId")
        
        // PERFORMANCE OPTIMIZATION: Always use cached data for faster loading
        // Only fall back to online mode if cache is missing or explicitly requested
        val preferCachedData = true // Always prefer cached data for performance
        val isOffline = !NetworkUtils.isNetworkAvailable(context)
        
        // Check if we have basic metadata in Room (hydrated during login)
        val hasBasicMetadata = withContext(Dispatchers.IO) {
            dataElementDao.getAll().isNotEmpty() && 
            categoryComboDao.getAll().isNotEmpty() && 
            categoryOptionComboDao.getAll().isNotEmpty() && 
            organisationUnitDao.getAll().isNotEmpty()
        }
        
        if (!hasBasicMetadata) {
            if (isOffline) {
                emit(emptyList())
                return@flow
            }
            // This shouldn't happen if login properly hydrated Room, but fallback to original logic
            Log.w("DataEntryRepositoryImpl", "Basic metadata missing from Room, this indicates login hydration failed")
            emit(emptyList())
            return@flow
        }
        
        if (preferCachedData || isOffline) {
            // PERFORMANCE: Always use cached data first for fast loading
            Log.d("DataEntryRepositoryImpl", if (isOffline) "Using offline mode with cached metadata" else "Using cached data for performance (online)")
            
            val drafts = withContext(Dispatchers.IO) {
                draftDao.getDraftsForInstance(datasetId, period, orgUnit, attributeOptionCombo)
            }
            val draftMap = drafts.associateBy { it.dataElement to it.categoryOptionCombo }
            
            // Use optimized cache service for offline mode
            val optimizedData = metadataCacheService.getOptimizedDataForEntry(datasetId, period, orgUnit, attributeOptionCombo)
            
            val merged = optimizedData.sections.flatMap { section ->
                section.dataElementUids.flatMap { deUid ->
                    val dataElement = optimizedData.dataElements[deUid]
                    val comboId = dataElement?.categoryComboId ?: ""
                    val combos = optimizedData.categoryOptionCombos.values.filter { it.categoryComboId == comboId }
                    
                    // Get display name with proper fallback hierarchy
                    val dataElementName = getDataElementDisplayName(deUid) ?: dataElement?.name ?: deUid
                    
                    if (combos.isEmpty()) {
                        val draft = draftMap[deUid to ""]
                        listOf(DataValue(
                            dataElement = deUid,
                            dataElementName = dataElementName,
                            sectionName = section.name,
                            categoryOptionCombo = "",
                            categoryOptionComboName = "Default",
                            value = draft?.value,
                            comment = draft?.comment,
                            storedBy = null,
                            validationState = ValidationState.VALID,
                            dataEntryType = DataEntryType.TEXT,
                            lastModified = draft?.lastModified ?: System.currentTimeMillis(),
                            validationRules = emptyList()
                        ))
                    } else {
                        combos.map { coc ->
                            val draft = draftMap[deUid to coc.id]
                            DataValue(
                                dataElement = deUid,
                                dataElementName = dataElementName,
                                sectionName = section.name,
                                categoryOptionCombo = coc.id,
                                categoryOptionComboName = coc.name,
                                value = draft?.value,
                                comment = draft?.comment,
                                storedBy = null,
                                validationState = ValidationState.VALID,
                                dataEntryType = DataEntryType.TEXT,
                                lastModified = draft?.lastModified ?: System.currentTimeMillis(),
                                validationRules = emptyList()
                            )
                        }
                    }
                }
            }
            emit(merged)
            return@flow
        }
        
        // Online mode: Use optimized cache service
        Log.d("DataEntryRepositoryImpl", "Using online mode with optimized cache service")
        
        val optimizedData = metadataCacheService.getOptimizedDataForEntry(datasetId, period, orgUnit, attributeOptionCombo)
        
        Log.d("DataEntryRepositoryImpl", "Optimized data loaded: ${optimizedData.sections.size} sections, ${optimizedData.sdkDataValues.size} data values")
        
        // Build DataValue objects using cached metadata and fresh data values
        val mappedDataValues = optimizedData.sections.flatMap { section ->
            section.dataElementUids.flatMap { deUid ->
                val cachedDataElement = optimizedData.dataElements[deUid]
                
                if (cachedDataElement == null) {
                    Log.w("DataEntryRepositoryImpl", "Data element $deUid not found in cache")
                    emptyList()
                } else {
                    val comboId = cachedDataElement.categoryComboId ?: "default"
                    val allCombos = optimizedData.categoryOptionCombos.values.filter { it.categoryComboId == comboId }
                    
                    if (allCombos.isEmpty()) {
                        // No combos, just default
                        val valueObj = optimizedData.sdkDataValues[deUid to ""]
                        val dataElementName = getDataElementDisplayName(deUid) ?: cachedDataElement.name
                        
                        listOf(DataValue(
                            dataElement = deUid,
                            dataElementName = dataElementName,
                            sectionName = section.name,
                            categoryOptionCombo = "",
                            categoryOptionComboName = "Default",
                            value = valueObj?.value(),
                            comment = valueObj?.comment(),
                            storedBy = valueObj?.storedBy(),
                            validationState = ValidationState.VALID,
                            dataEntryType = getDataEntryTypeFromString(cachedDataElement.valueType),
                            lastModified = valueObj?.lastUpdated()?.time ?: System.currentTimeMillis(),
                            validationRules = getValidationRulesFromString(cachedDataElement.valueType)
                        ))
                    } else {
                        allCombos.map { coc ->
                            val valueObj = optimizedData.sdkDataValues[deUid to coc.id]
                            val dataElementName = getDataElementDisplayName(deUid) ?: cachedDataElement.name
                            
                            DataValue(
                                dataElement = deUid,
                                dataElementName = dataElementName,
                                sectionName = section.name,
                                categoryOptionCombo = coc.id,
                                categoryOptionComboName = coc.name,
                                value = valueObj?.value(),
                                comment = valueObj?.comment(),
                                storedBy = valueObj?.storedBy(),
                                validationState = ValidationState.VALID,
                                dataEntryType = getDataEntryTypeFromString(cachedDataElement.valueType),
                                lastModified = valueObj?.lastUpdated()?.time ?: System.currentTimeMillis(),
                                validationRules = getValidationRulesFromString(cachedDataElement.valueType)
                            )
                        }
                    }
                }
            }
        }
        
        Log.d("DataEntryRepositoryImpl", "Mapped ${mappedDataValues.size} data values")
        
        // Save to Room for caching
        val valueEntities = mappedDataValues.map { dataValue ->
            DataValueEntity(
                datasetId = datasetId,
                period = period,
                orgUnit = orgUnit,
                attributeOptionCombo = attributeOptionCombo,
                dataElement = dataValue.dataElement,
                categoryOptionCombo = dataValue.categoryOptionCombo,
                value = dataValue.value,
                comment = dataValue.comment,
                lastModified = dataValue.lastModified
            )
        }
        dataValueDao.insertAll(valueEntities)
        
        emit(mappedDataValues)
    }

    override suspend fun saveDataValue(
        datasetId: String,
        period: String,
        orgUnit: String,
        attributeOptionCombo: String,
        dataElement: String,
        categoryOptionCombo: String,
        value: String?,
        comment: String?
    ): Result<DataValue> {
        return try {
            val dataValueObjectRepository = d2.dataValueModule().dataValues()
                .value(period, orgUnit, dataElement, categoryOptionCombo, attributeOptionCombo)
            
            if (value != null) {
                try {
                    dataValueObjectRepository.blockingSet(value)
//                    if (comment != null) {
//                        dataValueObjectRepository.blockingSetComment(comment)
//                    }
                    Log.d("DataEntryRepositoryImpl", "Staged value for upload: $value, comment: $comment")
                } catch (e: Exception) {
                    Log.e("DataEntryRepositoryImpl", "Error staging value for upload", e)
                    throw e
                }
            } else {
                dataValueObjectRepository.blockingDeleteIfExist()
            }

            val dataElementObj = d2.dataElementModule().dataElements()
                .uid(dataElement)
                .blockingGet() ?: throw Exception("Data element not found")

            val dataSet = d2.dataSetModule().dataSets()
                .withDataSetElements()
                .uid(datasetId)
                .blockingGet() ?: throw Exception("Dataset not found")

            // Get form name from data set element, fall back to display name, then short name, then uid
            val dataElementName = dataElementObj.formName()
                ?:dataElementObj.shortName()
                ?: dataElementObj.displayName()
                ?: dataElement

            val sectionName = d2.dataSetModule().sections()
                .byDataSetUid().eq(datasetId)
                .blockingGet()
                .find { section ->
                    section.dataElements()?.any { it.uid() == dataElement } == true
                }
                ?.displayName() ?: "Default Section"

            val categoryOptionComboName = if (categoryOptionCombo.isNotEmpty()) {
                d2.categoryModule().categoryOptionCombos()
                    .uid(categoryOptionCombo)
                    .blockingGet()
                    ?.displayName() ?: categoryOptionCombo
            } else {
                "Default"
            }

            // Get value history
            val valueHistory = d2.dataValueModule().dataValues()
                .byDataElementUid().eq(dataElement)
                .byPeriod().eq(period)
                .byOrganisationUnitUid().eq(orgUnit)
                .byCategoryOptionComboUid().eq(categoryOptionCombo)
                .byAttributeOptionComboUid().eq(attributeOptionCombo)
                .blockingGet()
                .map { value ->
                    ValueHistory(
                        value = value.value() ?: "",
                        timestamp = value.lastUpdated()?.time ?: System.currentTimeMillis(),
                        storedBy = value.storedBy() ?: "Unknown",
                        comment = value.comment()
                    )
                }

            Result.success(
                DataValue(
                    dataElement = dataElement,
                    dataElementName = dataElementName.toString(),
                    sectionName = sectionName,
                    categoryOptionCombo = categoryOptionCombo,
                    categoryOptionComboName = categoryOptionComboName,
                    value = value,
                    comment = comment,
                    storedBy = d2.userModule().user().blockingGet()?.username() ?: "Unknown",
                    validationState = ValidationState.VALID,
                    dataEntryType = getDataEntryType(dataElementObj),
                    lastModified = System.currentTimeMillis(),
                    valueHistory = valueHistory,
                    validationRules = getValidationRules(dataElementObj)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun validateValue(
        datasetId: String,
        dataElement: String,
        value: String
    ): DataValueValidationResult {
        return try {
            val dataElementObj = d2.dataElementModule().dataElements()
                .uid(dataElement)
                .blockingGet() ?: return DataValueValidationResult(false, ValidationState.ERROR, "Unknown data element type")

            // Check if value is required
            if (value.isBlank() && dataElementObj.optionSet() == null && 
                dataElementObj.valueType() != org.hisp.dhis.android.core.common.ValueType.BOOLEAN) {
                return DataValueValidationResult(false, ValidationState.ERROR, "This field is required")
            }

            // Explicit validation for each value type
            val valueType = dataElementObj.valueType()
            return when (valueType) {
                org.hisp.dhis.android.core.common.ValueType.NUMBER,
                org.hisp.dhis.android.core.common.ValueType.INTEGER,
                org.hisp.dhis.android.core.common.ValueType.INTEGER_POSITIVE,
                org.hisp.dhis.android.core.common.ValueType.INTEGER_NEGATIVE,
                org.hisp.dhis.android.core.common.ValueType.INTEGER_ZERO_OR_POSITIVE,
                org.hisp.dhis.android.core.common.ValueType.PERCENTAGE -> {
                    if (value.toDoubleOrNull() == null) {
                        DataValueValidationResult(false, ValidationState.ERROR, "Please enter a valid number")
                    } else {
                        DataValueValidationResult(true, ValidationState.VALID, null)
                    }
                }
                org.hisp.dhis.android.core.common.ValueType.BOOLEAN -> {
                    if (value != "true" && value != "false") {
                        DataValueValidationResult(false, ValidationState.ERROR, "Please enter true or false")
                    } else {
                        DataValueValidationResult(true, ValidationState.VALID, null)
                    }
                }
                else -> DataValueValidationResult(true, ValidationState.VALID, null)
            }
        } catch (e: Exception) {
            DataValueValidationResult(false, ValidationState.ERROR, e.message)
        }
    }

    override suspend fun getAvailablePeriods(datasetId: String): List<Period> {
        return d2.periodModule().periodHelper().getPeriodsForDataSet(datasetId).blockingGet().map {
            Period(id = it.periodId().toString())
        }
    }

    override suspend fun getUserOrgUnit(datasetId: String): OrganisationUnit {
        val orgUnits = d2.organisationUnitModule().organisationUnits()
            .byOrganisationUnitScope(org.hisp.dhis.android.core.organisationunit.OrganisationUnit.Scope.SCOPE_DATA_CAPTURE)
            .blockingGet()
        
        if (orgUnits.isEmpty()) {
            throw Exception("No organization units available for data capture")
        }
        
        return OrganisationUnit(
            id = orgUnits.first().uid(),
            name = orgUnits.first().displayName() ?: orgUnits.first().uid()
        )
    }

    override suspend fun getUserOrgUnits(datasetId: String): List<OrganisationUnit> {
        val orgUnits = d2.organisationUnitModule().organisationUnits()
            .byOrganisationUnitScope(org.hisp.dhis.android.core.organisationunit.OrganisationUnit.Scope.SCOPE_DATA_CAPTURE)
            .blockingGet()
        
        return orgUnits.map { orgUnit ->
            OrganisationUnit(
                id = orgUnit.uid(),
                name = orgUnit.displayName() ?: orgUnit.uid()
            )
        }
    }

    override suspend fun getDefaultAttributeOptionCombo(): String {
        return d2.categoryModule().categoryOptionCombos()
            .byCategoryComboUid().eq("default")
            .blockingGet()
            .firstOrNull()?.uid() ?: "default"
    }

    override suspend fun getAttributeOptionCombos(datasetId: String): List<Pair<String, String>> {
        val dataSet = d2.dataSetModule().dataSets()
            .uid(datasetId)
            .blockingGet() ?: return emptyList()
        val categoryComboUid = dataSet.categoryCombo()?.uid() ?: return emptyList()
        return d2.categoryModule().categoryOptionCombos()
            .byCategoryComboUid().eq(categoryComboUid)
            .blockingGet()
            .map { it.uid() to (it.displayName() ?: it.uid()) }
    }

    override suspend fun getCategoryComboStructure(categoryComboUid: String): List<Pair<String, List<Pair<String, String>>>> {
        return metadataCacheService.getCategoryComboStructure(categoryComboUid)
    }

    override suspend fun getCategoryOptionCombos(categoryComboUid: String): List<Pair<String, List<String>>> {
        return metadataCacheService.getCategoryOptionCombos(categoryComboUid)
    }

    override suspend fun pushAllLocalData() {
        if (!networkStateManager.isOnline()) {
            Log.i("DataEntryRepositoryImpl", "Offline - queuing data for sync when connection available")
            syncQueueManager.queueForSync()
            return
        }
        
        val syncResult = syncQueueManager.startSync()
        if (syncResult.isFailure) {
            Log.e("DataEntryRepositoryImpl", "Sync failed: ${syncResult.exceptionOrNull()?.message}")
            throw syncResult.exceptionOrNull() ?: Exception("Sync failed")
        }
    }

    override suspend fun syncCurrentEntryForm() {
        withContext(Dispatchers.IO) {
            if (!networkStateManager.isOnline()) {
                Log.i("DataEntryRepositoryImpl", "Offline - queuing current form for sync when connection available")
                syncQueueManager.queueForSync()
                return@withContext
            }
            
            try {
                val syncResult = syncQueueManager.startSync()
                if (syncResult.isFailure) {
                    throw syncResult.exceptionOrNull() ?: Exception("Sync failed")
                }
                
                // Download aggregated data after successful sync
                d2.aggregatedModule().data().blockingDownload()
                Log.d("DataEntryRepositoryImpl", "Current entry form synced successfully")
            } catch (e: Exception) {
                Log.e("DataEntryRepositoryImpl", "Error syncing current entry form", e)
                throw e
            }
        }
    }

    /**
     * Get display name for data element with proper fallback hierarchy.
     * Uses DHIS2 SDK to fetch: formName > shortName > displayName > technical name
     */
    private suspend fun getDataElementDisplayName(dataElementUid: String): String? {
        return try {
            val dataElementObj = d2.dataElementModule().dataElements()
                .uid(dataElementUid)
                .blockingGet()
            
            dataElementObj?.formName()
                ?: dataElementObj?.shortName()
                ?: dataElementObj?.displayName()
        } catch (e: Exception) {
            Log.w("DataEntryRepositoryImpl", "Failed to fetch display name for data element $dataElementUid: ${e.message}")
            null
        }
    }
}