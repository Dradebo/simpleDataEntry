package com.ash.simpledataentry.presentation.dataEntry

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ash.simpledataentry.data.DatabaseProvider
import com.ash.simpledataentry.data.cache.MetadataCacheService
import com.ash.simpledataentry.data.local.DataValueDraftEntity
import com.ash.simpledataentry.data.repositoryImpl.ValidationRepository
import com.ash.simpledataentry.domain.model.*
import com.ash.simpledataentry.domain.model.GroupType
import com.ash.simpledataentry.domain.grouping.DataElementGroupingAnalyzer
import com.ash.simpledataentry.data.sync.DetailedSyncProgress
import com.ash.simpledataentry.data.sync.SyncQueueManager
import com.ash.simpledataentry.data.sync.SyncStatusController
import com.ash.simpledataentry.domain.repository.DataEntryRepository
import com.ash.simpledataentry.domain.useCase.DataEntryUseCases
import com.ash.simpledataentry.util.NetworkUtils
import com.ash.simpledataentry.data.sync.NetworkStateManager
import com.ash.simpledataentry.presentation.core.LoadingOperation
import com.ash.simpledataentry.presentation.core.LoadingProgress
import com.ash.simpledataentry.presentation.core.UiError
import com.ash.simpledataentry.presentation.core.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hisp.dhis.android.core.dataset.DataSetEditableStatus
import org.hisp.dhis.android.core.dataset.DataSetNonEditableReason
import javax.inject.Inject

data class DataEntryState(
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
    val isSyncing: Boolean = false,
    val detailedSyncProgress: DetailedSyncProgress? = null, // Enhanced sync progress
    val successMessage: String? = null,
    val isValidating: Boolean = false,
    val validationSummary: ValidationSummary? = null,
    val navigationProgress: com.ash.simpledataentry.presentation.core.NavigationProgress? = null, // Enhanced loading progress
    val completionProgress: com.ash.simpledataentry.presentation.core.CompletionProgress? = null, // Enhanced completion progress
    val showCompletionDialog: Boolean = false,
    val completionAction: com.ash.simpledataentry.presentation.core.CompletionAction? = null,
    val optionSets: Map<String, com.ash.simpledataentry.domain.model.OptionSet> = emptyMap(), // Option sets by data element ID
    val renderTypes: Map<String, com.ash.simpledataentry.domain.model.RenderType> = emptyMap(), // Computed render types by data element ID
    val valuesByCombo: Map<String, List<DataValue>> = emptyMap(),
    val valuesByElement: Map<String, List<DataValue>> = emptyMap(),
    val dataElementsBySection: Map<String, List<Pair<String, String>>> = emptyMap(),
    val isEntryEditable: Boolean = true,
    val nonEditableReason: DataSetNonEditableReason? = null,
    val metadataDisabledFields: Set<String> = emptySet(),

    // Program rule effects (currently for tracker/event, can be extended to aggregate)
    val hiddenFields: Set<String> = emptySet(),
    val disabledFields: Set<String> = emptySet(),
    val mandatoryFields: Set<String> = emptySet(),
    val fieldWarnings: Map<String, String> = emptyMap(),
    val fieldErrors: Map<String, String> = emptyMap(),
    val calculatedValues: Map<String, String> = emptyMap(),

    // Radio button groups: Map<groupTitle, List<dataElementIds>>
    val radioButtonGroups: Map<String, List<String>> = emptyMap(),

    // Checkbox groups: Map<groupTitle, List<dataElementIds>>
    val checkboxGroups: Map<String, List<String>> = emptyMap(),

    // Generic grouping strategies per section
    val sectionGroupingStrategies: Map<String, List<GroupingStrategy>> = emptyMap(),

    // PERFORMANCE OPTIMIZATION: Pre-computed data element ordering per section
    // This avoids expensive re-computation on every render
    // Key: sectionName, Value: Map<dataElement, orderIndex>
    val dataElementOrdering: Map<String, Map<String, Int>> = emptyMap()
)

