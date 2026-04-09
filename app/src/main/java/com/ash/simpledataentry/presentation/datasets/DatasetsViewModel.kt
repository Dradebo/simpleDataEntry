package com.ash.simpledataentry.presentation.datasets

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.ash.simpledataentry.domain.model.*
import com.ash.simpledataentry.domain.useCase.FilterDatasetsUseCase
import com.ash.simpledataentry.data.sync.BackgroundSyncManager
import com.ash.simpledataentry.data.sync.DetailedSyncProgress
import com.ash.simpledataentry.data.sync.SyncStatusController
import com.ash.simpledataentry.data.sync.SyncQueueManager
import com.ash.simpledataentry.domain.useCase.GetDatasetsUseCase
import com.ash.simpledataentry.domain.useCase.LogoutUseCase
import com.ash.simpledataentry.domain.useCase.SyncDatasetsUseCase
import com.ash.simpledataentry.util.PeriodHelper
import com.ash.simpledataentry.data.sync.BackgroundDataPrefetcher
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.data.repositoryImpl.SavedAccountRepository
import com.ash.simpledataentry.presentation.core.UiState
import com.ash.simpledataentry.presentation.core.UiError
import com.ash.simpledataentry.presentation.core.LoadingOperation
import com.ash.simpledataentry.presentation.core.BackgroundOperation
import com.ash.simpledataentry.presentation.core.emitSuccess
import com.ash.simpledataentry.presentation.core.emitError
import com.ash.simpledataentry.presentation.core.emitLoading
import com.ash.simpledataentry.presentation.core.dataOr
import com.ash.simpledataentry.util.toUiError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.work.WorkInfo
import javax.inject.Inject

/**
 * Datasets data model - contains only data, no UI state flags
 */
data class DatasetsData(
    val programs: List<ProgramItem> = emptyList(),
    val filteredPrograms: List<ProgramItem> = programs,
    val currentFilter: FilterState = FilterState(),
    val currentProgramType: ProgramType = ProgramType.ALL,
    val syncMessage: String? = null
)

