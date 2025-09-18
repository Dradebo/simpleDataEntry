package com.ash.simpledataentry.presentation.datasetInstances

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ash.simpledataentry.domain.model.CompletionStatus
import com.ash.simpledataentry.domain.model.DatasetInstance
import com.ash.simpledataentry.domain.model.DatasetInstanceFilterState
import com.ash.simpledataentry.domain.model.InstanceSortBy
import com.ash.simpledataentry.domain.model.PeriodFilterType
import com.ash.simpledataentry.domain.model.DatasetInstanceState
import com.ash.simpledataentry.domain.model.SortOrder
import com.ash.simpledataentry.domain.model.SyncStatus
import com.ash.simpledataentry.domain.useCase.GetDatasetInstancesUseCase
import com.ash.simpledataentry.domain.repository.DataEntryRepository
import com.ash.simpledataentry.domain.repository.DatasetInstancesRepository
import com.ash.simpledataentry.util.PeriodHelper
import com.ash.simpledataentry.data.local.DataValueDraftDao
import com.ash.simpledataentry.data.sync.SyncQueueManager
import com.ash.simpledataentry.data.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DatasetInstancesUiState(
    val instances: List<DatasetInstance> = emptyList(),
    val filteredInstances: List<DatasetInstance> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    val attributeOptionCombos: List<Pair<String, String>> = emptyList(),
    val dataset: com.ash.simpledataentry.domain.model.Dataset? = null,
    val localInstanceCount: Int = 0,
    val instancesWithDrafts: Set<String> = emptySet()
)

data class CombinedDatasetInstancesState(
    val uiState: DatasetInstancesUiState,
    val syncState: SyncState
)

