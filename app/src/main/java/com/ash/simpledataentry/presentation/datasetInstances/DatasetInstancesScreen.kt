package com.ash.simpledataentry.presentation.datasetInstances

import android.annotation.SuppressLint
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ash.simpledataentry.domain.model.DatasetInstance
import com.ash.simpledataentry.domain.model.DatasetInstanceFilterState
import com.ash.simpledataentry.domain.model.InstanceSortBy
import com.ash.simpledataentry.domain.model.SortOrder
import com.ash.simpledataentry.domain.model.SyncStatus
import com.ash.simpledataentry.domain.model.CompletionStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import com.ash.simpledataentry.domain.model.DatasetInstanceState
import com.ash.simpledataentry.presentation.core.BaseScreen
import com.ash.simpledataentry.presentation.core.FullScreenLoader
import com.ash.simpledataentry.presentation.core.OverlayLoader
import com.ash.simpledataentry.presentation.core.SeamlessFilterPanel
import com.ash.simpledataentry.presentation.core.EnhancedDatasetInstanceCard
import com.ash.simpledataentry.presentation.core.EnhancedDatasetInstanceCardWithCheckbox
import com.ash.simpledataentry.presentation.core.AppDesignSystem
import org.hisp.dhis.android.core.dataset.DataSetInstance
import org.hisp.dhis.mobile.ui.designsystem.component.AdditionalInfoItem
import org.hisp.dhis.mobile.ui.designsystem.component.ListCard
import org.hisp.dhis.mobile.ui.designsystem.component.ListCardDescriptionModel
import org.hisp.dhis.mobile.ui.designsystem.component.ListCardTitleModel
import org.hisp.dhis.mobile.ui.designsystem.component.state.rememberAdditionalInfoColumnState
import org.hisp.dhis.mobile.ui.designsystem.component.state.rememberListCardState
import org.hisp.dhis.mobile.ui.designsystem.theme.DHIS2Theme
import org.hisp.dhis.mobile.ui.designsystem.theme.TextColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.coroutineScope
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.foundation.shape.RoundedCornerShape

// Status information for better UI presentation
data class StatusInfo(
    val text: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: androidx.compose.ui.graphics.Color,
    val backgroundColor: androidx.compose.ui.graphics.Color
)