@HiltViewModel
class DataEntryViewModel @Inject constructor(
    private val application: Application,
    private val repository: DataEntryRepository,
    private val useCases: DataEntryUseCases,
    private val databaseProvider: DatabaseProvider,
    private val validationRepository: ValidationRepository,
    private val syncQueueManager: SyncQueueManager,
    private val networkStateManager: NetworkStateManager,
    private val sessionManager: com.ash.simpledataentry.data.SessionManager,
    private val metadataCacheService: com.ash.simpledataentry.data.cache.MetadataCacheService,
    private val syncStatusController: SyncStatusController
) : ViewModel() {
    private val _state = MutableStateFlow(DataEntryState())
    val state: StateFlow<DataEntryState> = _state.asStateFlow()
    private val _uiState = MutableStateFlow<UiState<DataEntryState>>(UiState.Loading(LoadingOperation.Initial))
    val uiState: StateFlow<UiState<DataEntryState>> = _uiState.asStateFlow()
    private var lastSuccessfulState: DataEntryState? = null
    val syncController: SyncStatusController = syncStatusController
    private val draftDao get() = databaseProvider.getCurrentDatabase().dataValueDraftDao()

    private fun emitSuccessState() {
        val current = _state.value
        lastSuccessfulState = current
        _uiState.value = UiState.Success(current)
    }

    private fun updateState(
        autoEmit: Boolean = true,
        transform: (DataEntryState) -> DataEntryState
    ) {
        _state.update(transform)
        if (autoEmit && _uiState.value is UiState.Success) {
            emitSuccessState()
        }
    }

    private fun setUiLoading(operation: LoadingOperation, progress: LoadingProgress? = null) {
        _uiState.value = UiState.Loading(operation, progress)
    }

    private fun setUiError(error: UiError) {
        _uiState.value = UiState.Error(error, previousData = lastSuccessfulState)
    }

    // Grouping analyzer for intelligent data element organization
    private val groupingAnalyzer = DataElementGroupingAnalyzer()

    // Track unsaved edits: key = Pair<dataElement, categoryOptionCombo>, value = DataValue
    private val dirtyDataValues = mutableMapOf<Pair<String, String>, DataValue>()

    // Program rule evaluation debouncing
    private var ruleEvaluationJob: kotlinx.coroutines.Job? = null
    private val ruleEvaluationDelay = 300L // milliseconds

    init {
        // Observe sync progress from SyncQueueManager
        viewModelScope.launch {
            syncQueueManager.detailedProgress.collect { progress ->
                updateState { currentState ->
                    currentState.copy(
                        detailedSyncProgress = progress,
                        isSyncing = progress != null
                    )
                }
            }
        }
    }

    // --- BEGIN: Per-field TextFieldValue state ---
    private val _fieldStates = MutableStateFlow<Map<String, androidx.compose.ui.text.input.TextFieldValue>>(emptyMap())
    val fieldStates: StateFlow<Map<String, androidx.compose.ui.text.input.TextFieldValue>> = _fieldStates.asStateFlow()
    private fun fieldKey(dataElement: String, categoryOptionCombo: String): String = "$dataElement|$categoryOptionCombo"
    fun initializeFieldState(dataValue: DataValue) {
        val key = fieldKey(dataValue.dataElement, dataValue.categoryOptionCombo)
        if (!_fieldStates.value.containsKey(key)) {
            _fieldStates.update { current ->
                current + (key to androidx.compose.ui.text.input.TextFieldValue(dataValue.value ?: ""))
            }
        }
    }
    fun onFieldValueChange(newValue: androidx.compose.ui.text.input.TextFieldValue, dataValue: DataValue) {
        val key = fieldKey(dataValue.dataElement, dataValue.categoryOptionCombo)
        _fieldStates.update { current -> current + (key to newValue) }
        updateCurrentValue(newValue.text, dataValue.dataElement, dataValue.categoryOptionCombo)
    }
    // --- END: Per-field TextFieldValue state ---

    private var savePressed = false

    private fun hasBlockingValidationErrors(): Boolean {
        return _state.value.fieldErrors.isNotEmpty()
    }

    private data class CategoryMaps(
        val structuresByOptionCombo: Map<String, List<Pair<String, List<Pair<String, String>>>>>,
        val optionUidsByOptionCombo: Map<String, Map<Set<String>, String>>
    )

    private fun buildCategoryMapsFromFormStructure(
        formStructure: MetadataCacheService.DatasetFormStructure
    ): CategoryMaps {
        // BLACKBOX: Category parsing is derived strictly from metadata.
        // Do not change without profiling; regressions here can cause ANRs.
        val structuresByOptionCombo = buildMap<String, List<Pair<String, List<Pair<String, String>>>>> {
            formStructure.categoryOptionCombos.forEach { (optionComboId, optionCombo) ->
                put(optionComboId, formStructure.categoryStructures[optionCombo.categoryComboId].orEmpty())
            }
        }
        val optionUidsByOptionCombo = buildMap<String, Map<Set<String>, String>> {
            formStructure.categoryOptionCombos.forEach { (optionComboId, optionCombo) ->
                put(optionComboId, formStructure.optionUidsToComboUid[optionCombo.categoryComboId].orEmpty())
            }
        }
        return CategoryMaps(structuresByOptionCombo, optionUidsByOptionCombo)
    }

    fun loadDataValues(
        datasetId: String,
        datasetName: String,
        period: String,
        orgUnitId: String,
        attributeOptionCombo: String,
        isEditMode: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // BLACKBOX: Category parsing is derived strictly from metadata.
                // Do not change without profiling; regressions here can cause ANRs.
                val formStructure = metadataCacheService.getDatasetFormStructure(datasetId)
                val categoryMaps = buildCategoryMapsFromFormStructure(formStructure)

                val editabilityDeferred = async {
                    resolveDatasetEditability(
                        datasetId = datasetId,
                        period = period,
                        orgUnitId = orgUnitId,
                        attributeOptionCombo = attributeOptionCombo
                    )
                }
                val completionDeferred = async {
                    resolveDatasetCompletion(
                        datasetId = datasetId,
                        period = period,
                        orgUnitId = orgUnitId,
                        attributeOptionCombo = attributeOptionCombo
                    )
                }
                val initialProgress = com.ash.simpledataentry.presentation.core.NavigationProgress(
                    phase = com.ash.simpledataentry.presentation.core.LoadingPhase.INITIALIZING,
                    overallPercentage = 10,
                    phaseTitle = com.ash.simpledataentry.presentation.core.LoadingPhase.INITIALIZING.title,
                    phaseDetail = "Preparing form...",
                    loadingType = com.ash.simpledataentry.presentation.core.StepLoadingType.ENTRY
                )
                updateState { currentState ->
                    currentState.copy(
                        isLoading = true,
                        error = null,
                        datasetId = datasetId,
                        datasetName = datasetName,
                        period = period,
                        orgUnit = orgUnitId,
                        attributeOptionCombo = attributeOptionCombo,
                        isEditMode = isEditMode,
                        navigationProgress = initialProgress
                    )
                }
                setUiLoading(
                    operation = LoadingOperation.Navigation(initialProgress),
                    progress = LoadingProgress(message = initialProgress.phaseDetail)
                )

                // Step 1: Load Drafts (10-30%)
                updateState {
                    it.copy(
                        navigationProgress = com.ash.simpledataentry.presentation.core.NavigationProgress(
                            phase = com.ash.simpledataentry.presentation.core.LoadingPhase.LOADING_DATA,
                            overallPercentage = 25,
                            phaseTitle = com.ash.simpledataentry.presentation.core.LoadingPhase.LOADING_DATA.title,
                            phaseDetail = "Loading draft data...",
                            loadingType = com.ash.simpledataentry.presentation.core.StepLoadingType.ENTRY
                        )
                    )
                }

                val drafts = withContext(Dispatchers.IO) {
                    draftDao.getDraftsForInstance(datasetId, period, orgUnitId, attributeOptionCombo)
                }
                val draftMap = drafts.associateBy { it.dataElement to it.categoryOptionCombo }

                val attributeOptionComboDeferred = async {
                    repository.getAttributeOptionCombos(datasetId)
                }

                // Step 2: Load Data Values (30-50%)
                updateState {
                    it.copy(
                        navigationProgress = com.ash.simpledataentry.presentation.core.NavigationProgress(
                            phase = com.ash.simpledataentry.presentation.core.LoadingPhase.LOADING_DATA,
                            overallPercentage = 40,
                            phaseTitle = com.ash.simpledataentry.presentation.core.LoadingPhase.LOADING_DATA.title,
                            phaseDetail = "Loading form data...",
                            loadingType = com.ash.simpledataentry.presentation.core.StepLoadingType.ENTRY
                        )
                    )
                }

                val dataValuesFlow = repository.getDataValues(datasetId, period, orgUnitId, attributeOptionCombo)
                dataValuesFlow.collect { values ->
                    // Step 3: Process Categories (50-70%)
                    updateState {
                        it.copy(
                            navigationProgress = com.ash.simpledataentry.presentation.core.NavigationProgress(
                                phase = com.ash.simpledataentry.presentation.core.LoadingPhase.PROCESSING_DATA,
                                overallPercentage = 60,
                                phaseTitle = com.ash.simpledataentry.presentation.core.LoadingPhase.PROCESSING_DATA.title,
                                phaseDetail = "Processing categories...",
                                loadingType = com.ash.simpledataentry.presentation.core.StepLoadingType.ENTRY
                            )
                        )
                    }
                    val categoryComboStructures = categoryMaps.structuresByOptionCombo
                    val optionUidsToComboUid = categoryMaps.optionUidsByOptionCombo

                    Log.d("DataEntryViewModel", "=== CATEGORY COMBO STRUCTURE SUMMARY ===")
                    Log.d("DataEntryViewModel", "Total structures loaded: ${categoryComboStructures.size}")
                    val nonDefaultCount = categoryComboStructures.values.count { it.isNotEmpty() }
                    Log.d("DataEntryViewModel", "Non-default structures: $nonDefaultCount")

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
                    val valuesByCombo = mergedValues.groupBy { it.categoryOptionCombo }
                    val valuesByElement = mergedValues.groupBy { it.dataElement }
                    val metadataDisabledFields = resolveMetadataDisabledFields(
                        mergedValues.map { it.dataElement }.distinct()
                    )
                    val (isEntryEditable, nonEditableReason) = editabilityDeferred.await()
                    val isCompleted = completionDeferred.await()

                    dirtyDataValues.clear()
                    savePressed = false // Reset save state when loading new data
                    draftMap.forEach { (key, draft) ->
                        mergedValues.find { it.dataElement == key.first && it.categoryOptionCombo == key.second }?.let { merged ->
                            dirtyDataValues[key] = merged
                        }
                    }

                    // Step 3.5: Load Option Sets (65-80%)
                    updateState {
                        it.copy(
                            navigationProgress = com.ash.simpledataentry.presentation.core.NavigationProgress(
                                phase = com.ash.simpledataentry.presentation.core.LoadingPhase.PROCESSING_DATA,
                                overallPercentage = 70,
                                phaseTitle = com.ash.simpledataentry.presentation.core.LoadingPhase.PROCESSING_DATA.title,
                                phaseDetail = "Loading option sets...",
                                loadingType = com.ash.simpledataentry.presentation.core.StepLoadingType.ENTRY
                            )
                        )
                    }

                    val optionSets = repository.getAllOptionSetsForDataset(datasetId)

                    // Compute render types on background thread to avoid UI freezes
                    val renderTypes = withContext(Dispatchers.Default) {
                        optionSets.mapValues { (_, optionSet) ->
                            optionSet.computeRenderType()
                        }
                    }

                    // Fetch validation rules for intelligent grouping
                    val validationRules = repository.getValidationRulesForDataset(datasetId)
                    Log.d("DataEntryViewModel", "Fetched ${validationRules.size} validation rules for dataset $datasetId")

                    // Step 3.6: Analyze grouping strategies per section (75-85%)
                    updateState {
                        it.copy(
                            navigationProgress = com.ash.simpledataentry.presentation.core.NavigationProgress(
                                phase = com.ash.simpledataentry.presentation.core.LoadingPhase.PROCESSING_DATA,
                                overallPercentage = 80,
                                phaseTitle = com.ash.simpledataentry.presentation.core.LoadingPhase.PROCESSING_DATA.title,
                                phaseDetail = "Analyzing data grouping...",
                                loadingType = com.ash.simpledataentry.presentation.core.StepLoadingType.ENTRY
                            )
                        )
                    }

                    val groupedBySection = mergedValues.groupBy { it.sectionName }

                    // Check singleton cache first to avoid expensive re-computation
                    // This cache persists across ViewModel lifecycle for optimal performance
                    val sectionGroupingStrategies = metadataCacheService.getGroupingStrategies(datasetId) ?: run {
                        // Cache miss - compute grouping strategies on background thread
                        Log.d("DataEntryViewModel", "========== GROUPING ANALYSIS START ==========")
                        Log.d("DataEntryViewModel", "Cache miss for dataset $datasetId - computing grouping strategies")

                        val computed = withContext(Dispatchers.Default) {
                            groupedBySection.mapValues { (sectionName, sectionValues) ->

                                val strategies = groupingAnalyzer.analyzeGrouping(
                                    dataElements = sectionValues,
                                    categoryComboStructures = categoryComboStructures,
                                    optionSets = optionSets,
                                    validationRules = validationRules
                                )


                                strategies
                            }
                        }
                        // Store in singleton cache for persistence across ViewModel lifecycle
                        metadataCacheService.setGroupingStrategies(datasetId, computed)

                        Log.d("DataEntryViewModel", "=== CACHED STRATEGIES SUMMARY ===")
                        computed.forEach { (section, strategies) ->
                            Log.d("DataEntryViewModel", "  Section '$section': ${strategies.size} strategies")
                        }

                        computed
                    }

                    // Preserve legacy radio button groups for backward compatibility and merge heuristics
                    val analyzerRadioGroups = sectionGroupingStrategies.values
                        .flatten()
                        .filter { it.groupType == GroupType.RADIO_GROUP }
                        .associate { it.groupTitle to it.members.map { dv -> dv.dataElement } }

                    val heuristicRadioGroups = detectRadioButtonGroupsByName(mergedValues, optionSets)
                    val radioButtonGroups = analyzerRadioGroups.toMutableMap()
                    heuristicRadioGroups.forEach { (title, ids) ->
                        radioButtonGroups.putIfAbsent(title, ids)
                    }

                    val checkboxGroups = sectionGroupingStrategies.values
                        .flatten()
                        .filter { it.groupType == GroupType.CHECKBOX_GROUP }
                        .associate { it.groupTitle to it.members.map { dv -> dv.dataElement } }

                    // Step 4: Finalizing (85-100%)
                    updateState {
                        it.copy(
                            navigationProgress = com.ash.simpledataentry.presentation.core.NavigationProgress(
                                phase = com.ash.simpledataentry.presentation.core.LoadingPhase.COMPLETING,
                                overallPercentage = 90,
                                phaseTitle = com.ash.simpledataentry.presentation.core.LoadingPhase.COMPLETING.title,
                                phaseDetail = "Setting up form...",
                                loadingType = com.ash.simpledataentry.presentation.core.StepLoadingType.ENTRY
                            )
                        )
                    }

                    updateState { currentState ->
                        val dataElementGroupedSections = groupedBySection.mapValues { (_, sectionValues) ->
                            sectionValues.groupBy { it.dataElement }
                        }
                        val totalSections = groupedBySection.size

                        // PERFORMANCE OPTIMIZATION: Pre-compute data element ordering for all sections
                        // This eliminates expensive re-computation on every render
                        val dataElementOrdering = groupedBySection.mapValues { (sectionName, sectionValues) ->
                            sectionValues
                                .groupBy { it.dataElement }
                                .keys
                                .mapIndexed { index, dataElement -> dataElement to index }
                                .toMap()
                        }
                        Log.d("DataEntryViewModel", "Pre-computed data element ordering for ${dataElementOrdering.size} sections")

                        val dataElementsBySection = dataElementGroupedSections.mapValues { (sectionName, elementGroups) ->
                            val ordering = dataElementOrdering[sectionName].orEmpty()
                            elementGroups.map { (dataElement, dataValues) ->
                                val name = dataValues.firstOrNull()?.dataElementName ?: dataElement
                                dataElement to name
                            }.sortedBy { (dataElement, _) -> ordering[dataElement] ?: Int.MAX_VALUE }
                        }

                        // Determine the initial currentSectionIndex
                        val initialOrPreservedIndex = if (totalSections > 0) {
                            // If you want to ALWAYS open the first section on load, uncomment next line:
                            // 0
                            // If you want to respect a previously opened section or default to closed:
                            if (currentState.currentSectionIndex >= 0 && currentState.currentSectionIndex < totalSections) {
                                currentState.currentSectionIndex // Preserve if valid
                            } else if (currentState.currentSectionIndex == -1 && currentState.dataValues.isEmpty()) { // First ever load and state is still default
                                0 // Open first section on very first load
                            }
                            else {
                                currentState.currentSectionIndex // Keep as -1 or whatever it was if sections changed
                            }
                        } else {
                            -1 // No sections, so no section can be open
                        }.let {
                            // Final check to ensure index is valid or -1
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
                            dataElementGroupedSections = dataElementGroupedSections,
                            optionSets = optionSets,
                            renderTypes = renderTypes,
                            valuesByCombo = valuesByCombo,
                            valuesByElement = valuesByElement,
                            dataElementsBySection = dataElementsBySection,
                            radioButtonGroups = radioButtonGroups,
                            checkboxGroups = checkboxGroups,
                            sectionGroupingStrategies = sectionGroupingStrategies,
                            dataElementOrdering = dataElementOrdering, // Pre-computed ordering for performance
                            navigationProgress = null, // Clear progress when done
                            isEntryEditable = isEntryEditable,
                            nonEditableReason = nonEditableReason,
                            isCompleted = isCompleted,
                            metadataDisabledFields = metadataDisabledFields
                        )
                    }
                }
                
                // Load draft count after data is loaded
                loadDraftCount()
                emitSuccessState()

            } catch (e: Exception) {
                Log.e("DataEntryViewModel", "Failed to load data values", e)
                updateState { currentState ->
                    currentState.copy(
                        error = "Failed to load data values: ${e.message}",
                        isLoading = false,
                        navigationProgress = com.ash.simpledataentry.presentation.core.NavigationProgress.error(e.message ?: "Failed to load data values")
                    )
                }
                setUiError(UiError.Local(e.message ?: "Failed to load data values"))
            }
        }
    }

    /**
     * Load a NEW entry - uses cached metadata only, no data value loading.
     * This is the decoupled approach: metadata is loaded from cache,
     * empty DataValues are generated from the form structure.
     *
     * Key benefits:
     * - No SDK calls for data values (there are none for new entries)
     * - Category structures come from metadata, not from data values
     * - Much faster loading for new entries
     */
    fun loadNewEntry(
        datasetId: String,
        datasetName: String,
        period: String,
        orgUnitId: String,
        attributeOptionCombo: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d("DataEntryViewModel", "=== LOADING NEW ENTRY (metadata-only approach) ===")
                Log.d("DataEntryViewModel", "Dataset: $datasetId, Period: $period, OrgUnit: $orgUnitId")

                // Set initial loading state
                val initialProgress = com.ash.simpledataentry.presentation.core.NavigationProgress(
                    phase = com.ash.simpledataentry.presentation.core.LoadingPhase.INITIALIZING,
                    overallPercentage = 10,
                    phaseTitle = com.ash.simpledataentry.presentation.core.LoadingPhase.INITIALIZING.title,
                    phaseDetail = "Loading form structure...",
                    loadingType = com.ash.simpledataentry.presentation.core.StepLoadingType.ENTRY
                )
                updateState { currentState ->
                    currentState.copy(
                        isLoading = true,
                        error = null,
                        datasetId = datasetId,
                        datasetName = datasetName,
                        period = period,
                        orgUnit = orgUnitId,
                        attributeOptionCombo = attributeOptionCombo,
                        isEditMode = false, // New entry = not edit mode
                        navigationProgress = initialProgress
                    )
                }
                setUiLoading(
                    operation = LoadingOperation.Navigation(initialProgress),
                    progress = LoadingProgress(message = initialProgress.phaseDetail)
                )

                // Step 1: Load form structure from cached metadata (fast!)
                updateState {
                    it.copy(
                        navigationProgress = com.ash.simpledataentry.presentation.core.NavigationProgress(
                            phase = com.ash.simpledataentry.presentation.core.LoadingPhase.LOADING_DATA,
                            overallPercentage = 30,
                            phaseTitle = "Loading metadata",
                            phaseDetail = "Getting form structure from cache...",
                            loadingType = com.ash.simpledataentry.presentation.core.StepLoadingType.ENTRY
                        )
                    )
                }

                val formStructure = metadataCacheService.getDatasetFormStructure(datasetId)
                Log.d("DataEntryViewModel", "Form structure loaded: ${formStructure.sections.size} sections, ${formStructure.dataElements.size} data elements")
                val categoryMaps = buildCategoryMapsFromFormStructure(formStructure)
                val categoryStructuresByOptionCombo = categoryMaps.structuresByOptionCombo
                val optionUidsToComboUidByOptionCombo = categoryMaps.optionUidsByOptionCombo

                // Step 2: Generate empty data values from metadata (no SDK calls!)
                updateState {
                    it.copy(
                        navigationProgress = com.ash.simpledataentry.presentation.core.NavigationProgress(
                            phase = com.ash.simpledataentry.presentation.core.LoadingPhase.PROCESSING_DATA,
                            overallPercentage = 50,
                            phaseTitle = "Preparing form",
                            phaseDetail = "Generating entry fields...",
                            loadingType = com.ash.simpledataentry.presentation.core.StepLoadingType.ENTRY
                        )
                    )
                }

                val emptyValues = metadataCacheService.generateEmptyDataValues(formStructure)
                Log.d("DataEntryViewModel", "Generated ${emptyValues.size} empty data values")

                // Step 3: Get attribute option combos for the dataset
                val attributeOptionCombos = repository.getAttributeOptionCombos(datasetId)
                val attributeOptionComboName = attributeOptionCombos.find { it.first == attributeOptionCombo }?.second ?: attributeOptionCombo

                // Step 4: Build grouped sections
                updateState {
                    it.copy(
                        navigationProgress = com.ash.simpledataentry.presentation.core.NavigationProgress(
                            phase = com.ash.simpledataentry.presentation.core.LoadingPhase.PROCESSING_DATA,
                            overallPercentage = 70,
                            phaseTitle = "Organizing form",
                            phaseDetail = "Building sections...",
                            loadingType = com.ash.simpledataentry.presentation.core.StepLoadingType.ENTRY
                        )
                    )
                }

                val groupedBySection = emptyValues.groupBy { it.sectionName }
                val valuesByElement = emptyValues.groupBy { it.dataElement }
                val valuesByCombo = emptyValues.groupBy { it.categoryOptionCombo }

                // Build dataElementsBySection for navigation
                val dataElementsBySection = groupedBySection.mapValues { (_, sectionValues) ->
                    sectionValues
                        .map { it.dataElement to it.dataElementName }
                        .distinctBy { it.first }
                }

                // Build dataElementGroupedSections
                val dataElementGroupedSections = groupedBySection.mapValues { (_, sectionValues) ->
                    sectionValues.groupBy { it.dataElement }
                }

                // Step 5: Analyze grouping strategies (from cache if available)
                updateState {
                    it.copy(
                        navigationProgress = com.ash.simpledataentry.presentation.core.NavigationProgress(
                            phase = com.ash.simpledataentry.presentation.core.LoadingPhase.PROCESSING_DATA,
                            overallPercentage = 85,
                            phaseTitle = "Finalizing",
                            phaseDetail = "Analyzing data grouping...",
                            loadingType = com.ash.simpledataentry.presentation.core.StepLoadingType.ENTRY
                        )
                    )
                }

                val validationRules = repository.getValidationRulesForDataset(datasetId)
                val sectionGroupingStrategies = metadataCacheService.getGroupingStrategies(datasetId) ?: run {
                    val computed = withContext(Dispatchers.Default) {
                        groupedBySection.mapValues { (sectionName, sectionValues) ->
                            groupingAnalyzer.analyzeGrouping(
                                dataElements = sectionValues,
                                categoryComboStructures = categoryStructuresByOptionCombo,
                                optionSets = formStructure.optionSets,
                                validationRules = validationRules
                            )
                        }
                    }
                    metadataCacheService.setGroupingStrategies(datasetId, computed)
                    computed
                }

                // Build radio/checkbox groups from strategies
                val radioButtonGroups = mutableMapOf<String, List<String>>()
                val checkboxGroups = mutableMapOf<String, List<String>>()
                sectionGroupingStrategies.forEach { (_, strategies) ->
                    strategies.forEach { strategy ->
                        val dataElementIds = strategy.members.map { it.dataElement }.distinct()
                        when (strategy.groupType) {
                            GroupType.RADIO_GROUP -> radioButtonGroups[strategy.groupTitle] = dataElementIds
                            GroupType.CHECKBOX_GROUP -> checkboxGroups[strategy.groupTitle] = dataElementIds
                            else -> {}
                        }
                    }
                }

                // Build data element ordering
                val dataElementOrdering = dataElementsBySection.mapValues { (_, elements) ->
                    elements.mapIndexed { index, (elementId, _) -> elementId to index }.toMap()
                }

                // Step 6: Finalize state
                updateState { currentState ->
                    currentState.copy(
                        isLoading = false,
                        dataValues = emptyValues,
                        // Category structures from metadata (not from data values!)
                        categoryComboStructures = categoryStructuresByOptionCombo,
                        optionUidsToComboUid = optionUidsToComboUidByOptionCombo,
                        attributeOptionCombos = attributeOptionCombos,
                        attributeOptionComboName = attributeOptionComboName,
                        valuesByCombo = valuesByCombo,
                        valuesByElement = valuesByElement,
                        dataElementsBySection = dataElementsBySection,
                        dataElementGroupedSections = dataElementGroupedSections,
                        currentSectionIndex = 0,
                        totalSections = groupedBySection.size,
                        optionSets = formStructure.optionSets,
                        renderTypes = formStructure.renderTypes,
                        radioButtonGroups = radioButtonGroups,
                        checkboxGroups = checkboxGroups,
                        sectionGroupingStrategies = sectionGroupingStrategies,
                        dataElementOrdering = dataElementOrdering,
                        navigationProgress = null,
                        isEntryEditable = true, // New entries are always editable
                        nonEditableReason = null,
                        isCompleted = false,
                        metadataDisabledFields = emptySet()
                    )
                }

                Log.d("DataEntryViewModel", "=== NEW ENTRY LOADED SUCCESSFULLY ===")
                Log.d("DataEntryViewModel", "Sections: ${groupedBySection.size}, Values: ${emptyValues.size}")

                emitSuccessState()

            } catch (e: Exception) {
                Log.e("DataEntryViewModel", "Failed to load new entry", e)
                updateState { currentState ->
                    currentState.copy(
                        error = "Failed to load form: ${e.message}",
                        isLoading = false,
                        navigationProgress = com.ash.simpledataentry.presentation.core.NavigationProgress.error(e.message ?: "Failed to load form")
                    )
                }
                setUiError(UiError.Local(e.message ?: "Failed to load form"))
            }
        }
    }

    private suspend fun resolveDatasetEditability(
        datasetId: String,
        period: String,
        orgUnitId: String,
        attributeOptionCombo: String
    ): Pair<Boolean, DataSetNonEditableReason?> = withContext(Dispatchers.IO) {
        val d2 = sessionManager.getD2() ?: return@withContext true to null
        return@withContext try {
            val status = d2.dataSetModule()
                .dataSetInstanceService()
                .blockingGetEditableStatus(datasetId, period, orgUnitId, attributeOptionCombo)
            when (status) {
                is DataSetEditableStatus.Editable -> true to null
                is DataSetEditableStatus.NonEditable -> false to status.reason
            }
        } catch (e: Exception) {
            Log.w("DataEntryViewModel", "Failed to resolve dataset editability", e)
            true to null
        }
    }

    private suspend fun resolveDatasetCompletion(
        datasetId: String,
        period: String,
        orgUnitId: String,
        attributeOptionCombo: String
    ): Boolean = withContext(Dispatchers.IO) {
        val d2 = sessionManager.getD2() ?: return@withContext false
        return@withContext try {
            val instance = d2.dataSetModule()
                .dataSetInstances()
                .dataSetInstance(datasetId, period, orgUnitId, attributeOptionCombo)
                .blockingGet()
            instance?.completed() == true
        } catch (e: Exception) {
            Log.w("DataEntryViewModel", "Failed to resolve dataset completion status", e)
            false
        }
    }

    private suspend fun resolveMetadataDisabledFields(
        dataElementIds: List<String>
    ): Set<String> = withContext(Dispatchers.IO) {
        // SDK DataElement does not expose field-level access in this version.
        emptySet()
    }

    fun updateCurrentValue(value: String, dataElementUid: String, categoryOptionComboUid: String) {
        val key = dataElementUid to categoryOptionComboUid
        val dataValueToUpdate = _state.value.dataValues.find {
            it.dataElement == dataElementUid && it.categoryOptionCombo == categoryOptionComboUid
        }

        if (dataValueToUpdate != null) {
            val updatedValueObject = dataValueToUpdate.copy(value = value)
            dirtyDataValues[key] = updatedValueObject

            // Reset savePressed when new changes are made after a save
            if (savePressed) {
                savePressed = false
            }

            updateState { currentState ->
                var updateCount = 0
                val updatedValues = currentState.dataValues.map {
                    if (it.dataElement == dataElementUid && it.categoryOptionCombo == categoryOptionComboUid) {
                        updateCount++
                        updatedValueObject
                    } else {
                        it
                    }
                }

                // CRITICAL FIX: Rebuild dataElementGroupedSections with updated values
                val updatedGroupedSections = updatedValues
                    .groupBy { it.sectionName }
                    .mapValues { (_, sectionValues) ->
                        sectionValues.groupBy { it.dataElement }
                    }

                currentState.copy(
                    dataValues = updatedValues,
                    dataElementGroupedSections = updatedGroupedSections,
                    currentDataValue = if (currentState.currentDataValue?.dataElement == dataElementUid && currentState.currentDataValue?.categoryOptionCombo == categoryOptionComboUid) updatedValueObject else currentState.currentDataValue
                )
            }

            val validationError = validateEntryValue(updatedValueObject, value)
            updateState { currentState ->
                val updatedErrors = currentState.fieldErrors.toMutableMap()
                if (validationError.isNullOrBlank()) {
                    updatedErrors.remove(dataElementUid)
                } else {
                    updatedErrors[dataElementUid] = validationError
                }
                currentState.copy(fieldErrors = updatedErrors)
            }

            viewModelScope.launch(Dispatchers.IO) {
                if (value.isNotBlank()) {
                    draftDao.upsertDraft(
                        DataValueDraftEntity(
                            datasetId = _state.value.datasetId,
                            period = _state.value.period,
                            orgUnit = _state.value.orgUnit,
                            attributeOptionCombo = _state.value.attributeOptionCombo,
                            dataElement = dataElementUid,
                            categoryOptionCombo = categoryOptionComboUid,
                            value = value,
                            comment = updatedValueObject.comment,
                            lastModified = System.currentTimeMillis()
                        )
                    )
                } else {
                    draftDao.deleteDraft(
                        datasetId = _state.value.datasetId,
                        period = _state.value.period,
                        orgUnit = _state.value.orgUnit,
                        attributeOptionCombo = _state.value.attributeOptionCombo,
                        dataElement = dataElementUid,
                        categoryOptionCombo = categoryOptionComboUid
                    )
                }

                // Update draft count after draft operation
                loadDraftCount()
            }
        } else {
            Log.w("DataEntryViewModel", "NO MATCH FOUND for dataElement=$dataElementUid, combo=$categoryOptionComboUid in ${_state.value.dataValues.size} values")
        }
    }

    private fun validateEntryValue(dataValue: DataValue, value: String?): String? {
        val validations = dataValue.dataEntryType.getDefaultValidation()
        if (validations.isEmpty()) return null

        val input = value.orEmpty()
        if (input.isBlank()) return null

        validations.forEach { validation ->
            when (validation) {
                is DataEntryValidation.Pattern -> {
                    if (!Regex(validation.regex).matches(input)) {
                        return validation.message
                    }
                }
                is DataEntryValidation.MinValue -> {
                    val numeric = input.toDoubleOrNull() ?: return validation.message
                    if (numeric < validation.value) {
                        return validation.message
                    }
                }
                is DataEntryValidation.MaxValue -> {
                    val numeric = input.toDoubleOrNull() ?: return validation.message
                    if (numeric > validation.value) {
                        return validation.message
                    }
                }
                is DataEntryValidation.Custom -> {
                    val result = validation.validator(input)
                    if (!result.isValid) {
                        return result.message
                    }
                }
                is DataEntryValidation.Required -> {
                    if (input.isBlank()) {
                        return validation.message
                    }
                }
            }
        }

        return null
    }

    /**
     * Update all fields in a grouped radio button set
     * Set selected field to "1" (Yes), all others to "0" (No)
     */
    fun updateGroupedRadioFields(groupFields: List<DataValue>, selectedFieldId: String?) {
        Log.d("DataEntryViewModel", "updateGroupedRadioFields called with selectedFieldId: $selectedFieldId, groupFields: ${groupFields.map { it.dataElementName }}")
        groupFields.forEach { field ->
            val newValue = if (field.dataElement == selectedFieldId) "1" else "0"
            Log.d("DataEntryViewModel", "Setting ${field.dataElementName} (${field.dataElement}) to $newValue")
            updateCurrentValue(newValue, field.dataElement, field.categoryOptionCombo)
        }
    }

    fun saveAllDataValues(context: android.content.Context? = null) {
        viewModelScope.launch {
            if (hasBlockingValidationErrors()) {
                updateState { it.copy(error = "Please fix the highlighted field errors before saving.") }
                return@launch
            }
            updateState { it.copy(saveInProgress = true, saveResult = null) }
            savePressed = true
            val stateSnapshot = _state.value

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

                updateState { it.copy(
                    saveInProgress = false,
                    saveResult = Result.success(Unit)
                ) }

            } catch (e: Exception) {
                updateState { it.copy(
                    saveInProgress = false,
                    saveResult = Result.failure(e)
                ) }
            }
        }
    }

    fun syncCurrentEntryForm() {
        viewModelScope.launch {
            if (hasBlockingValidationErrors()) {
                updateState { it.copy(error = "Please fix the highlighted field errors before syncing.") }
                return@launch
            }
            updateState { it.copy(isLoading = true, error = null) }
            val stateSnapshot = _state.value
            // More permissive network check - allow 2G connections
            val networkState = networkStateManager.networkState.value
            if (!networkState.isConnected || !networkState.hasInternet) {
                updateState { it.copy(isLoading = false, error = "Cannot sync while offline.") }
                return@launch
            }

            try {
                // 1. Load all drafts for the current instance
                val drafts = withContext(Dispatchers.IO) {
                    draftDao.getDraftsForInstance(
                        stateSnapshot.datasetId,
                        stateSnapshot.period,
                        stateSnapshot.orgUnit,
                        stateSnapshot.attributeOptionCombo
                    )
                }
                Log.d("DataEntryViewModel", "Loaded ${'$'}{drafts.size} drafts for sync")

                // 2. For each draft, stage value in SDK
                val results = drafts.map { draft ->
                    async(Dispatchers.IO) {
                        useCases.saveDataValue(
                            datasetId = draft.datasetId,
                            period = draft.period,
                            orgUnit = draft.orgUnit,
                            attributeOptionCombo = draft.attributeOptionCombo,
                            dataElement = draft.dataElement,
                            categoryOptionCombo = draft.categoryOptionCombo,
                            value = draft.value,
                            comment = draft.comment
                        )
                    }
                }.awaitAll()
                val failed = results.filter { it.isFailure }
                if (failed.isNotEmpty()) {
                    Log.e(
                        "DataEntryViewModel",
                        "Failed to stage ${'$'}{failed.size} drafts: ${'$'}{failed.map { it.exceptionOrNull()?.message }}"
                    )
                    updateState {
                        it.copy(
                            isLoading = false,
                            error = "Failed to stage some values for upload."
                        )
                    }
                    return@launch
                }
                Log.d("DataEntryViewModel", "All drafts staged in SDK")

                // 3. Trigger upload
                try {
                    val uploadResult = withContext(Dispatchers.IO) {
                        repository.syncCurrentEntryForm()
                    }
                    Log.d("DataEntryViewModel", "Data values uploaded successfully: $uploadResult")
                    withContext(Dispatchers.IO) {
                        draftDao.deleteDraftsForInstance(
                            stateSnapshot.datasetId,
                            stateSnapshot.period,
                            stateSnapshot.orgUnit,
                            stateSnapshot.attributeOptionCombo
                        )
                    }
                    Log.d("DataEntryViewModel", "Drafts deleted after successful upload")
                } catch (e: Exception) {
                    Log.e("DataEntryViewModel", "Upload failed: ${'$'}{e.message}", e)
                    updateState {
                        it.copy(
                            isLoading = false,
                            error = "Upload failed: ${'$'}{e.message}"
                        )
                    }
                    return@launch
                }

                // 4. Reload data values to refresh UI
                loadDataValues(
                    datasetId = _state.value.datasetId,
                    datasetName = _state.value.datasetName,
                    period = _state.value.period,
                    orgUnitId = _state.value.orgUnit,
                    attributeOptionCombo = _state.value.attributeOptionCombo,
                    isEditMode = _state.value.isEditMode
                )
            }
            catch (e: Exception) {
                null
            }
        }


    }

    private fun loadDraftCount() {
        viewModelScope.launch {
            try {
                val stateSnapshot = _state.value
                val draftCount = withContext(Dispatchers.IO) {
                    draftDao.getDraftCountForInstance(
                        stateSnapshot.datasetId,
                        stateSnapshot.period,
                        stateSnapshot.orgUnit,
                        stateSnapshot.attributeOptionCombo
                    )
                }
                updateState { it.copy(localDraftCount = draftCount) }
            } catch (e: Exception) {
                Log.e("DataEntryViewModel", "Failed to load draft count", e)
            }
        }
    }

    fun syncDataEntry(uploadFirst: Boolean = false) {
        val stateSnapshot = _state.value
        if (stateSnapshot.datasetId.isEmpty()) {
            Log.e("DataEntryViewModel", "Cannot sync: datasetId is empty")
            return
        }

        Log.d("DataEntryViewModel", "Starting enhanced sync for data entry: datasetId=${stateSnapshot.datasetId}, uploadFirst: $uploadFirst")
        viewModelScope.launch {
            try {
                // Use the enhanced SyncQueueManager which provides detailed progress tracking
                val syncResult = syncQueueManager.startSync(forceSync = uploadFirst)
                syncResult.fold(
                    onSuccess = {
                        Log.d("DataEntryViewModel", "Enhanced sync completed successfully")
                        // Reload all data after sync
                        loadDataValues(
                            datasetId = stateSnapshot.datasetId,
                            datasetName = stateSnapshot.datasetName,
                            period = stateSnapshot.period,
                            orgUnitId = stateSnapshot.orgUnit,
                            attributeOptionCombo = stateSnapshot.attributeOptionCombo,
                            isEditMode = stateSnapshot.isEditMode
                        )
                        val message = if (uploadFirst) {
                            "Data synchronized successfully with enhanced progress tracking"
                        } else {
                            "Data entry synced successfully"
                        }
                        updateState {
                            it.copy(
                                successMessage = message,
                                detailedSyncProgress = null // Clear progress when done
                            )
                        }
                    },
                    onFailure = { error ->
                        Log.e("DataEntryViewModel", "Enhanced sync failed", error)
                        val errorMessage = error.message ?: "Failed to sync data entry"
                        updateState {
                            it.copy(
                                error = errorMessage,
                                detailedSyncProgress = null // Clear progress on failure
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("DataEntryViewModel", "Enhanced sync failed", e)
                updateState {
                    it.copy(
                        isSyncing = false,
                        error = e.message ?: "Failed to sync data entry",
                        detailedSyncProgress = null
                    )
                }
            }
        }
    }

    fun clearMessages() {
        updateState { it.copy(error = null, successMessage = null) }
    }

    fun dismissSyncOverlay() {
        // Clear error state in SyncQueueManager to prevent persistent dialogs
        syncQueueManager.clearErrorState()
        updateState {
            it.copy(
                detailedSyncProgress = null,
                isSyncing = false
            )
        }
    }


    fun toggleSection(sectionName: String) {
        updateState { currentState ->
            val current = currentState.isExpandedSections[sectionName] ?: false
            currentState.copy(
                isExpandedSections = currentState.isExpandedSections.toMutableMap().apply {
                    this[sectionName] = !current
                }
            )
        }
    }

    fun setCurrentSectionIndex(index: Int) {
        updateState { currentState ->
            if (index < 0 || index >= currentState.totalSections) { // Safety check
                return@updateState currentState
            }
            val newIndex = if (currentState.currentSectionIndex == index) {
                -1 // If clicking the currently open section, close it
            } else {
                index // Otherwise, open the clicked section
            }
            currentState.copy(currentSectionIndex = newIndex)
        }
    }


    fun goToNextSection() {
        updateState { currentState ->
            if (currentState.totalSections == 0) return@updateState currentState

            val newIndex = if (currentState.currentSectionIndex == -1) {
                0 // If nothing is open, "Next" opens the first section
            } else {
                (currentState.currentSectionIndex + 1).coerceAtMost(currentState.totalSections - 1)
            }
            currentState.copy(currentSectionIndex = newIndex)
        }
    }

    fun goToPreviousSection() {
        updateState { currentState ->
            if (currentState.totalSections == 0) return@updateState currentState

            val newIndex = if (currentState.currentSectionIndex == -1) {
                currentState.totalSections - 1 // If nothing is open, "Previous" opens the last section
            } else {
                (currentState.currentSectionIndex - 1).coerceAtLeast(0)
            }
            currentState.copy(currentSectionIndex = newIndex)
        }
    }


    fun toggleCategoryGroup(sectionName: String, categoryGroup: String) {
        updateState { currentState ->
            val key = "$sectionName:$categoryGroup"
            val newExpanded = if (currentState.expandedCategoryGroup == key) null else key
            currentState.copy(expandedCategoryGroup = newExpanded)
        }
    }

    fun moveToNextStep(): Boolean {
        val currentStep = _state.value.currentStep
        val totalSteps = _state.value.dataValues.size
        return if (currentStep < totalSteps - 1) {
            updateState { currentState ->
                currentState.copy(
                    currentStep = currentStep + 1,
                    currentDataValue = _state.value.dataValues[currentStep + 1]
                )
            }
            false
        } else {
            updateState { currentState ->
                currentState.copy(
                    isEditMode = true,
                    isCompleted = true
                )
            }
            true
        }
    }

    fun moveToPreviousStep(): Boolean {
        val currentStep = _state.value.currentStep
        return if (currentStep > 0) {
            updateState { currentState ->
                currentState.copy(
                    currentStep = currentStep - 1,
                    currentDataValue = _state.value.dataValues[currentStep - 1]
                )
            }
            true
        } else {
            false
        }
    }

    suspend fun getAvailablePeriods(datasetId: String, limit: Int = 5, showAll: Boolean = false): List<Period> {
        return repository.getAvailablePeriods(datasetId, limit, showAll)
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

    suspend fun getScopedOrgUnits(): List<OrganisationUnit> {
        return try {
            repository.getScopedOrgUnits()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun expandOrgUnitSelection(targetId: String, orgUnitId: String): Set<String> {
        return try {
            repository.expandOrgUnitSelection(targetId, orgUnitId)
        } catch (e: Exception) {
            emptySet()
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
        updateState { it.copy(isNavigating = isNavigating) }
    }

    fun resetSaveFeedback() {
        updateState { it.copy(saveResult = null, saveInProgress = false) }
        // Don't reset savePressed here - only reset when new changes are made
    }

    fun wasSavePressed(): Boolean = savePressed
    
    fun hasUnsavedChanges(): Boolean = dirtyDataValues.isNotEmpty()

    fun clearDraftsForCurrentInstance() {
        val stateSnapshot = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            draftDao.deleteDraftsForInstance(
                stateSnapshot.datasetId,
                stateSnapshot.period,
                stateSnapshot.orgUnit,
                stateSnapshot.attributeOptionCombo
            )
            dirtyDataValues.clear()
            savePressed = false // Reset save state when discarding changes
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

    /**
     * Clear only the current session's unsaved changes without affecting previously saved drafts
     */
    fun clearCurrentSessionChanges() {
        // Simply reset the dirty tracking and field states to their loaded values
        val currentState = _state.value

        // Reset field states to their loaded values from the database
        dirtyDataValues.clear()

        // Reset field states to original loaded values
        val resetStates = currentState.dataValues.associate { dataValue ->
            val key = "${dataValue.dataElement}|${dataValue.categoryOptionCombo}"
            key to androidx.compose.ui.text.input.TextFieldValue(dataValue.value ?: "")
        }
        _fieldStates.value = resetStates

        savePressed = false

        Log.d("DataEntryViewModel", "Cleared current session changes, preserved existing drafts")
    }

    fun toggleGridRow(sectionName: String, rowKey: String) {
        updateState { currentState ->
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
        return _state.value.expandedGridRows[sectionName]?.contains(rowKey) == true
    }

    fun startValidationForCompletion() {
        Log.d("DataEntryViewModel", "=== COMPLETION FLOW: Starting validation for completion ===")
        startValidationWithCompletionProgress()
    }

    fun completeDatasetAfterValidation(onResult: (Boolean, String?) -> Unit) {
        val stateSnapshot = _state.value
        viewModelScope.launch {
            Log.d("DataEntryViewModel", "=== COMPLETION FLOW: Proceeding with dataset completion after validation ===")
            Log.d("DataEntryViewModel", "Dataset: ${stateSnapshot.datasetId}, Period: ${stateSnapshot.period}, OrgUnit: ${stateSnapshot.orgUnit}")
            Log.d("DataEntryViewModel", "AttributeOptionCombo: ${stateSnapshot.attributeOptionCombo}")
            updateState { it.copy(isLoading = true, error = null) }
            
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
                    updateState { it.copy(isCompleted = true, isLoading = false, validationSummary = null) }
                    onResult(true, successMessage)
                } else {
                    Log.e("DataEntryViewModel", "Failed to mark dataset as complete: ${result.exceptionOrNull()?.message}")
                    updateState {
                        it.copy(
                            isLoading = false,
                            error = result.exceptionOrNull()?.message
                        )
                    }
                    onResult(false, result.exceptionOrNull()?.message)
                }
                
            } catch (e: Exception) {
                Log.e("DataEntryViewModel", "Error during completion: ${e.message}", e)
                updateState {
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
        val stateSnapshot = _state.value
        viewModelScope.launch {
            Log.d("DataEntryViewModel", "=== COMPLETION FLOW: Marking dataset as incomplete ===")
            Log.d("DataEntryViewModel", "Dataset: ${stateSnapshot.datasetId}, Period: ${stateSnapshot.period}, OrgUnit: ${stateSnapshot.orgUnit}")
            Log.d("DataEntryViewModel", "AttributeOptionCombo: ${stateSnapshot.attributeOptionCombo}")
            updateState { it.copy(isLoading = true, error = null) }

            try {
                val result = useCases.markDatasetInstanceIncomplete(
                    stateSnapshot.datasetId,
                    stateSnapshot.period,
                    stateSnapshot.orgUnit,
                    stateSnapshot.attributeOptionCombo
                )

                if (result.isSuccess) {
                    updateState {
                        it.copy(
                            isLoading = false,
                            isCompleted = false,
                            error = null
                        )
                    }
                    onResult(true, "Dataset marked as incomplete")
                } else {
                    Log.e("DataEntryViewModel", "Failed to mark dataset incomplete: ${result.exceptionOrNull()}")
                    updateState { it.copy(isLoading = false, error = result.exceptionOrNull()?.message) }
                    onResult(false, result.exceptionOrNull()?.message)
                }
            } catch (e: Exception) {
                Log.e("DataEntryViewModel", "Exception marking dataset incomplete: ${e.message}", e)
                updateState { it.copy(isLoading = false, error = e.message) }
                onResult(false, e.message)
            }
        }
    }

    fun clearValidationResult() {
        updateState { it.copy(validationSummary = null) }
    }

    // === Enhanced Completion Workflow ===

    fun showCompletionDialog() {
        updateState { it.copy(showCompletionDialog = true) }
    }

    fun dismissCompletionDialog() {
        updateState {
            it.copy(
                showCompletionDialog = false,
                completionAction = null,
                completionProgress = null
            )
        }
    }

    fun startCompletionWorkflow(action: com.ash.simpledataentry.presentation.core.CompletionAction) {
        updateState {
            it.copy(
                completionAction = action,
                showCompletionDialog = false
            )
        }

        when (action) {
            com.ash.simpledataentry.presentation.core.CompletionAction.VALIDATE_AND_COMPLETE -> {
                startValidationWithCompletionProgress()
            }
            com.ash.simpledataentry.presentation.core.CompletionAction.COMPLETE_WITHOUT_VALIDATION -> {
                completeDirectlyWithProgress()
            }
            com.ash.simpledataentry.presentation.core.CompletionAction.RERUN_VALIDATION -> {
                validateWithoutCompletion()
            }
            com.ash.simpledataentry.presentation.core.CompletionAction.MARK_INCOMPLETE -> {
                markIncompleteWithProgress()
            }
        }
    }

    private fun startValidationWithCompletionProgress() {
        val stateSnapshot = _state.value
        viewModelScope.launch {
            try {
                // Step 1: Preparing (0-10%)
                updateState {
                    it.copy(
                        isValidating = true,
                        error = null,
                        validationSummary = null,
                        completionProgress = com.ash.simpledataentry.presentation.core.CompletionProgress(
                            phase = com.ash.simpledataentry.presentation.core.CompletionPhase.PREPARING,
                            overallPercentage = 5,
                            phaseTitle = com.ash.simpledataentry.presentation.core.CompletionPhase.PREPARING.title,
                            phaseDetail = "Setting up validation...",
                            isValidating = true
                        )
                    )
                }

                // Step 2: Validating (10-70%)
                updateState {
                    it.copy(
                        completionProgress = com.ash.simpledataentry.presentation.core.CompletionProgress(
                            phase = com.ash.simpledataentry.presentation.core.CompletionPhase.VALIDATING,
                            overallPercentage = 20,
                            phaseTitle = com.ash.simpledataentry.presentation.core.CompletionPhase.VALIDATING.title,
                            phaseDetail = "Running validation rules...",
                            isValidating = true
                        )
                    )
                }

                val validationResult = validationRepository.validateDatasetInstance(
                    datasetId = stateSnapshot.datasetId,
                    period = stateSnapshot.period,
                    organisationUnit = stateSnapshot.orgUnit,
                    attributeOptionCombo = stateSnapshot.attributeOptionCombo,
                    dataValues = stateSnapshot.dataValues,
                    forceRefresh = true
                )

                // Step 3: Processing Results (70-90%)
                updateState {
                    it.copy(
                        completionProgress = com.ash.simpledataentry.presentation.core.CompletionProgress(
                            phase = com.ash.simpledataentry.presentation.core.CompletionPhase.PROCESSING_RESULTS,
                            overallPercentage = 80,
                            phaseTitle = com.ash.simpledataentry.presentation.core.CompletionPhase.PROCESSING_RESULTS.title,
                            phaseDetail = "Analyzing validation results...",
                            isValidating = true
                        )
                    )
                }

                updateState {
                    it.copy(
                        isValidating = false,
                        validationSummary = validationResult,
                        completionProgress = null // Clear progress when validation dialog shows
                    )
                }

            } catch (e: Exception) {
                Log.e("DataEntryViewModel", "Enhanced validation failed", e)
                updateState {
                    it.copy(
                        isValidating = false,
                        error = "Validation failed: ${e.message}",
                        completionProgress = com.ash.simpledataentry.presentation.core.CompletionProgress.error(
                            e.message ?: "Validation failed"
                        )
                    )
                }
            }
        }
    }

    private fun completeDirectlyWithProgress() {
        val stateSnapshot = _state.value
        viewModelScope.launch {
            try {
                // Step 1: Preparing (0-10%)
                updateState {
                    it.copy(
                        isLoading = true,
                        error = null,
                        completionProgress = com.ash.simpledataentry.presentation.core.CompletionProgress(
                            phase = com.ash.simpledataentry.presentation.core.CompletionPhase.PREPARING,
                            overallPercentage = 10,
                            phaseTitle = com.ash.simpledataentry.presentation.core.CompletionPhase.PREPARING.title,
                            phaseDetail = "Preparing to complete dataset..."
                        )
                    )
                }

                // Step 2: Completing (10-90%)
                updateState {
                    it.copy(
                        completionProgress = com.ash.simpledataentry.presentation.core.CompletionProgress(
                            phase = com.ash.simpledataentry.presentation.core.CompletionPhase.COMPLETING,
                            overallPercentage = 50,
                            phaseTitle = com.ash.simpledataentry.presentation.core.CompletionPhase.COMPLETING.title,
                            phaseDetail = "Marking dataset as complete..."
                        )
                    )
                }

                val result = useCases.completeDatasetInstance(
                    stateSnapshot.datasetId,
                    stateSnapshot.period,
                    stateSnapshot.orgUnit,
                    stateSnapshot.attributeOptionCombo
                )

                if (result.isSuccess) {
                    // Step 3: Completed (90-100%)
                    updateState {
                        it.copy(
                            completionProgress = com.ash.simpledataentry.presentation.core.CompletionProgress(
                                phase = com.ash.simpledataentry.presentation.core.CompletionPhase.COMPLETED,
                                overallPercentage = 100,
                                phaseTitle = com.ash.simpledataentry.presentation.core.CompletionPhase.COMPLETED.title,
                                phaseDetail = "Dataset completed successfully!"
                            )
                        )
                    }

                    // Show success briefly, then clear
                    kotlinx.coroutines.delay(1500)
                    updateState {
                        it.copy(
                            isCompleted = true,
                            isLoading = false,
                            completionProgress = null,
                            successMessage = "Dataset marked as complete successfully."
                        )
                    }
                } else {
                    updateState {
                        it.copy(
                            isLoading = false,
                            error = result.exceptionOrNull()?.message ?: "Failed to complete dataset",
                            completionProgress = com.ash.simpledataentry.presentation.core.CompletionProgress.error(
                                result.exceptionOrNull()?.message ?: "Failed to complete dataset"
                            )
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e("DataEntryViewModel", "Direct completion failed", e)
                updateState {
                    it.copy(
                        isLoading = false,
                        error = "Completion failed: ${e.message}",
                        completionProgress = com.ash.simpledataentry.presentation.core.CompletionProgress.error(
                            e.message ?: "Completion failed"
                        )
                    )
                }
            }
        }
    }

    private fun validateWithoutCompletion() {
        startValidationForCompletion() // Use existing validation method
    }

    private fun markIncompleteWithProgress() {
        val stateSnapshot = _state.value
        viewModelScope.launch {
            try {
                updateState {
                    it.copy(
                        isLoading = true,
                        error = null,
                        completionProgress = com.ash.simpledataentry.presentation.core.CompletionProgress(
                            phase = com.ash.simpledataentry.presentation.core.CompletionPhase.PREPARING,
                            overallPercentage = 30,
                            phaseTitle = "Marking Incomplete",
                            phaseDetail = "Updating dataset status..."
                        )
                    )
                }

                val result = useCases.markDatasetInstanceIncomplete(
                    stateSnapshot.datasetId,
                    stateSnapshot.period,
                    stateSnapshot.orgUnit,
                    stateSnapshot.attributeOptionCombo
                )

                if (result.isSuccess) {
                    updateState {
                        it.copy(
                            isLoading = false,
                            isCompleted = false,
                            error = null,
                            completionProgress = null,
                            successMessage = "Dataset marked as incomplete successfully."
                        )
                    }
                } else {
                    updateState {
                        it.copy(
                            isLoading = false,
                            error = result.exceptionOrNull()?.message ?: "Failed to mark incomplete",
                            completionProgress = com.ash.simpledataentry.presentation.core.CompletionProgress.error(
                                result.exceptionOrNull()?.message ?: "Failed to mark incomplete"
                            )
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e("DataEntryViewModel", "Mark incomplete failed", e)
                updateState {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to mark incomplete",
                        completionProgress = com.ash.simpledataentry.presentation.core.CompletionProgress.error(
                            e.message ?: "Failed to mark incomplete"
                        )
                    )
                }
            }
        }
    }

    /**
     * Trigger program rule evaluation when field loses focus
     * Uses debouncing to avoid excessive evaluations
     */
    fun onFieldBlur(dataElementId: String) {
        // Cancel any pending evaluation
        ruleEvaluationJob?.cancel()

        // Schedule new evaluation after delay
        ruleEvaluationJob = viewModelScope.launch {
            kotlinx.coroutines.delay(ruleEvaluationDelay)
            evaluateProgramRules()
        }
    }

    /**
     * Evaluate program rules for current dataset/program
     * Note: Aggregate datasets typically don't have program rules
     * This is primarily for future tracker/event program support
     */
    private suspend fun evaluateProgramRules() {
        // TODO: Implement when extending to tracker/event programs
        // For now, this is a placeholder for aggregate datasets
        // which don't typically use program rules

        // Future implementation will:
        // 1. Get d2 instance from sessionManager
        // 2. Call programRuleEngine() for tracker/event programs
        // 3. Evaluate rules based on current form data
        // 4. Apply effects to state

        Log.d("DataEntryViewModel", "Program rule evaluation placeholder - not implemented for aggregate datasets")
    }

    /**
     * Apply program rule effects to the form state
     * Updates hidden fields, warnings, errors, calculated values, etc.
     */
    private fun applyProgramRuleEffects(effect: com.ash.simpledataentry.domain.model.ProgramRuleEffect) {
        updateState { currentState ->
            currentState.copy(
                hiddenFields = effect.hiddenFields,
                disabledFields = effect.disabledFields,
                mandatoryFields = effect.mandatoryFields,
                fieldWarnings = effect.fieldWarnings,
                fieldErrors = effect.fieldErrors,
                calculatedValues = effect.calculatedValues
            )
        }

        // Apply calculated values to actual data fields
        effect.calculatedValues.forEach { (dataElementId, value) ->
            val matchingDataValue = _state.value.dataValues.find { it.dataElement == dataElementId }
            if (matchingDataValue != null) {
                updateCurrentValue(value, dataElementId, matchingDataValue.categoryOptionCombo)
            }
        }
    }

    /**
     * Detect radio button groups from data values using name-based heuristics
     * NOTE: Ideally would use DHIS2 validation rules, but SDK doesn't expose them
     *
     * Requirements for grouping:
     * 1. Fields must share the EXACT SAME option set instance (by ID)
     * 2. Fields must have 3+ fields sharing the same option set (indicates mutual exclusivity)
     * 3. Fields must have a clear common prefix ending with space, underscore, or dash
     * 4. Common prefix must be at least 5 characters (avoid false positives like "Is", "Has", etc.)
     */
    private fun detectRadioButtonGroupsByName(
        dataValues: List<DataValue>,
        optionSets: Map<String, com.ash.simpledataentry.domain.model.OptionSet>
    ): Map<String, List<String>> {
        // Only consider fields with YES/NO option sets
        val yesNoFields = dataValues.mapNotNull { dataValue ->
            val optionSet = optionSets[dataValue.dataElement]
            if (optionSet != null && isYesNoOptionSet(optionSet)) {
                dataValue to optionSet
            } else null
        }

        Log.d("RadioGroupDetection", "Found ${yesNoFields.size} YES/NO fields to analyze for grouping")

        // Group by section first
        val radioGroups = mutableMapOf<String, MutableList<String>>()
        val fieldsBySection = yesNoFields.groupBy { it.first.sectionName }

        fieldsBySection.forEach { (sectionName, fieldsWithOptionSets) ->
            Log.d("RadioGroupDetection", "Analyzing section '$sectionName' with ${fieldsWithOptionSets.size} YES/NO fields")

            // CRITICAL: Group by option set ID first - only fields sharing the SAME option set can be grouped
            val fieldsByOptionSet = fieldsWithOptionSets.groupBy { it.second.id }

            fieldsByOptionSet.forEach { (optionSetId, fieldsInSet) ->
                // CONSERVATIVE: Require at least 3 fields sharing same option set to consider grouping
                // This indicates they're likely mutually exclusive
                if (fieldsInSet.size < 3) {
                    Log.d("RadioGroupDetection", "Skipping option set $optionSetId - only ${fieldsInSet.size} field(s), need 3+ for grouping")
                    return@forEach
                }

                Log.d("RadioGroupDetection", "Checking ${fieldsInSet.size} fields sharing option set $optionSetId for grouping")

                val fields = fieldsInSet.map { it.first }

                // Find common prefix among ALL fields in this option set
                var commonPrefix = fields[0].dataElementName
                for (field in fields.drop(1)) {
                    commonPrefix = findCommonPrefix(commonPrefix, field.dataElementName)
                }

                // CONSERVATIVE: Prefix must be at least 5 characters AND end with clear separator
                val hasValidSeparator = commonPrefix.endsWith(" ") ||
                                       commonPrefix.endsWith("_") ||
                                       commonPrefix.endsWith("-")

                if (commonPrefix.length >= 5 && hasValidSeparator) {
                    val groupTitle = commonPrefix.trim().trimEnd('-', ':', '|', '/', ' ', '_')

                    if (groupTitle.isNotEmpty()) {
                        val fieldIds = fields.map { it.dataElement }
                        radioGroups[groupTitle] = fieldIds.toMutableList()

                        Log.d("RadioGroupDetection", "✓ Created group '$groupTitle' with ${fieldIds.size} fields sharing option set $optionSetId: ${fields.map { it.dataElementName }}")
                    } else {
                        Log.d("RadioGroupDetection", "✗ Rejected group - empty title after cleanup")
                    }
                } else {
                    Log.d("RadioGroupDetection", "✗ Rejected grouping for option set $optionSetId - prefix too short (${ commonPrefix.length} chars) or no separator. Prefix: '$commonPrefix'")
                }
            }
        }

        val result = radioGroups.filter { it.value.size >= 3 }  // Final filter: groups must have 3+ members
        Log.d("RadioGroupDetection", "Final result: ${result.size} groups detected with 3+ members each")
        return result
    }

    private fun findCommonPrefix(str1: String, str2: String): String {
        var i = 0
        while (i < str1.length && i < str2.length && str1[i] == str2[i]) {
            i++
        }
        return str1.substring(0, i)
    }

    /**
     * Check if an option set is a YES/NO boolean option set
     */
    private fun isYesNoOptionSet(optionSet: com.ash.simpledataentry.domain.model.OptionSet): Boolean {
        if (optionSet.options.size != 2) return false
        val codes = optionSet.options.map { it.code.uppercase() }.toSet()
        return codes.containsAll(setOf("YES", "NO")) ||
               codes.containsAll(setOf("TRUE", "FALSE")) ||
               codes.containsAll(setOf("1", "0"))
    }

    /**
     * Extract common prefix from a list of strings
     */
    private fun extractCommonPrefix(strings: List<String>): String {
        if (strings.isEmpty()) return ""
        if (strings.size == 1) return strings[0]

        var prefix = strings[0]
        for (i in 1 until strings.size) {
            while (!strings[i].startsWith(prefix)) {
                prefix = prefix.dropLast(1)
                if (prefix.isEmpty()) return ""
            }
        }

        // Trim to last meaningful separator
        val lastSeparator = prefix.indexOfLast { it in listOf('-', ':', '|', '/', ' ') }
        if (lastSeparator > 0) {
            prefix = prefix.substring(0, lastSeparator + 1)
        }

        return prefix
    }
}