@HiltViewModel
class DatasetInstancesViewModel @Inject constructor(
    private val getDatasetInstancesUseCase: GetDatasetInstancesUseCase,
    private val dataEntryRepository: DataEntryRepository,
    private val datasetInstacesRepository: DatasetInstancesRepository,
    private val datasetsRepository: com.ash.simpledataentry.domain.repository.DatasetsRepository,
    private val draftDao: DataValueDraftDao,
    private val syncQueueManager: SyncQueueManager,
    private val app: Application
) : ViewModel() {
    private var datasetId: String = ""

    private val _uiState = MutableStateFlow(DatasetInstancesUiState())

    val combinedState: StateFlow<CombinedDatasetInstancesState> = _uiState
        .combine(syncQueueManager.syncState) { ui, sync ->
            CombinedDatasetInstancesState(ui, sync)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CombinedDatasetInstancesState(DatasetInstancesUiState(), SyncState())
        )

    private val _bulkCompletionMode = MutableStateFlow(false)
    val bulkCompletionMode: StateFlow<Boolean> = _bulkCompletionMode.asStateFlow()

    private val _selectedInstances = MutableStateFlow<Set<String>>(emptySet())
    val selectedInstances: StateFlow<Set<String>> = _selectedInstances.asStateFlow()

    private val _filterState = MutableStateFlow(DatasetInstanceFilterState())
    val filterState: StateFlow<DatasetInstanceFilterState> = _filterState.asStateFlow()

    private fun createInstanceKey(datasetId: String, period: String, orgUnit: String, attributeOptionCombo: String): String {
        return "$datasetId|$period|$orgUnit|$attributeOptionCombo"
    }

    fun setDatasetId(id: String) {
        if (id.isNotEmpty() && id != datasetId) {
            datasetId = id
            Log.d("DatasetInstancesVM", "Initializing ViewModel with dataset: $datasetId")
            loadData()
        }
    }

    fun refreshData() {
        Log.d("DatasetInstancesVM", "Refreshing dataset instances data")
        loadData()
    }

    private fun loadData() {
        if (datasetId.isEmpty()) {
            Log.e("DatasetInstancesVM", "Cannot load data: datasetId is empty")
            return
        }

        Log.d("DatasetInstancesVM", "Loading dataset data for ID: $datasetId")
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                Log.d("DatasetInstancesVM", "Fetching dataset instances")
                val attributeOptionCombos = dataEntryRepository.getAttributeOptionCombos(datasetId)
                Log.d("DatasetInstancesVM", "getAttributeOptionCombos returned: $attributeOptionCombos")
                
                var dataset: com.ash.simpledataentry.domain.model.Dataset? = null
                datasetsRepository.getDatasets().collect { datasets ->
                    dataset = datasets.find { it.id == datasetId }
                }
                
                val instancesResult = getDatasetInstancesUseCase(datasetId)
                instancesResult.fold(
                    onSuccess = { instances ->
                        Log.d("DatasetInstancesVM", "Received "+instances.size+" instances")

                        val instancesWithDrafts = mutableSetOf<String>()
                        for (instance in instances) {
                            val draftCount = draftDao.getDraftCountForInstance(
                                datasetId = instance.datasetId,
                                period = instance.period.id,
                                orgUnit = instance.organisationUnit.id,
                                attributeOptionCombo = instance.attributeOptionCombo
                            )
                            if (draftCount > 0) {
                                val instanceKey = createInstanceKey(
                                    instance.datasetId,
                                    instance.period.id,
                                    instance.organisationUnit.id,
                                    instance.attributeOptionCombo
                                )
                                instancesWithDrafts.add(instanceKey)
                            }
                        }

                        Log.d("DatasetInstancesVM", "Found ${instancesWithDrafts.size} instances with local draft values")

                        _uiState.value = _uiState.value.copy(
                            instances = instances,
                            filteredInstances = applyFilters(orderInstances(instances)),
                            isLoading = false,
                            error = null,
                            attributeOptionCombos = attributeOptionCombos,
                            dataset = dataset,
                            localInstanceCount = instancesWithDrafts.size,
                            instancesWithDrafts = instancesWithDrafts
                        )
                    },
                    onFailure = { error ->
                        Log.e("DatasetInstancesVM", "Error loading data", error)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = error.message ?: "Failed to load dataset data",
                            attributeOptionCombos = attributeOptionCombos,
                            dataset = dataset,
                            localInstanceCount = 0
                        )
                    }
                )
            } catch (e: Exception) {
                Log.e("DatasetInstancesVM", "Error loading data", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load dataset data"
                )
            }
        }
    }

    fun syncDatasetInstances() {
        viewModelScope.launch {
            syncQueueManager.startSync()
        }
    }

    fun manualRefresh() {
        loadData()
    }

    fun toggleBulkCompletionMode() {
        _bulkCompletionMode.value = !_bulkCompletionMode.value
        if (!_bulkCompletionMode.value) {
            _selectedInstances.value = emptySet()
        }
    }

    fun toggleInstanceSelection(uid: String) {
        _selectedInstances.value = _selectedInstances.value.toMutableSet().apply {
            if (contains(uid)) remove(uid) else add(uid)
        }
    }

    fun clearBulkSelection() {
        _selectedInstances.value = emptySet()
        _bulkCompletionMode.value = false
    }

    fun bulkCompleteSelectedInstances(onResult: (Boolean, String?) -> Unit) {
        val selected = _selectedInstances.value
        val allInstances = _uiState.value.instances.associateBy { it.id }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            var anyError: String? = null
            for ((uid, instance) in allInstances) {
                val shouldBeComplete = selected.contains(uid) || instance.state == com.ash.simpledataentry.domain.model.DatasetInstanceState.COMPLETE
                val isComplete = instance.state == com.ash.simpledataentry.domain.model.DatasetInstanceState.COMPLETE
                val result: Result<Unit>? = when {
                    shouldBeComplete && !isComplete -> datasetInstacesRepository.completeDatasetInstance(
                        datasetId = instance.datasetId,
                        period = instance.period.id,
                        orgUnit = instance.organisationUnit.id,
                        attributeOptionCombo = instance.attributeOptionCombo
                    )
                    !shouldBeComplete && isComplete -> datasetInstacesRepository.markDatasetInstanceIncomplete(
                        datasetId = instance.datasetId,
                        period = instance.period.id,
                        orgUnit = instance.organisationUnit.id,
                        attributeOptionCombo = instance.attributeOptionCombo
                    )
                    else -> null // No action needed
                }
                if (result != null && result.isFailure) {
                    anyError = result.exceptionOrNull()?.message ?: "Unknown error"
                    break
                }
            }
            _uiState.value = _uiState.value.copy(isLoading = false)
            if (anyError != null) {
                onResult(false, anyError)
            } else {
                onResult(true, null)
            }
        }
    }

    fun markDatasetInstanceIncomplete(uid: String, onResult: (Boolean, String?) -> Unit) {
        val allInstances = _uiState.value.instances.associateBy { it.id }
        val instance = allInstances[uid]
        if (instance != null) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                val result = datasetInstacesRepository.markDatasetInstanceIncomplete(
                    datasetId = instance.datasetId,
                    period = instance.period.id,
                    orgUnit = instance.organisationUnit.id,
                    attributeOptionCombo = instance.attributeOptionCombo
                )
                _uiState.value = _uiState.value.copy(isLoading = false)
                if (result.isFailure) {
                    onResult(false, result.exceptionOrNull()?.message ?: "Unknown error")
                } else {
                    onResult(true, null)
                }
            }
        }
    }

    fun bulkMarkInstancesIncomplete(uids: Set<String>, onResult: (Boolean, String?) -> Unit) {
        val allInstances = _uiState.value.instances.associateBy { it.id }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            var anyError: String? = null
            for (uid in uids) {
                val instance = allInstances[uid]
                if (instance != null) {
                    val result = datasetInstacesRepository.markDatasetInstanceIncomplete(
                        datasetId = instance.datasetId,
                        period = instance.period.id,
                        orgUnit = instance.organisationUnit.id,
                        attributeOptionCombo = instance.attributeOptionCombo
                    )
                    if (result.isFailure) {
                        anyError = result.exceptionOrNull()?.message ?: "Unknown error"
                        break
                    }
                }
            }
            _uiState.value = _uiState.value.copy(isLoading = false)
            if (anyError != null) {
                onResult(false, anyError)
            } else {
                onResult(true, null)
            }
        }
    }

    fun markDatasetInstanceComplete(uid: String, onResult: (Boolean, String?) -> Unit) {
        val allInstances = _uiState.value.instances.associateBy { it.id }
        val instance = allInstances[uid]
        if (instance != null) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                val result = datasetInstacesRepository.completeDatasetInstance(
                    datasetId = instance.datasetId,
                    period = instance.period.id,
                    orgUnit = instance.organisationUnit.id,
                    attributeOptionCombo = instance.attributeOptionCombo
                )
                _uiState.value = _uiState.value.copy(isLoading = false)
                if (result.isFailure) {
                    onResult(false, result.exceptionOrNull()?.message ?: "Unknown error")
                } else {
                    onResult(true, null)
                }
            }
        }
    }

    fun updateFilterState(newFilterState: DatasetInstanceFilterState) {
        _filterState.value = newFilterState
        val currentInstances = _uiState.value.instances
        _uiState.value = _uiState.value.copy(
            filteredInstances = applyFilters(orderInstances(currentInstances))
        )
    }

    private fun applyFilters(instances: List<DatasetInstance>): List<DatasetInstance> {
        val filter = _filterState.value
        val periodHelper = PeriodHelper()

        val periodIds = when (filter.periodType) {
            PeriodFilterType.RELATIVE -> filter.relativePeriod?.let {
                periodHelper.getPeriodIds(it)
            } ?: emptyList()
            PeriodFilterType.CUSTOM_RANGE -> {
                if (filter.customFromDate != null && filter.customToDate != null) {
                    periodHelper.getPeriodIds(filter.customFromDate, filter.customToDate)
                } else emptyList()
            }
            else -> emptyList()
        }

        return instances.filter { instance ->
            val periodMatches = if (filter.periodType == PeriodFilterType.ALL) true else instance.period.id in periodIds

            val instanceKey = createInstanceKey(
                instance.datasetId,
                instance.period.id,
                instance.organisationUnit.id,
                instance.attributeOptionCombo
            )
            val hasDraftValues = _uiState.value.instancesWithDrafts.contains(instanceKey)

            val syncStatusMatches = when (filter.syncStatus) {
                SyncStatus.ALL -> true
                SyncStatus.SYNCED -> !hasDraftValues
                SyncStatus.NOT_SYNCED -> hasDraftValues
            }
            val completionMatches = when (filter.completionStatus) {
                CompletionStatus.ALL -> true
                CompletionStatus.COMPLETE -> instance.state == DatasetInstanceState.COMPLETE
                CompletionStatus.INCOMPLETE -> instance.state == DatasetInstanceState.OPEN
            }
            val attributeOptionComboMatches = if (filter.attributeOptionCombo == null) {
                true
            } else {
                instance.attributeOptionCombo == filter.attributeOptionCombo
            }
            val searchMatches = if (filter.searchQuery.isBlank()) {
                true
            } else {
                val query = filter.searchQuery.lowercase()
                instance.organisationUnit.name.lowercase().contains(query) ||
                        instance.period.id.lowercase().contains(query)
            }
            periodMatches && syncStatusMatches && completionMatches && attributeOptionComboMatches && searchMatches
        }
    }

    fun clearFilters() {
        _filterState.value = DatasetInstanceFilterState()
        val currentInstances = _uiState.value.instances
        _uiState.value = _uiState.value.copy(
            filteredInstances = orderInstances(currentInstances)
        )
    }

    private fun orderInstances(instances: List<DatasetInstance>): List<DatasetInstance> {
        val filter = _filterState.value

        val sorted = when (filter.sortBy) {
            InstanceSortBy.ORGANISATION_UNIT -> instances.sortedBy { it.organisationUnit.name.lowercase() }
            InstanceSortBy.PERIOD -> instances.sortedWith(
                compareBy<DatasetInstance> { parseDhis2PeriodToDate(it.period.id)?.time ?: 0L }
                    .thenBy { it.attributeOptionCombo }
            )
            InstanceSortBy.LAST_UPDATED -> instances.sortedBy {
                it.lastUpdated?.toString()?.let { dateStr ->
                    try {
                        java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", java.util.Locale.ENGLISH).parse(dateStr)?.time
                    } catch (e: Exception) { 0L }
                } ?: 0L
            }
            InstanceSortBy.COMPLETION_STATUS -> instances.sortedBy { it.state.ordinal }
        }

        return if (filter.sortOrder == SortOrder.DESCENDING) sorted.reversed() else sorted
    }

    private fun parseDhis2PeriodToDate(periodId: String): java.util.Date? {
        return try {
            when {
                Regex("^\\d{4}$").matches(periodId) -> {
                    java.text.SimpleDateFormat("yyyy", java.util.Locale.ENGLISH).parse(periodId)
                }
                Regex("^\\d{6}$").matches(periodId) -> {
                    java.text.SimpleDateFormat("yyyyMM", java.util.Locale.ENGLISH).parse(periodId)
                }
                Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(periodId) -> {
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).parse(periodId)
                }
                Regex("^\\d{4}W\\d{1,2}$").matches(periodId) -> {
                    val year = periodId.substring(0, 4).toInt()
                    val week = periodId.substring(5).toInt()
                    val cal = java.util.Calendar.getInstance(java.util.Locale.ENGLISH)
                    cal.clear()
                    cal.set(java.util.Calendar.YEAR, year)
                    cal.set(java.util.Calendar.WEEK_OF_YEAR, week)
                    cal.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
                    cal.time
                }
                Regex("^\\d{4}Q[1-4]$").matches(periodId) -> {
                    val year = periodId.substring(0, 4).toInt()
                    val quarter = periodId.substring(5).toInt()
                    val month = (quarter - 1) * 3
                    val cal = java.util.Calendar.getInstance(java.util.Locale.ENGLISH)
                    cal.clear()
                    cal.set(java.util.Calendar.YEAR, year)
                    cal.set(java.util.Calendar.MONTH, month)
                    cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                    cal.time
                }
                Regex("^\\d{4}S[1-2]$").matches(periodId) -> {
                    val year = periodId.substring(0, 4).toInt()
                    val semester = periodId.substring(5).toInt()
                    val month = if (semester == 1) 0 else 6
                    val cal = java.util.Calendar.getInstance(java.util.Locale.ENGLISH)
                    cal.clear()
                    cal.set(java.util.Calendar.YEAR, year)
                    cal.set(java.util.Calendar.MONTH, month)
                    cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                    cal.time
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
