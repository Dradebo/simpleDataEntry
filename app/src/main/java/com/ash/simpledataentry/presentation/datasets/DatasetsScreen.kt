package com.ash.simpledataentry.presentation.datasets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.domain.model.FilterState
import com.ash.simpledataentry.domain.model.SortBy
import com.ash.simpledataentry.domain.model.SortOrder
import com.ash.simpledataentry.domain.model.SyncStatus
import com.ash.simpledataentry.domain.model.PeriodFilterType
import com.ash.simpledataentry.domain.model.RelativePeriod
import com.ash.simpledataentry.domain.repository.AuthRepository
import com.ash.simpledataentry.navigation.Screen
import com.ash.simpledataentry.presentation.core.BaseScreen
import com.ash.simpledataentry.presentation.login.LoginViewModel
import kotlinx.coroutines.launch
import org.hisp.dhis.mobile.ui.designsystem.component.AdditionalInfoItem
import org.hisp.dhis.mobile.ui.designsystem.component.ListCard
import org.hisp.dhis.mobile.ui.designsystem.component.ListCardDescriptionModel
import org.hisp.dhis.mobile.ui.designsystem.component.ListCardTitleModel
import org.hisp.dhis.mobile.ui.designsystem.component.state.rememberAdditionalInfoColumnState
import org.hisp.dhis.mobile.ui.designsystem.component.state.rememberListCardState
import org.hisp.dhis.mobile.ui.designsystem.theme.TextColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.ash.simpledataentry.presentation.datasets.components.DatasetIcon
import com.ash.simpledataentry.presentation.datasets.components.ProgramType
import com.ash.simpledataentry.presentation.core.OverlayLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatasetsScreen(
    navController: NavController,
    viewModel: DatasetsViewModel = hiltViewModel()
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val combinedState by viewModel.combinedState.collectAsState()
    val datasetsState = combinedState.uiState
    val syncState = combinedState.syncState
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showFilterSection by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Menu",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            navController.navigate("settings")
                        }
                    }
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text("About") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            navController.navigate(Screen.AboutScreen.route)
                        }
                    }
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Report Issues") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            navController.navigate(Screen.ReportIssuesScreen.route)
                        }
                    }
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Logout, contentDescription = null) },
                    label = { Text("Logout") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            viewModel.logout()
                            navController.navigate("login") {
                                popUpTo(0)
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.weight(1f))

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    label = { Text("Delete Account") },
                    selected = false,
                    onClick = { 
                        scope.launch {
                            drawerState.close()
                            showDeleteConfirmation = true
                        }
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedIconColor = MaterialTheme.colorScheme.error,
                        unselectedTextColor = MaterialTheme.colorScheme.error
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        },
        gesturesEnabled = true
    ) {
        BaseScreen(
            title = "Home",
            navController = navController,
            actions = {
                // Sync button with loading indicator
                IconButton(
                    onClick = {
                        if (!syncState.isRunning) {
                            viewModel.syncDatasets()
                        }
                    }
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

                IconButton(onClick = { showFilterSection = !showFilterSection }) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = "Filter & Sort",
                        tint = if (showFilterSection) MaterialTheme.colorScheme.primary else TextColor.OnSurface
                    )
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = {
                        scope.launch {
                            drawerState.open()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Menu",
                        tint = TextColor.OnSurface
                    )
                }
            }
        ) {
            // Use OverlayLoader for sync operations
            OverlayLoader(
                message = "Syncing datasets...",
                isVisible = syncState.isRunning,
                progress = syncState.progress,
                progressStep = syncState.step,
                modifier = Modifier.fillMaxSize()
            ) {
                Column {
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
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {

                        }
                    }

                    // Main content
                    when (datasetsState) {
                        is DatasetsState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        is DatasetsState.Error -> {
                            LaunchedEffect(datasetsState) {
                                snackbarHostState.showSnackbar((datasetsState as DatasetsState.Error).message)
                            }
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = (datasetsState as DatasetsState.Error).message,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        is DatasetsState.Success -> {
                            val successState = datasetsState as DatasetsState.Success
                            // Show sync success message
                            LaunchedEffect(successState.syncMessage) {
                                successState.syncMessage?.let { message ->
                                    snackbarHostState.showSnackbar(message)
                                    viewModel.clearSyncMessage()
                                }
                            }

                            val syncItem = AdditionalInfoItem(
                                key = "",
                                value = "",
                                isConstantItem = true
                            )

                            val datasets = successState.filteredDatasets
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(datasets) { dataset ->

                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface
                                        ),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                        onClick = {
                                            navController.navigate("DatasetInstances/${dataset.id}/${dataset.name}")
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            // Icon
                                            DatasetIcon(
                                                style = dataset.style,
                                                size = 40.dp,
                                                programType = ProgramType.DATASET
                                            )

                                            // Content column
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                // Title
                                                Text(
                                                    text = dataset.name,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 2
                                                )

                                                // Description (if available)
                                                if (dataset.description?.isNotBlank() == true) {
                                                    Text(
                                                        text = dataset.description,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 2
                                                    )
                                                }
                                            }

                                            // Entry count display
                                            if (dataset.instanceCount > 0) {
                                                Column(
                                                    horizontalAlignment = Alignment.End,
                                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    Text(
                                                        text = "${dataset.instanceCount} entries",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

                Box(modifier = Modifier.fillMaxSize()) {
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
                                Text(
                                    data.visuals.message,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    )
                }
            }

            // Delete Account Confirmation Dialog
            if (showDeleteConfirmation) {
                val context = androidx.compose.ui.platform.LocalContext.current

                AlertDialog(
                    onDismissRequest = { showDeleteConfirmation = false },
                    title = {
                        Text(
                            "Delete Account",
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    text = {
                        Column {
                            Text("Are you sure you want to delete your account?")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "This will permanently delete:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("• All saved login credentials")
                            Text("• All downloaded data")
                            Text("• All unsaved draft entries")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "This action cannot be undone.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.deleteAccount(context)
                                showDeleteConfirmation = false
                                // Navigate to login after deletion
                                navController.navigate("login") {
                                    popUpTo(0)
                                }
                            },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Delete Account")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showDeleteConfirmation = false }
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Delete Account Confirmation Dialog
            if (showDeleteConfirmation) {
                val context = androidx.compose.ui.platform.LocalContext.current

                AlertDialog(
                    onDismissRequest = { showDeleteConfirmation = false },
                    title = {
                        Text(
                            "Delete Account",
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    text = {
                        Column {
                            Text("Are you sure you want to delete your account?")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "This will permanently delete:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("• All saved login credentials")
                            Text("• All downloaded data")
                            Text("• All unsaved draft entries")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "This action cannot be undone.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.deleteAccount(context)
                                showDeleteConfirmation = false
                                // Navigate to login after deletion
                                navController.navigate("login") {
                                    popUpTo(0)
                                }
                            },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Delete Account")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showDeleteConfirmation = false }
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }

        @OptIn(ExperimentalMaterial3Api::class)
        @Composable
        fun FilterDialog(
            currentFilter: FilterState,
            onApplyFilter: (FilterState) -> Unit,
            onDismiss: () -> Unit
        ) {
            var searchQuery by remember { mutableStateOf(currentFilter.searchQuery) }
            var syncStatus by remember { mutableStateOf(currentFilter.syncStatus) }
            var periodType by remember { mutableStateOf(currentFilter.periodType) }
            var relativePeriod by remember { mutableStateOf(currentFilter.relativePeriod) }

            var showSyncDropdown by remember { mutableStateOf(false) }
            var showPeriodDropdown by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Filter & Sort Datasets") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Search Query
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Search datasets") },
                            placeholder = { Text("Enter dataset name...") },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null)
                            }
                        )

                        // Sync Status Filter
                        Box {
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
                                        }
                                    )
                                }
                            }
                        }

                        // Period Filter
                        Box {
                            OutlinedTextField(
                                value = when (periodType) {
                                    PeriodFilterType.ALL -> "All Periods"
                                    PeriodFilterType.RELATIVE -> relativePeriod?.displayName
                                        ?: "Select Period"

                                    PeriodFilterType.CUSTOM_RANGE -> "Custom Range"
                                },
                                onValueChange = { },
                                label = { Text("Period") },
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(onClick = {
                                        showPeriodDropdown = !showPeriodDropdown
                                    }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                }
                            )

                            DropdownMenu(
                                expanded = showPeriodDropdown,
                                onDismissRequest = { showPeriodDropdown = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All Periods") },
                                    onClick = {
                                        periodType = PeriodFilterType.ALL
                                        relativePeriod = null
                                        showPeriodDropdown = false
                                    }
                                )

                                // Common relative periods
                                listOf<RelativePeriod>(
                                    RelativePeriod.THIS_MONTH,
                                    RelativePeriod.LAST_MONTH,
                                    RelativePeriod.LAST_3_MONTHS,
                                    RelativePeriod.THIS_QUARTER,
                                    RelativePeriod.LAST_QUARTER
                                ).forEach { period ->
                                    DropdownMenuItem(
                                        text = { Text(period.displayName) },
                                        onClick = {
                                            periodType = PeriodFilterType.RELATIVE
                                            relativePeriod = period
                                            showPeriodDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                // Reset all filter values to defaults
                                searchQuery = ""
                                syncStatus = SyncStatus.ALL
                                periodType = PeriodFilterType.ALL
                                relativePeriod = null

                                // Apply cleared filter
                                onApplyFilter(FilterState())
                            }
                        ) {
                            Text("Clear")
                        }

                        Button(
                            onClick = {
                                onApplyFilter(
                                    FilterState(
                                        searchQuery = searchQuery,
                                        syncStatus = syncStatus,
                                        periodType = periodType,
                                        relativePeriod = relativePeriod
                                    )
                                )
                            }
                        ) {
                            Text("Apply")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            )
        }

        @OptIn(ExperimentalMaterial3Api::class)
        @Composable
        fun PullDownFilterSection(
            currentFilter: FilterState,
            onApplyFilter: (FilterState) -> Unit
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                var searchQuery by remember { mutableStateOf(currentFilter.searchQuery) }
                var syncStatus by remember { mutableStateOf(currentFilter.syncStatus) }
                var periodType by remember { mutableStateOf(currentFilter.periodType) }
                var relativePeriod by remember { mutableStateOf(currentFilter.relativePeriod) }
                var sortBy by remember { mutableStateOf(currentFilter.sortBy) }
                var sortOrder by remember { mutableStateOf(currentFilter.sortOrder) }

                var showSyncDropdown by remember { mutableStateOf(false) }
                var showPeriodDropdown by remember { mutableStateOf(false) }
                var showSortByDropdown by remember { mutableStateOf(false) }
                var showSortOrderDropdown by remember { mutableStateOf(false) }

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        onApplyFilter(
                            FilterState(
                                searchQuery = searchQuery,
                                syncStatus = syncStatus,
                                periodType = periodType,
                                relativePeriod = relativePeriod,
                                sortBy = sortBy,
                                sortOrder = sortOrder
                            )
                        )
                    },
                    label = { Text("Search datasets") },
                    placeholder = { Text("Enter dataset name...") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
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
                            SortBy.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(sort.displayName) },
                                    onClick = {
                                        sortBy = sort
                                        showSortByDropdown = false
                                        onApplyFilter(
                                            FilterState(
                                                searchQuery = searchQuery,
                                                syncStatus = syncStatus,
                                                periodType = periodType,
                                                relativePeriod = relativePeriod,
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
                                IconButton(onClick = {
                                    showSortOrderDropdown = !showSortOrderDropdown
                                }) {
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
                                            FilterState(
                                                searchQuery = searchQuery,
                                                syncStatus = syncStatus,
                                                periodType = periodType,
                                                relativePeriod = relativePeriod,
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
                                            FilterState(
                                                searchQuery = searchQuery,
                                                syncStatus = syncStatus,
                                                periodType = periodType,
                                                relativePeriod = relativePeriod,
                                                sortBy = sortBy,
                                                sortOrder = sortOrder
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // Period Filter
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = when (periodType) {
                                PeriodFilterType.ALL -> "All Periods"
                                PeriodFilterType.RELATIVE -> relativePeriod?.displayName
                                    ?: "Select Period"

                                PeriodFilterType.CUSTOM_RANGE -> "Custom Range"
                            },
                            onValueChange = { },
                            label = { Text("Period") },
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { showPeriodDropdown = !showPeriodDropdown }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            }
                        )

                        DropdownMenu(
                            expanded = showPeriodDropdown,
                            onDismissRequest = { showPeriodDropdown = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("All Periods") },
                                onClick = {
                                    periodType = PeriodFilterType.ALL
                                    relativePeriod = null
                                    showPeriodDropdown = false
                                    onApplyFilter(
                                        FilterState(
                                            searchQuery = searchQuery,
                                            syncStatus = syncStatus,
                                            periodType = periodType,
                                            relativePeriod = relativePeriod,
                                            sortBy = sortBy,
                                            sortOrder = sortOrder
                                        )
                                    )
                                }
                            )

                            listOf(
                                RelativePeriod.THIS_MONTH,
                                RelativePeriod.LAST_MONTH,
                                RelativePeriod.LAST_3_MONTHS,
                                RelativePeriod.THIS_QUARTER,
                                RelativePeriod.LAST_QUARTER
                            ).forEach { period ->
                                DropdownMenuItem(
                                    text = { Text(period.displayName) },
                                    onClick = {
                                        periodType = PeriodFilterType.RELATIVE
                                        relativePeriod = period
                                        showPeriodDropdown = false
                                        onApplyFilter(
                                            FilterState(
                                                searchQuery = searchQuery,
                                                syncStatus = syncStatus,
                                                periodType = periodType,
                                                relativePeriod = relativePeriod,
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
                if (searchQuery.isNotBlank() || syncStatus != SyncStatus.ALL || periodType != PeriodFilterType.ALL ||
                    sortBy != SortBy.NAME || sortOrder != SortOrder.ASCENDING
                ) {
                    OutlinedButton(
                        onClick = {
                            searchQuery = ""
                            syncStatus = SyncStatus.ALL
                            periodType = PeriodFilterType.ALL
                            relativePeriod = null
                            sortBy = SortBy.NAME
                            sortOrder = SortOrder.ASCENDING
                            onApplyFilter(FilterState())
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Clear All")
                    }
                }
            }
        }
    }
}