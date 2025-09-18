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
    val state by viewModel.state.collectAsState()
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
                enabled = !state.isSyncing && !bulkMode
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = "Filter",
                    tint = if (filterState.hasActiveFilters() || showFilterSection) MaterialTheme.colorScheme.primary else TextColor.OnSurface
                )
            }
            IconButton(
                onClick = {
                    if (!state.isSyncing) {
                        showSyncDialog = true
                    }
                },
                enabled = !state.isLoading && !state.isSyncing && !bulkMode
            ) {
                if (state.isSyncing) {
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
                enabled = !state.isLoading && !state.isSyncing
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = if (bulkMode) "Exit Bulk Complete" else "Bulk Complete",
                    tint = if (bulkMode) MaterialTheme.colorScheme.primary else TextColor.OnSurface
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Pull-down filter section
                AnimatedVisibility(
                    visible = showFilterSection,
                    enter = slideInVertically(initialOffsetY = { -it }),
                    exit = slideOutVertically(targetOffsetY = { -it })
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        DatasetInstancesPullDownFilter(
                            currentFilter = filterState,
                            onApplyFilter = { newFilter ->
                                viewModel.updateFilterState(newFilter)
                            }
                        )
                    }
                }

                // Main content with proper loading overlays
                OverlayLoader(
                    message = "Syncing...",
                    isVisible = state.isSyncing,
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
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.filteredInstances) { instance ->
                                // ... (Instance item content remains the same)
                                var isLoading by remember { mutableStateOf(false) }
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
                                val showAttrCombo = !attrComboName.equals("default", ignoreCase = true)
                                val isComplete = instance.state == DatasetInstanceState.COMPLETE
                                val instanceKey = "${instance.datasetId}|${instance.period.id}|${instance.organisationUnit.id}|${instance.attributeOptionCombo}"
                                val hasLocalChanges = state.instancesWithDrafts.contains(instanceKey)
                                val dateForTopRight = if (instance.lastUpdated != null) formattedDate else null

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (bulkMode) {
                                        Checkbox(
                                            checked = selectedInstances.contains(instance.id) || isComplete,
                                            onCheckedChange = { checked ->
                                                if (!state.isLoading && !state.isSyncing && !isComplete) {
                                                    viewModel.toggleInstanceSelection(instance.id)
                                                }
                                            },
                                            enabled = !state.isLoading && !state.isSyncing && !isComplete,
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = if (isComplete) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.primary,
                                                uncheckedColor = if (isComplete) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surface
                                            ),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                            onClick = {
                                                if (!isLoading && !bulkMode) {
                                                    val encodedDatasetId = URLEncoder.encode(datasetId, "UTF-8")
                                                    val encodedDatasetName = URLEncoder.encode(datasetName, "UTF-8")
                                                    navController.navigate("EditEntry/$encodedDatasetId/${instance.period.id}/${instance.organisationUnit.id}/${instance.attributeOptionCombo}/$encodedDatasetName") {
                                                        launchSingleTop = true
                                                        popUpTo("DatasetInstances/{datasetId}/{datasetName}") {
                                                            saveState = true
                                                        }
                                                    }
                                                }
                                            }
                                        ) {
                                            Column(modifier = Modifier.padding(16.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "${instance.organisationUnit.name} • $periodText",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (dateForTopRight != null) {
                                                        Text(
                                                            text = dateForTopRight,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                                if (showAttrCombo || isComplete) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        if (showAttrCombo) {
                                                            Text(
                                                                text = attrComboName,
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                        } else {
                                                            Spacer(modifier = Modifier.weight(1f))
                                                        }
                                                        if (isComplete) {
                                                            Icon(
                                                                imageVector = Icons.Default.CheckCircle,
                                                                contentDescription = "Complete",
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                if (hasLocalChanges && !bulkMode) {
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    AssistChip(
                                                        onClick = { /* No action needed */ },
                                                        label = { Text("Not synced", style = MaterialTheme.typography.labelSmall) },
                                                        leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                                        colors = AssistChipDefaults.assistChipColors(
                                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                                        ),
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Bulk action buttons at the bottom of the column
                if (bulkMode) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.bulkCompleteSelectedInstances { success, error ->
                                    bulkActionSuccess = success
                                    bulkActionMessage = if (success) "Selected instances marked as complete." else (error ?: "Failed to complete selected instances.")
                                }
                            },
                            enabled = selectedInstances.isNotEmpty() && !state.isLoading && !state.isSyncing
                        ) {
                            Text("Complete Selected")
                        }
                        Button(
                            onClick = { viewModel.clearBulkSelection() },
                            enabled = !state.isLoading && !state.isSyncing
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }

            // FAB aligned to the bottom end of the Box
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
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create Data Entry"
                    )
                }
            }

            // Snackbar aligned to the bottom center of the Box
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                snackbar = { data ->
                    Snackbar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = Color.White
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
                onConfirm = { uploadFirst ->
                    viewModel.syncDatasetInstances(uploadFirst)
                    showSyncDialog = false
                },
                onDismiss = { showSyncDialog = false }
            )
        }
    }


@Composable
private fun LoadingIndicator() {
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
    }
}
