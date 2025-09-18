package com.ash.simpledataentry.presentation.datasets

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ash.simpledataentry.domain.model.Dataset
import com.ash.simpledataentry.domain.model.FilterState
import com.ash.simpledataentry.domain.model.PeriodFilterType
import com.ash.simpledataentry.domain.model.SortBy
import com.ash.simpledataentry.domain.model.SortOrder
import com.ash.simpledataentry.domain.model.SyncStatus
import com.ash.simpledataentry.domain.useCase.FilterDatasetsUseCase
import com.ash.simpledataentry.data.sync.BackgroundSyncManager
import com.ash.simpledataentry.domain.useCase.GetDatasetsUseCase
import com.ash.simpledataentry.domain.useCase.LogoutUseCase
import com.ash.simpledataentry.util.PeriodHelper
import com.ash.simpledataentry.data.sync.BackgroundDataPrefetcher
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.data.repositoryImpl.SavedAccountRepository
import com.ash.simpledataentry.data.sync.SyncQueueManager
import com.ash.simpledataentry.data.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class DatasetsUiState {
    data object Loading : DatasetsUiState()
    data class Error(val message: String) : DatasetsUiState()
    data class Success(
        val datasets: List<Dataset>,
        val filteredDatasets: List<Dataset> = datasets,
        val currentFilter: FilterState = FilterState()
    ) : DatasetsUiState()
}

data class CombinedDatasetsState(
    val uiState: DatasetsUiState,
    val syncState: SyncState
)

@HiltViewModel
class DatasetsViewModel @Inject constructor(
    private val getDatasetsUseCase: GetDatasetsUseCase,
    private val filterDatasetsUseCase: FilterDatasetsUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val backgroundSyncManager: BackgroundSyncManager,
    private val backgroundDataPrefetcher: BackgroundDataPrefetcher,
    private val sessionManager: SessionManager,
    private val savedAccountRepository: SavedAccountRepository,
    private val syncQueueManager: SyncQueueManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<DatasetsUiState>(DatasetsUiState.Loading)

    val combinedState: StateFlow<CombinedDatasetsState> = _uiState
        .combine(syncQueueManager.syncState) { ui, sync ->
            CombinedDatasetsState(ui, sync)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CombinedDatasetsState(DatasetsUiState.Loading, SyncState())
        )

    init {
        loadDatasets()
        backgroundDataPrefetcher.startPrefetching()
    }

    fun loadDatasets() {
        viewModelScope.launch {
            _uiState.value = DatasetsUiState.Loading
            getDatasetsUseCase()
                .catch { exception ->
                    _uiState.value = DatasetsUiState.Error(
                        message = exception.message ?: "Failed to load datasets"
                    )
                }
                .collect { datasets ->
                    _uiState.value = DatasetsUiState.Success(
                        datasets = datasets,
                        filteredDatasets = datasets
                    )
                }
        }
    }

    fun syncDatasets() {
        // Trigger the sync, state is handled by observing the SyncQueueManager
        backgroundSyncManager.triggerImmediateSync()
    }

    fun applyFilter(filterState: FilterState) {
        val currentState = _uiState.value
        if (currentState is DatasetsUiState.Success) {
            val filteredDatasets = filterDatasets(currentState.datasets, filterState)
            _uiState.value = currentState.copy(
                filteredDatasets = filteredDatasets,
                currentFilter = filterState
            )
        }
    }

    private fun filterDatasets(datasets: List<Dataset>, filterState: FilterState): List<Dataset> {
        val periodHelper = PeriodHelper()
        val periodIds = when (filterState.periodType) {
            PeriodFilterType.RELATIVE -> filterState.relativePeriod?.let {
                periodHelper.getPeriodIds(it)
            } ?: emptyList()
            PeriodFilterType.CUSTOM_RANGE -> {
                if (filterState.customFromDate != null && filterState.customToDate != null) {
                    periodHelper.getPeriodIds(filterState.customFromDate, filterState.customToDate)
                } else emptyList()
            }
            else -> emptyList()
        }

        val filteredDatasets = datasets.filter { dataset ->
            val matchesSearch = if (filterState.searchQuery.isBlank()) {
                true
            } else {
                dataset.name.contains(filterState.searchQuery, ignoreCase = true) ||
                dataset.description?.contains(filterState.searchQuery, ignoreCase = true) == true
            }
            val matchesPeriod = if (filterState.periodType == PeriodFilterType.ALL) {
                true
            } else {
                true
            }
            val matchesSyncStatus = when (filterState.syncStatus) {
                SyncStatus.ALL -> true
                else -> true
            }
            matchesSearch && matchesPeriod && matchesSyncStatus
        }
        return sortDatasets(filteredDatasets, filterState.sortBy, filterState.sortOrder)
    }

    private fun sortDatasets(datasets: List<Dataset>, sortBy: SortBy, sortOrder: SortOrder): List<Dataset> {
        val sorted = when (sortBy) {
            SortBy.NAME -> datasets.sortedBy { it.name.lowercase() }
            SortBy.CREATED_DATE -> datasets.sortedBy { it.id }
            SortBy.ENTRY_COUNT -> datasets.sortedBy { it.instanceCount }
        }
        return if (sortOrder == SortOrder.DESCENDING) sorted.reversed() else sorted
    }

    fun clearFilters() {
        val currentState = _uiState.value
        if (currentState is DatasetsUiState.Success) {
            _uiState.value = currentState.copy(
                filteredDatasets = currentState.datasets,
                currentFilter = FilterState()
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                backgroundDataPrefetcher.clearAllCaches()
                logoutUseCase()
            } catch (e: Exception) {
                _uiState.value = DatasetsUiState.Error(
                    message = "Failed to logout: ${e.message}"
                )
            }
        }
    }

    fun deleteAccount(context: android.content.Context) {
        viewModelScope.launch {
            try {
                backgroundDataPrefetcher.clearAllCaches()
                savedAccountRepository.deleteAllAccounts()
                sessionManager.wipeAllData(context)
                logoutUseCase()
            } catch (e: Exception) {
                _uiState.value = DatasetsUiState.Error(
                    message = "Failed to delete account: ${e.message}"
                )
            }
        }
    }
}