@HiltViewModel
class DatasetsViewModel @Inject constructor(
    private val datasetsRepository: com.ash.simpledataentry.domain.repository.DatasetsRepository,
    private val getDatasetsUseCase: GetDatasetsUseCase,
    private val syncDatasetsUseCase: SyncDatasetsUseCase,
    private val filterDatasetsUseCase: FilterDatasetsUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val backgroundSyncManager: BackgroundSyncManager,
    private val backgroundDataPrefetcher: BackgroundDataPrefetcher,
    private val sessionManager: SessionManager,
    private val savedAccountRepository: SavedAccountRepository,
    private val syncQueueManager: SyncQueueManager,
    private val syncStatusController: SyncStatusController,
    private val databaseProvider: com.ash.simpledataentry.data.DatabaseProvider,
    private val datasetInstancesRepository: com.ash.simpledataentry.domain.repository.DatasetInstancesRepository,
    private val dataEntryRepository: com.ash.simpledataentry.domain.repository.DataEntryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<DatasetsData>>(
        UiState.Success(DatasetsData())
    )
    val uiState: StateFlow<UiState<DatasetsData>> = _uiState.asStateFlow()
    val syncController: SyncStatusController = syncStatusController
    private val _activeAccountLabel = MutableStateFlow<String?>(null)
    val activeAccountLabel: StateFlow<String?> = _activeAccountLabel.asStateFlow()
    private val _activeAccountSubtitle = MutableStateFlow<String?>(null)
    val activeAccountSubtitle: StateFlow<String?> = _activeAccountSubtitle.asStateFlow()
    private val _backgroundSyncRunning = MutableStateFlow(false)
    val backgroundSyncRunning: StateFlow<Boolean> = _backgroundSyncRunning.asStateFlow()
    private val _isRefreshingAfterSync = MutableStateFlow(false)
    val isRefreshingAfterSync: StateFlow<Boolean> = _isRefreshingAfterSync.asStateFlow()
    private var wasBackgroundSyncRunning: Boolean = false

    init {
        // Account change observer - MUST come first
        // When account changes (including session restoration), reload programs from the new database
        viewModelScope.launch {
            sessionManager.currentAccountId.collect { accountId ->
                if (accountId == null) {
                    resetToInitialState()
                    _activeAccountLabel.value = null
                    _activeAccountSubtitle.value = null
                } else {
                    // Account switched or restored - reload programs from correct database
                    Log.d("DatasetsViewModel", "Account changed/restored: $accountId - reloading programs")
                    loadPrograms()
                    refreshActiveAccountLabel()
                }
            }
        }

        viewModelScope.launch {
            backgroundSyncManager.getSyncWorkInfo()
                .asFlow()
                .map { workInfos ->
                    workInfos.any { info ->
                        info.state == WorkInfo.State.RUNNING
                    }
                }
                .distinctUntilChanged()
                .collect { isRunning ->
                    _backgroundSyncRunning.value = isRunning
                    if (!isRunning && wasBackgroundSyncRunning) {
                        val currentData = getCurrentData()
                        if (currentData.syncMessage == null) {
                            _uiState.emitSuccess(
                                currentData.copy(syncMessage = "Background sync completed")
                            )
                        }
                        _isRefreshingAfterSync.value = true
                    }
                    wasBackgroundSyncRunning = isRunning
                }
        }

        // Initial load (may use fallback database if session not yet restored)
        loadPrograms()
        // Start background prefetching after programs are loaded
        backgroundDataPrefetcher.startPrefetching()

        // REMOVED: Background sync progress observer
        // Background sync after login should NOT block the UI with an overlay
        // Only user-initiated syncs (via syncDatasets() method) should show the overlay
    }

    private fun resetToInitialState() {
        _uiState.emitSuccess(DatasetsData())
    }

    private suspend fun refreshActiveAccountLabel() {
        val account = savedAccountRepository.getActiveAccount()
        _activeAccountLabel.value = account?.username
        _activeAccountSubtitle.value = account?.serverUrl
    }

    /**
     * Helper to get current data from UiState
     */
    private fun getCurrentData(): DatasetsData {
        return _uiState.value.dataOr { DatasetsData() }
    }

    fun refreshPrograms() {
        viewModelScope.launch {
            loadPrograms()
        }
    }

    fun loadPrograms() {
        viewModelScope.launch {
            _uiState.emitLoading(LoadingOperation.Initial)

            datasetsRepository.getAllPrograms()
                .catch { exception ->
                    val uiError = exception.toUiError()
                    _uiState.emitError(uiError)
                }
                .collect { programs ->
                    val wasRefreshing = _isRefreshingAfterSync.value
                    // Room Flow automatically re-emits when database changes
                    // Preserve sync message from current state
                    val currentData = getCurrentData()
                    val preservedSyncMessage = currentData.syncMessage
                    val currentFilter = currentData.currentFilter
                    val currentProgramType = currentData.currentProgramType

                    val filteredProgramsByOrgUnit = withContext(Dispatchers.IO) {
                        val allowedDatasetIds = programs
                            .filterIsInstance<ProgramItem.DatasetProgram>()
                            .mapNotNull { program ->
                                if (dataEntryRepository.isDatasetAttachedToScopedOrgUnits(program.dataset.id)) {
                                    program.dataset.id
                                } else {
                                    null
                                }
                            }
                            .toSet()

                        programs.filter { program ->
                            when (program) {
                                is ProgramItem.DatasetProgram -> program.dataset.id in allowedDatasetIds
                                else -> true
                            }
                        }
                    }

                    // Filter programs if needed
                    val filteredPrograms = filterPrograms(filteredProgramsByOrgUnit, currentFilter, currentProgramType)

                    val newData = DatasetsData(
                        programs = filteredProgramsByOrgUnit,
                        filteredPrograms = filteredPrograms,
                        currentFilter = currentFilter,
                        currentProgramType = currentProgramType,
                        syncMessage = preservedSyncMessage
                    )

                    // Check if there's an active sync operation from background
                    val currentUiState = _uiState.value
                    val backgroundOp = if (currentUiState is UiState.Success) {
                        currentUiState.backgroundOperation
                    } else null

                    _uiState.emitSuccess(newData, backgroundOperation = backgroundOp)
                    if (wasRefreshing) {
                        _isRefreshingAfterSync.value = false
                    }
                }
        }
    }

    fun syncDatasets(uploadFirst: Boolean = false) {
        val currentUiState = _uiState.value

        // Check if already syncing
        val alreadySyncing = currentUiState is UiState.Loading && currentUiState.operation is LoadingOperation.Syncing

        if (!alreadySyncing) {
            Log.d("DatasetsViewModel", "Starting enhanced sync for datasets, uploadFirst: $uploadFirst")

            viewModelScope.launch {
                try {
                    // Observe progress only for this user-initiated sync
                    val progressJob = launch {
                        syncQueueManager.detailedProgress.collect { progress ->
                            if (progress != null) {
                                _uiState.emitLoading(LoadingOperation.Syncing(progress))
                            }
                        }
                    }

                    // Use the enhanced SyncQueueManager which provides detailed progress tracking
                    val syncResult = syncQueueManager.startSync(forceSync = uploadFirst)

                    // Cancel progress observation after sync completes
                    progressJob.cancel()

                    syncResult.fold(
                        onSuccess = {
                            Log.d("DatasetsViewModel", "Enhanced sync completed successfully")
                            val message = if (uploadFirst) {
                                "Data synchronized successfully"
                            } else {
                                "Datasets synced successfully"
                            }

                            // Update data with success message
                            val currentData = getCurrentData()
                            val newData = currentData.copy(syncMessage = message)
                            _uiState.emitSuccess(newData)

                            // Reload all data after sync - this will preserve syncMessage and show updated counts
                            _isRefreshingAfterSync.value = true
                            loadPrograms()
                        },
                        onFailure = { error ->
                            Log.e("DatasetsViewModel", "Enhanced sync failed", error)
                            val uiError = error.toUiError()
                            _uiState.emitError(uiError)
                        }
                    )
                } catch (e: Exception) {
                    Log.e("DatasetsViewModel", "Enhanced sync failed", e)
                    val uiError = e.toUiError()
                    _uiState.emitError(uiError)
                }
            }
        } else {
            Log.w("DatasetsViewModel", "DATASETS SYNC: Cannot sync - already syncing")
        }
    }

    fun downloadOnlySync() {
        Log.d("DatasetsViewModel", "Starting download-only sync for datasets")

        viewModelScope.launch {
            try {
                // Show background operation indicator
                val currentData = getCurrentData()
                _uiState.emitSuccess(currentData, BackgroundOperation.Syncing)

                val syncResult = syncQueueManager.startDownloadOnlySync()
                syncResult.fold(
                    onSuccess = {
                        Log.d("DatasetsViewModel", "Download-only sync completed successfully")
                        kotlinx.coroutines.delay(100) // Ensure SDK database writes committed

                        // Update data with success message
                        val updatedData = getCurrentData().copy(syncMessage = "Latest data downloaded successfully")
                        _uiState.emitSuccess(updatedData)

                        // Reload programs
                        _isRefreshingAfterSync.value = true
                        loadPrograms()
                    },
                    onFailure = { error ->
                        Log.e("DatasetsViewModel", "Download-only sync failed", error)
                        val uiError = error.toUiError()
                        _uiState.emitError(uiError)
                    }
                )
            } catch (e: Exception) {
                Log.e("DatasetsViewModel", "Download-only sync failed", e)
                val uiError = e.toUiError()
                _uiState.emitError(uiError)
            }
        }
    }

    fun applyFilter(filterState: FilterState) {
        viewModelScope.launch {
            val currentData = getCurrentData()
            val programsWithOrgUnitCounts = computeOrgUnitFilteredPrograms(currentData.programs, filterState)
            val filteredPrograms = filterPrograms(programsWithOrgUnitCounts, filterState, currentData.currentProgramType)
            val newData = currentData.copy(
                programs = programsWithOrgUnitCounts,
                filteredPrograms = filteredPrograms,
                currentFilter = filterState
            )
            _uiState.emitSuccess(newData)
        }
    }

    fun filterByProgramType(programType: ProgramType) {
        val currentData = getCurrentData()
        val filteredPrograms = filterPrograms(currentData.programs, currentData.currentFilter, programType)
        val newData = currentData.copy(
            filteredPrograms = filteredPrograms,
            currentProgramType = programType
        )
        _uiState.emitSuccess(newData)
    }

    suspend fun getScopedOrgUnits(): List<OrganisationUnit> {
        return dataEntryRepository.getScopedOrgUnits()
    }

    suspend fun getAttachedOrgUnitIdsForDatasets(datasetIds: List<String>): Set<String> {
        return dataEntryRepository.getOrgUnitsAttachedToDataSets(datasetIds)
    }

    private suspend fun computeOrgUnitFilteredPrograms(
        programs: List<ProgramItem>,
        filterState: FilterState
    ): List<ProgramItem> {
        if (filterState.orgUnitIds.isEmpty()) {
            return programs
        }

        return withContext(Dispatchers.IO) {
            programs.mapNotNull { programItem ->
                when (programItem) {
                    is ProgramItem.DatasetProgram -> {
                        val expandedIds = filterState.orgUnitIds
                            .flatMap { selectedId ->
                                dataEntryRepository.expandOrgUnitSelection(programItem.dataset.id, selectedId).toList()
                            }
                            .toSet()

                        val count = if (expandedIds.isEmpty()) {
                            0
                        } else {
                            datasetInstancesRepository.getDatasetInstanceCount(programItem.dataset.id, expandedIds)
                        }

                        val updatedItem = programItem.copy(
                            dataset = programItem.dataset.copy(instanceCount = count)
                        )

                        if (count > 0) updatedItem else null
                    }
                    else -> programItem
                }
            }
        }
    }

    private fun filterPrograms(programs: List<ProgramItem>, filterState: FilterState, programType: ProgramType): List<ProgramItem> {
        // First filter by program type
        val typeFiltered = if (programType == ProgramType.ALL) {
            programs
        } else {
            programs.filter { it.programType == programType }
        }

        // Apply search filter
        val searchFiltered = if (filterState.searchQuery.isBlank()) {
            typeFiltered
        } else {
            typeFiltered.filter { program ->
                program.name.contains(filterState.searchQuery, ignoreCase = true) ||
                program.description?.contains(filterState.searchQuery, ignoreCase = true) == true
            }
        }

        // Period filter - for datasets only, simplified implementation
        val periodFiltered = if (filterState.periodType == PeriodFilterType.ALL) {
            searchFiltered
        } else {
            // For now, we assume all programs can be filtered by any period
            // In a real implementation, you would check period compatibility
            searchFiltered
        }

        // Sync status filter - simplified implementation
        val syncFiltered = when (filterState.syncStatus) {
            SyncStatus.ALL -> periodFiltered
            // In a real scenario, you would check the sync status of the program instances
            else -> periodFiltered
        }

        // Apply sorting
        return sortPrograms(syncFiltered, filterState.sortBy, filterState.sortOrder)
    }

    private fun sortPrograms(programs: List<ProgramItem>, sortBy: SortBy, sortOrder: SortOrder): List<ProgramItem> {
        val sorted = when (sortBy) {
            SortBy.NAME -> programs.sortedBy { it.name.lowercase() }
            SortBy.CREATED_DATE -> programs.sortedBy { it.id } // Use ID as fallback since no created date field
            SortBy.ENTRY_COUNT -> programs.sortedBy { it.instanceCount }
        }

        return if (sortOrder == SortOrder.DESCENDING) sorted.reversed() else sorted
    }

    fun clearFilters() {
        val currentData = getCurrentData()
        val newData = currentData.copy(
            filteredPrograms = currentData.programs,
            currentFilter = FilterState(),
            currentProgramType = ProgramType.ALL
        )
        _uiState.emitSuccess(newData)
    }

    fun clearSyncMessage() {
        val currentData = getCurrentData()
        val newData = currentData.copy(syncMessage = null)
        _uiState.emitSuccess(newData)
    }

    fun dismissSyncOverlay() {
        // Clear error state in SyncQueueManager to prevent persistent dialogs
        syncQueueManager.clearErrorState()

        // Convert back to success state
        val currentUiState = _uiState.value
            if (currentUiState is UiState.Loading || currentUiState is UiState.Error) {
                val data = when (currentUiState) {
                    is UiState.Loading -> getCurrentData()
                    is UiState.Error -> currentUiState.previousData ?: DatasetsData()
                    else -> getCurrentData()
                }
                _uiState.emitSuccess(data)
            }
        }


    fun logout() {
        viewModelScope.launch {
            try {
                // Stop background prefetching and clear caches
                backgroundDataPrefetcher.clearAllCaches()
                logoutUseCase()
            } catch (e: Exception) {
                val uiError = e.toUiError()
                _uiState.emitError(uiError)
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
                sessionManager.wipeAllData(context, databaseProvider.getCurrentDatabase())

                // Logout from current session
                logoutUseCase()
            } catch (e: Exception) {
                val uiError = e.toUiError()
                _uiState.emitError(uiError)
            }
        }
    }

}
