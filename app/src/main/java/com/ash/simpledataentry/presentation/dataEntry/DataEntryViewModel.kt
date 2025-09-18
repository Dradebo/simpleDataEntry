package com.ash.simpledataentry.presentation.dataEntry

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ash.simpledataentry.data.local.DataValueDraftDao
import com.ash.simpledataentry.data.local.DataValueDraftEntity
import com.ash.simpledataentry.data.repositoryImpl.ValidationRepository
import com.ash.simpledataentry.domain.model.*
import com.ash.simpledataentry.domain.repository.DataEntryRepository
import com.ash.simpledataentry.domain.useCase.DataEntryUseCases
import com.ash.simpledataentry.util.NetworkUtils
import com.ash.simpledataentry.data.sync.SyncQueueManager
import com.ash.simpledataentry.data.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DataEntryUiState(
    val datasetId: String = "",
    val datasetName: String = "",
    val period: String = "",
    val orgUnit: String = "",
    val attributeOptionCombo: String = "",
    val attributeOptionComboName: String = "",
    val isEditMode: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val dataValues: List<DataValue> = emptyList(),
    val currentDataValue: DataValue? = null,
    val currentStep: Int = 0,
    val isCompleted: Boolean = false,
    val validationState: ValidationState = ValidationState.VALID,
    val validationMessage: String? = null,
    val expandedSection: String? = null,
    val expandedCategoryGroup: String? = null,
    val categoryComboStructures: Map<String, List<Pair<String, List<Pair<String, String>>>>> = emptyMap(),
    val optionUidsToComboUid: Map<String, Map<Set<String>, String>> = emptyMap(),
    val isNavigating: Boolean = false,
    val saveInProgress: Boolean = false,
    val saveResult: Result<Unit>? = null,
    val attributeOptionCombos: List<Pair<String, String>> = emptyList(),
    val expandedGridRows: Map<String, Set<String>> = emptyMap(),
    val isExpandedSections: Map<String, Boolean> = emptyMap(),
    val currentSectionIndex: Int = -1,
    val totalSections: Int = 0,
    val dataElementGroupedSections: Map<String, Map<String, List<DataValue>>> = emptyMap(),
    val localDraftCount: Int = 0,
    val successMessage: String? = null,
    val isValidating: Boolean = false,
    val validationSummary: ValidationSummary? = null
)

data class CombinedDataEntryState(
    val uiState: DataEntryUiState,
    val syncState: SyncState
)