@SuppressLint("SuspiciousIndentation")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatasetInstancesScreen(
    navController: NavController,
    datasetId: String,
    datasetName: String,
    viewModel: DatasetInstancesViewModel = hiltViewModel()
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val combinedState by viewModel.combinedState.collectAsState()
    val state = combinedState.uiState
    val syncState = combinedState.syncState
    val bulkMode by viewModel.bulkCompletionMode.collectAsState()
    val selectedInstances by viewModel.selectedInstances.collectAsState()
    val filterState by viewModel.filterState.collectAsState()
    var bulkActionMessage by remember { mutableStateOf<String?>(null) }
    var bulkActionSuccess by remember { mutableStateOf<Boolean?>(null) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showSyncDialog by remember { mutableStateOf(false) }
    var showFilterSection by remember { mutableStateOf(false) }

    LaunchedEffect(datasetId) {
        viewModel.setDatasetId(datasetId)
    }

    // Refresh data when the screen is resumed (e.g., coming back from EditEntryScreen)
    LaunchedEffect(Unit) {
        viewModel.refreshData()
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Short
            )
        }
    }

    LaunchedEffect(state.successMessage) {
        state.successMessage?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Short
            )
        }
    }

    BaseScreen(
        title = datasetName,
        navController = navController,
        actions = {
            // Keep icons visible during loading - just disable them
            IconButton(
                onClick = { showFilterSection = !showFilterSection },
                enabled = !syncState.isRunning && !bulkMode
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = "Filter",
                    tint = if (filterState.hasActiveFilters() || showFilterSection) MaterialTheme.colorScheme.primary else TextColor.OnSurface
                )
            }
            IconButton(
                onClick = {
                    if (!syncState.isRunning) {
                        showSyncDialog = true
                    }
                },
                enabled = !state.isLoading && !syncState.isRunning && !bulkMode
            ) {
                if (syncState.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Sync",
                        tint = TextColor.OnSurface
                    )
                }
            }
            IconButton(
                onClick = { viewModel.toggleBulkCompletionMode() },
                enabled = !state.isLoading && !syncState.isRunning
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = if (bulkMode) "Exit Bulk Complete" else "Bulk Complete",
                    tint = if (bulkMode) MaterialTheme.colorScheme.primary else TextColor.OnSurface
                )
            }
        },
        floatingActionButton = {
            if (!bulkMode) {
                FloatingActionButton(
                    onClick = {
                        val encodedDatasetName = URLEncoder.encode(datasetName, "UTF-8")
                        navController.navigate("CreateDataEntry/$datasetId/$encodedDatasetName") {
                            launchSingleTop = true
                            popUpTo("DatasetInstances/{datasetId}/{datasetName}") {
                                saveState = true
                            }
                        }
                    },
                    modifier = Modifier.padding(AppDesignSystem.Spacing.medium),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create Data Entry"
                    )
                }
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Seamless filter panel integrated with header
            SeamlessFilterPanel(
                isVisible = showFilterSection,
                currentFilter = filterState,
                onApplyFilter = { newFilter ->
                    viewModel.updateFilterState(newFilter)
                }
            )

            // Main content with proper loading overlays
            OverlayLoader(
                message = "Syncing...",
                isVisible = syncState.isRunning,
                progress = syncState.progress,
                progressStep = syncState.step,
                modifier = Modifier.weight(1f) // Ensure loader takes available space
            ) {
                if (state.isLoading) {
                    FullScreenLoader(
                        message = "Loading dataset instances...",
                        isVisible = true
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = 88.dp, // Increased padding for FAB clearance
                            start = AppDesignSystem.Spacing.medium,
                            end = AppDesignSystem.Spacing.medium,
                            top = AppDesignSystem.Spacing.medium
                        ),
                        verticalArrangement = Arrangement.spacedBy(AppDesignSystem.Spacing.small)
                    ) {
                        items(state.filteredInstances) { instance ->
                            // Enhanced instance card with improved layout
                            val formattedDate = try {
                                instance.lastUpdated?.let { dateStr ->
                                    if (dateStr is String) {
                                        val inputFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH)
                                        val outputFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
                                        val date = inputFormat.parse(dateStr)
                                        date?.let { outputFormat.format(it) } ?: "N/A"
                                    } else {
                                        "N/A"
                                    }
                                } ?: "N/A"
                            } catch (e: Exception) {
                                Log.e("DatasetInstancesScreen", "Error parsing date: ", e)
                                "N/A"
                            }

                            val periodText = instance.period.toString().replace("Period(id=", "").replace(")", "")
                            val attrComboName = state.attributeOptionCombos.find { it.first == instance.attributeOptionCombo }?.second ?: instance.attributeOptionCombo
                            val isComplete = instance.state == DatasetInstanceState.COMPLETE
                            val instanceKey = "${instance.datasetId}|${instance.period.id}|${instance.organisationUnit.id}|${instance.attributeOptionCombo}"
                            val hasLocalChanges = state.instancesWithDrafts.contains(instanceKey)
                            val dateForTopRight = if (instance.lastUpdated != null) formattedDate else null

                            // Use enhanced card components based on mode
                            if (bulkMode) {
                                EnhancedDatasetInstanceCardWithCheckbox(
                                    instance = instance,
                                    organisationUnitName = instance.organisationUnit.name,
                                    periodText = periodText,
                                    attributeOptionComboName = if (!attrComboName.equals("default", ignoreCase = true)) attrComboName else null,
                                    formattedDate = dateForTopRight,
                                    isComplete = isComplete,
                                    hasLocalChanges = hasLocalChanges,
                                    isSelected = selectedInstances.contains(instance.id),
                                    onSelectionChanged = { _ ->
                                        if (!state.isLoading && !syncState.isRunning) {
                                            viewModel.toggleInstanceSelection(instance.id)
                                        }
                                    },
                                    onClick = {
                                        if (!bulkMode) {
                                            val encodedDatasetId = URLEncoder.encode(datasetId, "UTF-8")
                                            val encodedDatasetName = URLEncoder.encode(datasetName, "UTF-8")
                                            navController.navigate("EditEntry/$encodedDatasetId/${instance.period.id}/${instance.organisationUnit.id}/${instance.attributeOptionCombo}/$encodedDatasetName") {
                                                launchSingleTop = true
                                                popUpTo("DatasetInstances/{datasetId}/{datasetName}") {
                                                    saveState = true
                                                }
                                            }
                                        }
                                    },
                                    enabled = !state.isLoading && !syncState.isRunning
                                )
                            } else {
                                EnhancedDatasetInstanceCard(
                                    instance = instance,
                                    organisationUnitName = instance.organisationUnit.name,
                                    periodText = periodText,
                                    attributeOptionComboName = if (!attrComboName.equals("default", ignoreCase = true)) attrComboName else null,
                                    formattedDate = dateForTopRight,
                                    isComplete = isComplete,
                                    hasLocalChanges = hasLocalChanges,
                                    onClick = {
                                        val encodedDatasetId = URLEncoder.encode(datasetId, "UTF-8")
                                        val encodedDatasetName = URLEncoder.encode(datasetName, "UTF-8")
                                        navController.navigate("EditEntry/$encodedDatasetId/${instance.period.id}/${instance.organisationUnit.id}/${instance.attributeOptionCombo}/$encodedDatasetName") {
                                            launchSingleTop = true
                                            popUpTo("DatasetInstances/{datasetId}/{datasetName}") {
                                                saveState = true
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Bulk action buttons with enhanced styling
            if (bulkMode) {
                Row(
                    modifier = Modifier.padding(AppDesignSystem.Spacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(AppDesignSystem.Spacing.medium)
                ) {
                    Button(
                        onClick = {
                            viewModel.bulkCompleteSelectedInstances { success, error ->
                                bulkActionSuccess = success
                                bulkActionMessage = if (success) "Selected instances marked as complete." else (error ?: "Failed to complete selected instances.")
                            }
                        },
                        enabled = selectedInstances.isNotEmpty() && !state.isLoading && !syncState.isRunning,
                        modifier = Modifier.weight(1f),
                        shape = AppDesignSystem.CornerRadius.medium
                    ) {
                        Text("Complete Selected")
                    }
                    OutlinedButton(
                        onClick = { viewModel.clearBulkSelection() },
                        enabled = !state.isLoading && !syncState.isRunning,
                        modifier = Modifier.weight(1f),
                        shape = AppDesignSystem.CornerRadius.medium
                    ) {
                        Text("Cancel")
                    }
                }
            }

            // Enhanced SnackbarHost
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(AppDesignSystem.Spacing.medium),
                snackbar = { data ->
                    Snackbar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = AppDesignSystem.CornerRadius.medium
                    ) {
                        Text(data.visuals.message)
                    }
                }
            )

            // Effect for showing bulk action snackbar
            if (bulkActionMessage != null) {
                LaunchedEffect(bulkActionMessage) {
                    snackbarHostState.showSnackbar(bulkActionMessage!!)
                    bulkActionMessage = null
                }
            }
        }

        // Filter Dialog - only show when data is loaded
        if (showFilterDialog && !state.isLoading) {
            DatasetInstanceFilterDialog(
                currentFilter = filterState,
                attributeOptionCombos = state.attributeOptionCombos,
                dataset = state.dataset,
                onFilterChanged = { newFilter ->
                    viewModel.updateFilterState(newFilter)
                },
                onClearFilters = {
                    viewModel.clearFilters()
                },
                onDismiss = { showFilterDialog = false }
            )
        }
        
        // Sync Dialog
        if (showSyncDialog) {
            SyncConfirmationDialog(
                syncOptions = SyncOptions(
                    uploadLocalData = state.localInstanceCount > 0,
                    localInstanceCount = state.localInstanceCount
                ),
                onConfirm = { _ ->
                    viewModel.syncDatasetInstances()
                    showSyncDialog = false
                },
                onDismiss = { showSyncDialog = false }
            )
        }
    }


@Composable
fun LoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp
        )
    }
}

fun parseDhis2PeriodToDate(periodId: String): Date? {
    return try {
        when {
            // Yearly: 2023
            Regex("^\\d{4}$").matches(periodId) -> {
                SimpleDateFormat("yyyy", Locale.ENGLISH).parse(periodId)
            }
            // Monthly: 202306
            Regex("^\\d{6}$").matches(periodId) -> {
                SimpleDateFormat("yyyyMM", Locale.ENGLISH).parse(periodId)
            }
            // Daily: 2023-06-01
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(periodId) -> {
                SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(periodId)
            }
            // Weekly: 2023W23
            Regex("^\\d{4}W\\d{1,2}$").matches(periodId) -> {
                val year = periodId.substring(0, 4).toInt()
                val week = periodId.substring(5).toInt()
                val cal = Calendar.getInstance(Locale.ENGLISH)
                cal.clear()
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.WEEK_OF_YEAR, week)
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                cal.time
            }
            // Quarterly: 2023Q2
            Regex("^\\d{4}Q[1-4]$").matches(periodId) -> {
                val year = periodId.substring(0, 4).toInt()
                val quarter = periodId.substring(5).toInt()
                val month = (quarter - 1) * 3
                val cal = Calendar.getInstance(Locale.ENGLISH)
                cal.clear()
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.time
            }
            // Six-monthly: 2023S1 or 2023S2
            Regex("^\\d{4}S[1-2]$").matches(periodId) -> {
                val year = periodId.substring(0, 4).toInt()
                val semester = periodId.substring(5).toInt()
                val month = if (semester == 1) 0 else 6
                val cal = Calendar.getInstance(Locale.ENGLISH)
                cal.clear()
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.time
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatasetInstancesPullDownFilter(
    currentFilter: DatasetInstanceFilterState,
    onApplyFilter: (DatasetInstanceFilterState) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        var searchQuery by remember { mutableStateOf(currentFilter.searchQuery) }
        var syncStatus by remember { mutableStateOf(currentFilter.syncStatus) }
        var completionStatus by remember { mutableStateOf(currentFilter.completionStatus) }
        var sortBy by remember { mutableStateOf(currentFilter.sortBy) }
        var sortOrder by remember { mutableStateOf(currentFilter.sortOrder) }

        var showSyncDropdown by remember { mutableStateOf(false) }
        var showCompletionDropdown by remember { mutableStateOf(false) }
        var showSortByDropdown by remember { mutableStateOf(false) }
        var showSortOrderDropdown by remember { mutableStateOf(false) }
        
        // Search field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                onApplyFilter(
                    currentFilter.copy(
                        searchQuery = searchQuery,
                        sortBy = sortBy,
                        sortOrder = sortOrder
                    )
                )
            },
            label = { Text("Search dataset instances") },
            placeholder = { Text("Enter org unit, period...") },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = {
                Icon(Icons.Default.FilterList, contentDescription = null)
            }
        )

        // Sort Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Sort By
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = sortBy.displayName,
                    onValueChange = { },
                    label = { Text("Sort By") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showSortByDropdown = !showSortByDropdown }) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    }
                )

                DropdownMenu(
                    expanded = showSortByDropdown,
                    onDismissRequest = { showSortByDropdown = false }
                ) {
                    InstanceSortBy.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.displayName) },
                            onClick = {
                                sortBy = sort
                                showSortByDropdown = false
                                onApplyFilter(
                                    currentFilter.copy(
                                        sortBy = sortBy,
                                        sortOrder = sortOrder
                                    )
                                )
                            }
                        )
                    }
                }
            }

            // Sort Order
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = sortOrder.displayName,
                    onValueChange = { },
                    label = { Text("Order") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showSortOrderDropdown = !showSortOrderDropdown }) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    }
                )

                DropdownMenu(
                    expanded = showSortOrderDropdown,
                    onDismissRequest = { showSortOrderDropdown = false }
                ) {
                    SortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(order.displayName) },
                            onClick = {
                                sortOrder = order
                                showSortOrderDropdown = false
                                onApplyFilter(
                                    currentFilter.copy(
                                        sortBy = sortBy,
                                        sortOrder = sortOrder
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }

        // Filter Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Sync Status Filter
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = syncStatus.displayName,
                    onValueChange = { },
                    label = { Text("Sync Status") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showSyncDropdown = !showSyncDropdown }) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    }
                )
                
                DropdownMenu(
                    expanded = showSyncDropdown,
                    onDismissRequest = { showSyncDropdown = false }
                ) {
                    SyncStatus.entries.forEach { status ->
                        DropdownMenuItem(
                            text = { Text(status.displayName) },
                            onClick = {
                                syncStatus = status
                                showSyncDropdown = false
                                onApplyFilter(
                                    currentFilter.copy(
                                        syncStatus = syncStatus,
                                        sortBy = sortBy,
                                        sortOrder = sortOrder
                                    )
                                )
                            }
                        )
                    }
                }
            }
            
            // Completion Status Filter  
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = completionStatus.displayName,
                    onValueChange = { },
                    label = { Text("Completion") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showCompletionDropdown = !showCompletionDropdown }) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    }
                )
                
                DropdownMenu(
                    expanded = showCompletionDropdown,
                    onDismissRequest = { showCompletionDropdown = false }
                ) {
                    CompletionStatus.entries.forEach { status ->
                        DropdownMenuItem(
                            text = { Text(status.displayName) },
                            onClick = {
                                completionStatus = status
                                showCompletionDropdown = false
                                onApplyFilter(
                                    currentFilter.copy(
                                        completionStatus = completionStatus,
                                        sortBy = sortBy,
                                        sortOrder = sortOrder
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
        
        // Clear button
        if (searchQuery.isNotBlank() || syncStatus != SyncStatus.ALL || completionStatus != CompletionStatus.ALL ||
            sortBy != InstanceSortBy.PERIOD || sortOrder != SortOrder.DESCENDING) {
            OutlinedButton(
                onClick = {
                    searchQuery = ""
                    syncStatus = SyncStatus.ALL
                    completionStatus = CompletionStatus.ALL
                    sortBy = InstanceSortBy.PERIOD
                    sortOrder = SortOrder.DESCENDING
                    onApplyFilter(DatasetInstanceFilterState())
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Clear All")
            }
        }
    }}}



