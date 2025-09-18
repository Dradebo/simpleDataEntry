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
import com.ash.simpledataentry.domain.useCase.SyncDatasetsUseCase
import com.ash.simpledataentry.util.PeriodHelper
import com.ash.simpledataentry.data.sync.BackgroundDataPrefetcher
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.data.repositoryImpl.SavedAccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import androidx.work.WorkInfo
import javax.inject.Inject
import androidx.lifecycle.asFlow

sealed class DatasetsState {
    data object Loading : DatasetsState()
    data class Error(val message: String) : DatasetsState()
    data class Success(
        val datasets: List<Dataset>,
        val filteredDatasets: List<Dataset> = datasets,
        val currentFilter: FilterState = FilterState(),
        val isSyncing: Boolean = false,
        val syncMessage: String? = null,
        val syncProgress: Int = 0,
        val syncStep: String? = null
    ) : DatasetsState()
}
@HiltViewModel
class DatasetsViewModel @Inject constructor(
    private val getDatasetsUseCase: GetDatasetsUseCase,
    private val syncDatasetsUseCase: SyncDatasetsUseCase,
    private val filterDatasetsUseCase: FilterDatasetsUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val backgroundSyncManager: BackgroundSyncManager,
    private val backgroundDataPrefetcher: BackgroundDataPrefetcher,
    private val sessionManager: SessionManager,
    private val savedAccountRepository: SavedAccountRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<DatasetsState>(DatasetsState.Loading)
    val uiState: StateFlow<DatasetsState> = _uiState.asStateFlow()

    init {
        loadDatasets()
        // Start background prefetching after datasets are loaded
        backgroundDataPrefetcher.startPrefetching()
    }

    fun loadDatasets() {
        viewModelScope.launch {
            _uiState.value = DatasetsState.Loading
            getDatasetsUseCase()
                .catch { exception ->
                    _uiState.value = DatasetsState.Error(
                        message = exception.message ?: "Failed to load datasets"
                    )
                }
                .collect { datasets ->
                    val currentState = _uiState.value
                    val wasSyncing = (currentState as? DatasetsState.Success)?.isSyncing == true
                    _uiState.value = DatasetsState.Success(
                        datasets = datasets,
                        filteredDatasets = datasets,
                        isSyncing = false,
                        syncMessage = if (wasSyncing) "Sync completed successfully!" else null,
                        syncProgress = 0,
                        syncStep = null
                    )
                }
        }
    }

    fun syncDatasets() {
        val currentState = _uiState.value
        if (currentState is DatasetsState.Success && !currentState.isSyncing) {
            _uiState.value = currentState.copy(isSyncing = true, syncProgress = 0, syncStep = "Starting sync...")
            val workName = backgroundSyncManager.triggerImmediateSync()
            Log.d("DatasetsViewModel", "=== DATASETS SYNC: WorkManager sync triggered with work name: $workName ===")

            viewModelScope.launch {
                backgroundSyncManager.getImmediateSyncWorkInfo(workName).asFlow().collect { workInfos ->
                    val workInfo = workInfos.firstOrNull() ?: return@collect
                    val progressData = workInfo.progress
                    val currentStep = progressData.getString("step") ?: "Syncing..."
                    val progress = progressData.getInt("progress", 0)

                    when (workInfo.state) {
                        WorkInfo.State.RUNNING -> {
                            Log.d("DatasetsViewModel", "Sync Progress: $progress%, Step: $currentStep")
                            val state = _uiState.value
                            if (state is DatasetsState.Success) {
                                _uiState.value = state.copy(
                                    isSyncing = true,
                                    syncProgress = progress,
                                    syncStep = currentStep
                                )
                            }
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            Log.d("DatasetsViewModel", "Sync SUCCEEDED")
                            val state = _uiState.value
                            if (state is DatasetsState.Success) {
                                _uiState.value = state.copy(
                                    isSyncing = false,
                                    syncMessage = "Sync completed successfully!",
                                    syncProgress = 100,
                                    syncStep = "Done"
                                )
                            }
                            loadDatasets() // Refresh data after successful sync
                        }
                        WorkInfo.State.FAILED -> {
                            val errorMessage = workInfo.outputData.getString("error") ?: "Sync failed"
                            Log.e("DatasetsViewModel", "Sync FAILED: $errorMessage")
                            val state = _uiState.value
                            if (state is DatasetsState.Success) {
                                _uiState.value = state.copy(
                                    isSyncing = false,
                                    syncMessage = "Error: $errorMessage"
                                )
                            }
                        }
                        WorkInfo.State.CANCELLED -> {
                            Log.w("DatasetsViewModel", "Sync CANCELLED")
                            val state = _uiState.value
                            if (state is DatasetsState.Success) {
                                _uiState.value = state.copy(
                                    isSyncing = false,
                                    syncMessage = "Sync was cancelled"
                                )
                            }
                        }
                        else -> {
                            // ENQUEUED, BLOCKED
                            Log.d("DatasetsViewModel", "Sync status: ${workInfo.state}")
                        }
                    }
                }
            }
        } else {
            Log.w("DatasetsViewModel", "DATASETS SYNC: Cannot sync - already syncing or state is not Success")
        }
    }

    fun applyFilter(filterState: FilterState) {
        val currentState = _uiState.value
        if (currentState is DatasetsState.Success) {
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
            // Search query filter
            val matchesSearch = if (filterState.searchQuery.isBlank()) {
                true
            } else {
                dataset.name.contains(filterState.searchQuery, ignoreCase = true) ||
                dataset.description?.contains(filterState.searchQuery, ignoreCase = true) == true
            }

            // Period filter
            val matchesPeriod = if (filterState.periodType == PeriodFilterType.ALL) {
                true
            } else {
                // This is a simplified check. A real implementation would need to check
                // if the dataset's period type is compatible with the selected periods.
                // For now, we assume all datasets can be filtered by any period.
                true
            }

            // Sync status filter
            val matchesSyncStatus = when (filterState.syncStatus) {
                SyncStatus.ALL -> true
                // In a real scenario, you would check the sync status of the dataset instances
                // related to this dataset. For now, we assume this information is not available
                // at the dataset level and we will not filter by sync status.
                else -> true
            }

            matchesSearch && matchesPeriod && matchesSyncStatus
        }

        // Apply sorting
        return sortDatasets(filteredDatasets, filterState.sortBy, filterState.sortOrder)
    }

    private fun sortDatasets(datasets: List<Dataset>, sortBy: SortBy, sortOrder: SortOrder): List<Dataset> {
        val sorted = when (sortBy) {
            SortBy.NAME -> datasets.sortedBy { it.name.lowercase() }
            SortBy.CREATED_DATE -> datasets.sortedBy { it.id } // Use ID as fallback since no created date field
            SortBy.ENTRY_COUNT -> datasets.sortedBy { it.instanceCount }
        }

        return if (sortOrder == SortOrder.DESCENDING) sorted.reversed() else sorted
    }

    fun clearFilters() {
        val currentState = _uiState.value
        if (currentState is DatasetsState.Success) {
            _uiState.value = currentState.copy(
                filteredDatasets = currentState.datasets,
                currentFilter = FilterState()
            )
        }
    }

    fun clearSyncMessage() {
        val currentState = _uiState.value
        if (currentState is DatasetsState.Success) {
            _uiState.value = currentState.copy(syncMessage = null)
        }
    }


    fun logout() {
        viewModelScope.launch {
            try {
                // Stop background prefetching and clear caches
                backgroundDataPrefetcher.clearAllCaches()
                logoutUseCase()

            } catch (e: Exception) {
                _uiState.value = DatasetsState.Error(
                    message = "Failed to logout: ${e.message}"
                )
            }
        }
    }

    fun deleteAccount(context: android.content.Context) {
        viewModelScope.launch {
            try {
                // Stop background prefetching and clear caches
                backgroundDataPrefetcher.clearAllCaches()
                
                // Delete all saved accounts
                savedAccountRepository.deleteAllAccounts()
                
                // Wipe all DHIS2 data and local data
                sessionManager.wipeAllData(context)
                
                // Logout from current session
                logoutUseCase()
                
            } catch (e: Exception) {
                _uiState.value = DatasetsState.Error(
                    message = "Failed to delete account: ${e.message}"
                )
            }
        }
    }

}