@HiltViewModel
class DataEntryViewModel @Inject constructor(
    private val application: Application,
    private val repository: DataEntryRepository,
    private val useCases: DataEntryUseCases,
    private val draftDao: DataValueDraftDao,
    private val validationRepository: ValidationRepository,
    private val syncQueueManager: SyncQueueManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(DataEntryUiState())

    val combinedState: StateFlow<CombinedDataEntryState> = _uiState
        .combine(syncQueueManager.syncState) { ui, sync ->
            CombinedDataEntryState(ui, sync)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CombinedDataEntryState(DataEntryUiState(), SyncState())
        )

    private val dirtyDataValues = mutableMapOf<Pair<String, String>, DataValue>()

    private val _fieldStates = mutableStateMapOf<String, androidx.compose.ui.text.input.TextFieldValue>()
    val fieldStates: Map<String, androidx.compose.ui.text.input.TextFieldValue> get() = _fieldStates
    private fun fieldKey(dataElement: String, categoryOptionCombo: String): String = "$dataElement|$categoryOptionCombo"
    fun initializeFieldState(dataValue: DataValue) {
        val key = fieldKey(dataValue.dataElement, dataValue.categoryOptionCombo)
        if (!_fieldStates.containsKey(key)) {
            _fieldStates[key] = androidx.compose.ui.text.input.TextFieldValue(dataValue.value ?: "")
        }
    }
    fun onFieldValueChange(newValue: androidx.compose.ui.text.input.TextFieldValue, dataValue: DataValue) {
        val key = fieldKey(dataValue.dataElement, dataValue.categoryOptionCombo)
        _fieldStates[key] = newValue
        updateCurrentValue(newValue.text, dataValue.dataElement, dataValue.categoryOptionCombo)
    }

    private var savePressed = false

    fun loadDataValues(
        datasetId: String,
        datasetName: String,
        period: String,
        orgUnitId: String,
        attributeOptionCombo: String,
        isEditMode: Boolean
    ) {
        viewModelScope.launch {
            try {
                _uiState.update { currentState ->
                    currentState.copy(
                        isLoading = true,
                        error = null,
                        datasetId = datasetId,
                        datasetName = datasetName,
                        period = period,
                        orgUnit = orgUnitId,
                        attributeOptionCombo = attributeOptionCombo,
                        isEditMode = isEditMode
                    )
                }

                val drafts = withContext(Dispatchers.IO) {
                    draftDao.getDraftsForInstance(datasetId, period, orgUnitId, attributeOptionCombo)
                }
                val draftMap = drafts.associateBy { it.dataElement to it.categoryOptionCombo }

                val attributeOptionComboDeferred = async {
                    repository.getAttributeOptionCombos(datasetId)
                }

                val dataValuesFlow = repository.getDataValues(datasetId, period, orgUnitId, attributeOptionCombo)
                dataValuesFlow.collect { values ->
                    val uniqueCategoryCombos = values
                        .mapNotNull { it.categoryOptionCombo }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .toSet()

                    val categoryComboStructures = mutableMapOf<String, List<Pair<String, List<Pair<String, String>>>>>()
                    val optionUidsToComboUid = mutableMapOf<String, Map<Set<String>, String>>()

                    uniqueCategoryCombos.map { comboUid ->
                        async {
                            if (!categoryComboStructures.containsKey(comboUid)) {
                                val structure = repository.getCategoryComboStructure(comboUid)
                                categoryComboStructures[comboUid] = structure

                                val combos = repository.getCategoryOptionCombos(comboUid)
                                val map = combos.associate { coc ->
                                    val optionUids = coc.second.toSet()
                                    optionUids to coc.first
                                }
                                optionUidsToComboUid[comboUid] = map
                            }
                        }
                    }.awaitAll()

                    val attributeOptionCombos = attributeOptionComboDeferred.await()
                    val attributeOptionComboName = attributeOptionCombos.find { it.first == attributeOptionCombo }?.second ?: attributeOptionCombo

                    val mergedValues = values.map { fetched ->
                        val key = fetched.dataElement to fetched.categoryOptionCombo
                        draftMap[key]?.let { draft ->
                            fetched.copy(
                                value = draft.value,
                                comment = draft.comment,
                                lastModified = draft.lastModified
                            )
                        } ?: fetched
                    }

                    dirtyDataValues.clear()
                    savePressed = false
                    draftMap.forEach { (key, draft) ->
                        mergedValues.find { it.dataElement == key.first && it.categoryOptionCombo == key.second }?.let { merged ->
                            dirtyDataValues[key] = merged
                        }
                    }

                    _uiState.update { currentState ->

                        val groupedBySection = mergedValues.groupBy { it.sectionName }
                        val dataElementGroupedSections = groupedBySection.mapValues { (_, sectionValues) ->
                            sectionValues.groupBy { it.dataElement }
                        }
                        val totalSections = groupedBySection.size

                        val initialOrPreservedIndex = if (totalSections > 0) {
                            if (currentState.currentSectionIndex >= 0 && currentState.currentSectionIndex < totalSections) {
                                currentState.currentSectionIndex
                            } else if (currentState.currentSectionIndex == -1 && currentState.dataValues.isEmpty()) {
                                0
                            }
                            else {
                                currentState.currentSectionIndex
                            }
                        } else {
                            -1
                        }.let {
                            if (it >= totalSections && totalSections > 0) totalSections -1
                            else if (it < -1) -1
                            else it
                        }

                        currentState.copy(
                            dataValues = mergedValues,
                            totalSections = totalSections,
                            currentSectionIndex = initialOrPreservedIndex,
                            currentDataValue = mergedValues.firstOrNull(),
                            currentStep = 0,
                            isLoading = false,
                            expandedSection = null,
                            categoryComboStructures = categoryComboStructures,
                            optionUidsToComboUid = optionUidsToComboUid,
                            attributeOptionComboName = attributeOptionComboName,
                            attributeOptionCombos = attributeOptionCombos,
                            dataElementGroupedSections = dataElementGroupedSections
                        )
                    }
                }
                
                loadDraftCount()
                
            } catch (e: Exception) {
                Log.e("DataEntryViewModel", "Failed to load data values", e)
                _uiState.update { currentState ->
                    currentState.copy(
                        error = "Failed to load data values: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun updateCurrentValue(value: String, dataElementUid: String, categoryOptionComboUid: String) {
        val key = dataElementUid to categoryOptionComboUid
        val dataValueToUpdate = _uiState.value.dataValues.find {
            it.dataElement == dataElementUid && it.categoryOptionCombo == categoryOptionComboUid
        }
        if (dataValueToUpdate != null) {
            val updatedValueObject = dataValueToUpdate.copy(value = value)
            dirtyDataValues[key] = updatedValueObject
            
            if (savePressed) {
                savePressed = false
            }
            _uiState.update { currentState ->
                currentState.copy(
                    dataValues = currentState.dataValues.map {
                        if (it.dataElement == dataElementUid && it.categoryOptionCombo == categoryOptionComboUid) updatedValueObject else it
                    },
                    currentDataValue = if (currentState.currentDataValue?.dataElement == dataElementUid && currentState.currentDataValue?.categoryOptionCombo == categoryOptionComboUid) updatedValueObject else currentState.currentDataValue
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                if (value.isNotBlank()) {
                    draftDao.upsertDraft(
                        DataValueDraftEntity(
                            datasetId = _uiState.value.datasetId,
                            period = _uiState.value.period,
                            orgUnit = _uiState.value.orgUnit,
                            attributeOptionCombo = _uiState.value.attributeOptionCombo,
                            dataElement = dataElementUid,
                            categoryOptionCombo = categoryOptionComboUid,
                            value = value,
                            comment = updatedValueObject.comment,
                            lastModified = System.currentTimeMillis()
                        )
                    )
                } else {
                    draftDao.deleteDraft(
                        datasetId = _uiState.value.datasetId,
                        period = _uiState.value.period,
                        orgUnit = _uiState.value.orgUnit,
                        attributeOptionCombo = _uiState.value.attributeOptionCombo,
                        dataElement = dataElementUid,
                        categoryOptionCombo = categoryOptionComboUid
                    )
                }
                
                loadDraftCount()
            }
        }
    }

    fun saveAllDataValues(context: android.content.Context? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(saveInProgress = true, saveResult = null) }
            savePressed = true
            val stateSnapshot = _uiState.value

            try {
                val draftsToSave = dirtyDataValues.values.map { dataValue ->
                    DataValueDraftEntity(
                        datasetId = stateSnapshot.datasetId,
                        period = stateSnapshot.period,
                        orgUnit = stateSnapshot.orgUnit,
                        attributeOptionCombo = stateSnapshot.attributeOptionCombo,
                        dataElement = dataValue.dataElement,
                        categoryOptionCombo = dataValue.categoryOptionCombo,
                        value = dataValue.value,
                        comment = dataValue.comment,
                        lastModified = System.currentTimeMillis()
                    )
                }

                withContext(Dispatchers.IO) {
                    draftDao.upsertAll(draftsToSave)
                }

                dirtyDataValues.clear()

                _uiState.update { it.copy(
                    saveInProgress = false,
                    saveResult = Result.success(Unit)
                ) }

            } catch (e: Exception) {
                _uiState.update { it.copy(
                    saveInProgress = false,
                    saveResult = Result.failure(e)
                ) }
            }
        }
    }

    fun syncData() {
        viewModelScope.launch {
            val stateSnapshot = _uiState.value
            if (stateSnapshot.datasetId.isEmpty()) {
                Log.e("DataEntryViewModel", "Cannot sync: datasetId is empty")
                _uiState.update { it.copy(error = "Cannot sync, dataset information is missing.") }
                return@launch
            }

            if (!NetworkUtils.isNetworkAvailable(application)) {
                _uiState.update { it.copy(error = "No network connection. Please connect and try again.") }
                return@launch
            }

            val result = syncQueueManager.startSyncForInstance(
                datasetId = stateSnapshot.datasetId,
                period = stateSnapshot.period,
                orgUnit = stateSnapshot.orgUnit,
                attributeOptionCombo = stateSnapshot.attributeOptionCombo,
                forceSync = true
            )

            if (result.isSuccess) {
                Log.d("DataEntryViewModel", "Sync for instance completed successfully.")
                _uiState.update { it.copy(successMessage = "Data synced successfully!") }
                loadDataValues(
                    datasetId = stateSnapshot.datasetId,
                    datasetName = stateSnapshot.datasetName,
                    period = stateSnapshot.period,
                    orgUnitId = stateSnapshot.orgUnit,
                    attributeOptionCombo = stateSnapshot.attributeOptionCombo,
                    isEditMode = stateSnapshot.isEditMode
                )
            } else {
                val errorMessage = result.exceptionOrNull()?.message ?: "Unknown error during sync."
                Log.e("DataEntryViewModel", "Sync for instance failed: $errorMessage")
                _uiState.update { it.copy(error = "Sync failed: $errorMessage") }
            }
        }
    }

    private fun loadDraftCount() {
        viewModelScope.launch {
            try {
                val stateSnapshot = _uiState.value
                val draftCount = withContext(Dispatchers.IO) {
                    draftDao.getDraftCountForInstance(
                        stateSnapshot.datasetId,
                        stateSnapshot.period,
                        stateSnapshot.orgUnit,
                        stateSnapshot.attributeOptionCombo
                    )
                }
                _uiState.update { it.copy(localDraftCount = draftCount) }
            } catch (e: Exception) {
                Log.e("DataEntryViewModel", "Failed to load draft count", e)
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }


    fun toggleSection(sectionName: String) {
        _uiState.update { currentState ->
            val current = currentState.isExpandedSections[sectionName] ?: false
            currentState.copy(
                isExpandedSections = currentState.isExpandedSections.toMutableMap().apply {
                    this[sectionName] = !current
                }
            )
        }
    }

    fun setCurrentSectionIndex(index: Int) {
        _uiState.update { currentState ->
            if (index < 0 || index >= currentState.totalSections) {
                return@update currentState
            }
            val newIndex = if (currentState.currentSectionIndex == index) {
                -1
            } else {
                index
            }
            currentState.copy(currentSectionIndex = newIndex)
        }
    }


    fun goToNextSection() {
        _uiState.update { currentState ->
            if (currentState.totalSections == 0) return@update currentState

            val newIndex = if (currentState.currentSectionIndex == -1) {
                0
            } else {
                (currentState.currentSectionIndex + 1).coerceAtMost(currentState.totalSections - 1)
            }
            currentState.copy(currentSectionIndex = newIndex)
        }
    }

    fun goToPreviousSection() {
        _uiState.update { currentState ->
            if (currentState.totalSections == 0) return@update currentState

            val newIndex = if (currentState.currentSectionIndex == -1) {
                currentState.totalSections - 1
            } else {
                (currentState.currentSectionIndex - 1).coerceAtLeast(0)
            }
            currentState.copy(currentSectionIndex = newIndex)
        }
    }


    fun toggleCategoryGroup(sectionName: String, categoryGroup: String) {
        _uiState.update { currentState ->
            val key = "$sectionName:$categoryGroup"
            val newExpanded = if (currentState.expandedCategoryGroup == key) null else key
            currentState.copy(expandedCategoryGroup = newExpanded)
        }
    }

    fun moveToNextStep(): Boolean {
        val currentStep = _uiState.value.currentStep
        val totalSteps = _uiState.value.dataValues.size
        return if (currentStep < totalSteps - 1) {
            _uiState.update { currentState ->
                currentState.copy(
                    currentStep = currentStep + 1,
                    currentDataValue = _uiState.value.dataValues[currentStep + 1]
                )
            }
            false
        } else {
            _uiState.update { currentState ->
                currentState.copy(
                    isEditMode = true,
                    isCompleted = true
                )
            }
            true
        }
    }

    fun moveToPreviousStep(): Boolean {
        val currentStep = _uiState.value.currentStep
        return if (currentStep > 0) {
            _uiState.update { currentState ->
                currentState.copy(
                    currentStep = currentStep - 1,
                    currentDataValue = _uiState.value.dataValues[currentStep - 1]
                )
            }
            true
        } else {
            false
        }
    }

    suspend fun getAvailablePeriods(datasetId: String): List<Period> {
        return repository.getAvailablePeriods(datasetId)
    }

    suspend fun getUserOrgUnit(datasetId: String): OrganisationUnit? {
        return try {
            repository.getUserOrgUnit(datasetId)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getUserOrgUnits(datasetId: String): List<OrganisationUnit> {
        return try {
            repository.getUserOrgUnits(datasetId)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getDefaultAttributeOptionCombo(): String {
        return try {
            repository.getDefaultAttributeOptionCombo()
        } catch (e: Exception) {
            "default"
        }
    }

    suspend fun getAttributeOptionCombos(datasetId: String): List<Pair<String, String>> {
        return repository.getAttributeOptionCombos(datasetId)
    }

    fun setNavigating(isNavigating: Boolean) {
        _uiState.update { it.copy(isNavigating = isNavigating) }
    }

    fun resetSaveFeedback() {
        _uiState.update { it.copy(saveResult = null, saveInProgress = false) }
    }

    fun wasSavePressed(): Boolean = savePressed
    
    fun hasUnsavedChanges(): Boolean = dirtyDataValues.isNotEmpty()

    fun clearDraftsForCurrentInstance() {
        val stateSnapshot = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            draftDao.deleteDraftsForInstance(
                stateSnapshot.datasetId,
                stateSnapshot.period,
                stateSnapshot.orgUnit,
                stateSnapshot.attributeOptionCombo
            )
            dirtyDataValues.clear()
            savePressed = false
            withContext(Dispatchers.Main) {
                loadDataValues(
                    datasetId = stateSnapshot.datasetId,
                    datasetName = stateSnapshot.datasetName,
                    period = stateSnapshot.period,
                    orgUnitId = stateSnapshot.orgUnit,
                    attributeOptionCombo = stateSnapshot.attributeOptionCombo,
                    isEditMode = true
                )
            }
        }
    }

    fun clearCurrentSessionChanges() {
        val currentState = _uiState.value

        dirtyDataValues.clear()

        _fieldStates.clear()
        currentState.dataValues.forEach { dataValue ->
            val key = "${dataValue.dataElement}|${dataValue.categoryOptionCombo}"
            _fieldStates[key] = androidx.compose.ui.text.input.TextFieldValue(dataValue.value ?: "")
        }

        savePressed = false

        Log.d("DataEntryViewModel", "Cleared current session changes, preserved existing drafts")
    }

    fun toggleGridRow(sectionName: String, rowKey: String) {
        _uiState.update { currentState ->
            val currentSet = currentState.expandedGridRows[sectionName] ?: emptySet()
            val newSet =
                if (currentSet.contains(rowKey)) currentSet - rowKey else currentSet + rowKey
            currentState.copy(
                expandedGridRows = currentState.expandedGridRows.toMutableMap().apply {
                    put(sectionName, newSet)
                }
            )
        }
    }

    fun isGridRowExpanded(sectionName: String, rowKey: String): Boolean {
        return _uiState.value.expandedGridRows[sectionName]?.contains(rowKey) == true
    }

    fun startValidationForCompletion() {
        val stateSnapshot = _uiState.value
        viewModelScope.launch {
            Log.d("DataEntryViewModel", "=== COMPLETION FLOW: Starting validation for completion ===")
            Log.d("DataEntryViewModel", "Current isCompleted state: ${stateSnapshot.isCompleted}")
            Log.d("DataEntryViewModel", "Dataset: ${stateSnapshot.datasetId}, Period: ${stateSnapshot.period}, OrgUnit: ${stateSnapshot.orgUnit}")
            _uiState.update { it.copy(isValidating = true, error = null, validationSummary = null) }
            
            try {
                val validationResult = validationRepository.validateDatasetInstance(
                    datasetId = stateSnapshot.datasetId,
                    period = stateSnapshot.period,
                    organisationUnit = stateSnapshot.orgUnit,
                    attributeOptionCombo = stateSnapshot.attributeOptionCombo,
                    dataValues = stateSnapshot.dataValues,
                    forceRefresh = true
                )
                
                Log.d("DataEntryViewModel", "Validation completed: ${validationResult.errorCount} errors, ${validationResult.warningCount} warnings")
                
                _uiState.update {
                    it.copy(
                        isValidating = false, 
                        validationSummary = validationResult
                    ) 
                }
                
            } catch (e: Exception) {
                Log.e("DataEntryViewModel", "Error during validation: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isValidating = false,
                        error = "Error during validation: ${e.message}"
                    )
                }
            }
        }
    }

    fun completeDatasetAfterValidation(onResult: (Boolean, String?) -> Unit) {
        val stateSnapshot = _uiState.value
        viewModelScope.launch {
            Log.d("DataEntryViewModel", "=== COMPLETION FLOW: Proceeding with dataset completion after validation ===")
            Log.d("DataEntryViewModel", "Dataset: ${stateSnapshot.datasetId}, Period: ${stateSnapshot.period}, OrgUnit: ${stateSnapshot.orgUnit}")
            Log.d("DataEntryViewModel", "AttributeOptionCombo: ${stateSnapshot.attributeOptionCombo}")
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            try {
                val result = useCases.completeDatasetInstance(
                    stateSnapshot.datasetId,
                    stateSnapshot.period,
                    stateSnapshot.orgUnit,
                    stateSnapshot.attributeOptionCombo
                )
                
                if (result.isSuccess) {
                    val validationSummary = stateSnapshot.validationSummary
                    val successMessage = if (validationSummary?.warningCount ?: 0 > 0) {
                        "Dataset marked as complete successfully. Note: ${validationSummary?.warningCount} validation warning(s) were found."
                    } else {
                        "Dataset marked as complete successfully. All validation rules passed."
                    }
                    
                    Log.d("DataEntryViewModel", successMessage)
                    _uiState.update { it.copy(isCompleted = true, isLoading = false, validationSummary = null) }
                    onResult(true, successMessage)
                } else {
                    Log.e("DataEntryViewModel", "Failed to mark dataset as complete: ${result.exceptionOrNull()?.message}")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.exceptionOrNull()?.message
                        )
                    }
                    onResult(false, result.exceptionOrNull()?.message)
                }
                
            } catch (e: Exception) {
                Log.e("DataEntryViewModel", "Error during completion: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Error during completion: ${e.message}"
                    )
                }
                onResult(false, "Error during completion: ${e.message}")
            }
        }
    }

    fun markDatasetIncomplete(onResult: (Boolean, String?) -> Unit) {
        val stateSnapshot = _uiState.value
        viewModelScope.launch {
            Log.d("DataEntryViewModel", "=== COMPLETION FLOW: Marking dataset as incomplete ===")
            Log.d("DataEntryViewModel", "Dataset: ${stateSnapshot.datasetId}, Period: ${stateSnapshot.period}, OrgUnit: ${stateSnapshot.orgUnit}")
            Log.d("DataEntryViewModel", "AttributeOptionCombo: ${stateSnapshot.attributeOptionCombo}")
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val result = useCases.markDatasetInstanceIncomplete(
                    stateSnapshot.datasetId,
                    stateSnapshot.period,
                    stateSnapshot.orgUnit,
                    stateSnapshot.attributeOptionCombo
                )

                if (result.isSuccess) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isCompleted = false,
                            error = null
                        )
                    }
                    onResult(true, "Dataset marked as incomplete")
                } else {
                    Log.e("DataEntryViewModel", "Failed to mark dataset incomplete: ${result.exceptionOrNull()}")
                    _uiState.update { it.copy(isLoading = false, error = result.exceptionOrNull()?.message) }
                    onResult(false, result.exceptionOrNull()?.message)
                }
            } catch (e: Exception) {
                Log.e("DataEntryViewModel", "Exception marking dataset incomplete: ${e.message}", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
                onResult(false, e.message)
            }
        }
    }

    fun clearValidationResult() {
        _uiState.update { it.copy(validationSummary = null) }
    }
}
