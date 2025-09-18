@file:OptIn(ExperimentalMaterial3Api::class)

package com.ash.simpledataentry.presentation.dataEntry

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ash.simpledataentry.presentation.datasetInstances.SyncConfirmationDialog
import com.ash.simpledataentry.presentation.datasetInstances.SyncOptions
import org.hisp.dhis.mobile.ui.designsystem.component.Button
import org.hisp.dhis.mobile.ui.designsystem.component.ButtonStyle
import org.hisp.dhis.mobile.ui.designsystem.component.ColorStyle
import org.hisp.dhis.mobile.ui.designsystem.component.InputNumber
import org.hisp.dhis.mobile.ui.designsystem.component.InputShellState
import org.hisp.dhis.mobile.ui.designsystem.component.InputText
import org.hisp.dhis.mobile.ui.designsystem.component.SupportingTextData
import org.hisp.dhis.mobile.ui.designsystem.component.SupportingTextState
import com.ash.simpledataentry.domain.model.*
import com.ash.simpledataentry.presentation.core.BaseScreen
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.platform.LocalContext
import kotlin.Pair
import androidx.compose.runtime.Composable
import com.google.common.collect.Lists.cartesianProduct
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import com.ash.simpledataentry.presentation.dataEntry.components.FrozenHeaderGrid
import com.ash.simpledataentry.presentation.dataEntry.components.SectionNavigator
import com.ash.simpledataentry.presentation.core.FullScreenLoader
import com.ash.simpledataentry.presentation.core.CompactLoader
import com.ash.simpledataentry.presentation.core.OverlayLoader

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditEntryScreen(
    viewModel: DataEntryViewModel = hiltViewModel(),
    navController: NavController,
    datasetId: String,
    datasetName: String,
    period: String,
    orgUnit: String,
    attributeOptionCombo: String
) {
    val combinedState by viewModel.combinedState.collectAsState()
    val state = combinedState.uiState
    val syncState = combinedState.syncState
    var lastLoadedParams by remember { mutableStateOf(Quadruple("", "", "", "")) }
    val currentParams = Quadruple(datasetId, period, orgUnit, attributeOptionCombo)
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var isUIReady by remember { mutableStateOf(false) }
    val showSaveDialog = remember { mutableStateOf(false) }
    val showSyncDialog = remember { mutableStateOf(false) }
    var showCompleteDialog by remember { mutableStateOf(false) }
    val pendingNavAction = remember { mutableStateOf<(() -> Unit)?>(null) }
    
    // Debug log for showSyncDialog state changes
    LaunchedEffect(showSyncDialog.value) {
        Log.d("EditEntryScreen", "showSyncDialog state changed to: ${showSyncDialog.value}")
    }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    // Define onValueChange ONCE here, at the top
    val onValueChange: (String, DataValue) -> Unit = { value, dataValue ->
        viewModel.updateCurrentValue(value, dataValue.dataElement, dataValue.categoryOptionCombo)
    }

    // --- Proper unsaved changes detection using ViewModel's dirty tracking ---
    val hasUnsavedChanges = viewModel.hasUnsavedChanges()

    val shouldShowSaveDialog = hasUnsavedChanges && !state.saveInProgress

    // Intercept back press: only show dialog if shouldShowSaveDialog
    BackHandler(enabled = shouldShowSaveDialog) {
        showSaveDialog.value = true
        pendingNavAction.value = { navController.popBackStack() }
    }

    // Intercept navigation via top bar back button: only show dialog if shouldShowSaveDialog
    val baseScreenNavIcon: @Composable (() -> Unit) = {
        IconButton(onClick = {
            if (state.saveInProgress) {
                // Only block navigation while actively saving
                return@IconButton
            }
            if (hasUnsavedChanges) {
                showSaveDialog.value = true
                pendingNavAction.value = { navController.popBackStack() }
            } else {
                navController.popBackStack()
            }
        }) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back"
            )
        }
    }

    // Save confirmation dialog: only show if shouldShowSaveDialog
    if (showSaveDialog.value && shouldShowSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog.value = false },
            title = { Text("Unsaved Changes") },
            text = { Text("You have unsaved changes. Save before leaving?") },
            confirmButton = {
                TextButton(onClick = {
                    showSaveDialog.value = false
                    viewModel.saveAllDataValues(context)
                    pendingNavAction.value?.invoke()
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showSaveDialog.value = false
                        // Discard: clear only current session changes, preserve existing drafts
                        viewModel.clearCurrentSessionChanges()
                        pendingNavAction.value?.invoke()
                    }) { Text("Discard") }
                    TextButton(onClick = {
                        showSaveDialog.value = false
                    }) { Text("Cancel") }
                }
            }
        )
    }

    // Sync confirmation dialog - using the same one as datasetInstances
    if (showSyncDialog.value) {
        Log.d("EditEntryScreen", "Rendering SyncConfirmationDialog! uploadLocalData: ${state.localDraftCount > 0}, localInstanceCount: ${state.localDraftCount}")
        SyncConfirmationDialog(
            syncOptions = SyncOptions(
                uploadLocalData = state.localDraftCount > 0,
                localInstanceCount = state.localDraftCount,
                isEditEntryContext = true
            ),
            onConfirm = { uploadFirst ->
                Log.d("EditEntryScreen", "SyncConfirmationDialog onConfirm called with uploadFirst: $uploadFirst")
                viewModel.syncDataEntry(uploadFirst)
                showSyncDialog.value = false
                Log.d("EditEntryScreen", "showSyncDialog set to false after confirm")
            },
            onDismiss = { 
                Log.d("EditEntryScreen", "SyncConfirmationDialog onDismiss called")
                showSyncDialog.value = false 
                Log.d("EditEntryScreen", "showSyncDialog set to false after dismiss")
            }
        )
    }

    // Mark incomplete dialog - shown when dataset is already completed
    if (showCompleteDialog) {
        AlertDialog(
            onDismissRequest = { showCompleteDialog = false },
            title = { Text("Dataset Already Complete") },
            text = { Text("This dataset is already marked as complete. Do you want to mark it as incomplete so you can edit it?") },
            confirmButton = {
                TextButton(onClick = {
                    showCompleteDialog = false
                    viewModel.markDatasetIncomplete { success, message ->
                        coroutineScope.launch {
                            if (success) {
                                snackbarHostState.showSnackbar(message ?: "Dataset marked as incomplete.")
                            } else {
                                snackbarHostState.showSnackbar(message ?: "Failed to mark as incomplete.")
                            }
                        }
                    }
                }) { Text("Mark Incomplete") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCompleteDialog = false
                }) { Text("Cancel") }
            }
        )
    }

    // Validation result dialog for completion
    state.validationSummary?.let { validationSummary ->
        ValidationResultDialog(
            validationSummary = validationSummary,
            showCompletionOption = !state.isCompleted, // Only show completion options if not already completed
            onComplete = {
                viewModel.completeDatasetAfterValidation { success, message ->
                    coroutineScope.launch {
                        if (success) {
                            snackbarHostState.showSnackbar(message ?: "Dataset marked as complete.")
                        } else {
                            snackbarHostState.showSnackbar(message ?: "Failed to mark as complete.")
                        }
                    }
                }
                viewModel.clearValidationResult()
            },
            onCompleteAnyway = {
                viewModel.completeDatasetAfterValidation { success, message ->
                    coroutineScope.launch {
                        if (success) {
                            snackbarHostState.showSnackbar(message ?: "Dataset marked as complete (validation warnings ignored).")
                        } else {
                            snackbarHostState.showSnackbar(message ?: "Failed to mark as complete.")
                        }
                    }
                }
                viewModel.clearValidationResult()
            },
            onDismiss = {
                viewModel.clearValidationResult()
            }
        )
    }

    // --- Accordion expansion state for nested accordions ---
    val expandedAccordions = remember { mutableStateOf<Map<List<String>, String?>>(emptyMap()) }

    // In EditEntryScreen, define the toggle handler ONCE:
    val onAccordionToggle: (List<String>, String) -> Unit = { parentPath, optionUid ->
        expandedAccordions.value = buildMap {
            putAll(expandedAccordions.value)
            val current = expandedAccordions.value[parentPath]
            put(parentPath, if (current == optionUid) null else optionUid)
        }
    }

    LaunchedEffect(state.currentSectionIndex, state.dataValues) {
        if (state.dataValues.isNotEmpty() && state.currentSectionIndex >= 0 && state.currentSectionIndex < state.totalSections) {
            coroutineScope.launch {
                // Ensure the list is populated before trying to scroll
                listState.animateScrollToItem(index = state.currentSectionIndex)
            }
        }
    }


    LaunchedEffect(currentParams) {
        if (state.dataValues.isEmpty() || lastLoadedParams != currentParams) {
            isUIReady = false
            viewModel.loadDataValues(
                datasetId = datasetId,
                datasetName = datasetName,
                period = period,
                orgUnitId = orgUnit,
                attributeOptionCombo = attributeOptionCombo,
                isEditMode = true
            )
            lastLoadedParams = currentParams
        }
    }
    
    LaunchedEffect(state.isLoading, state.dataValues) {
        if (!state.isLoading && state.dataValues.isNotEmpty()) {
            delay(300)
            isUIReady = true
        }
    }
    fun manualRefresh() {
        viewModel.loadDataValues(
            datasetId = datasetId,
            datasetName = datasetName,
            period = period,
            orgUnitId = orgUnit,
            attributeOptionCombo = attributeOptionCombo,
            isEditMode = true
        )
        lastLoadedParams = currentParams
    }
    // Show Snackbar on save result
    LaunchedEffect(state.saveResult) {
        state.saveResult?.let {
            if (it.isSuccess) {
                snackbarHostState.showSnackbar("All data saved successfully.")
            } else {
                snackbarHostState.showSnackbar(it.exceptionOrNull()?.message ?: "Failed to save some fields.")
            }
            viewModel.resetSaveFeedback()
        }
    }

    // Handle sync success messages
    LaunchedEffect(state.successMessage) {
        state.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    // Handle sync error messages
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(state.currentSectionIndex, state.dataValues) {
        if (state.dataValues.isNotEmpty() && state.totalSections > 0 && state.currentSectionIndex != -1) { // Check for != -1
            coroutineScope.launch {
                listState.animateScrollToItem(index = state.currentSectionIndex)
            }
        }
    }


    // Resolve attribute option combo display name for the title
    val attrComboName = state.attributeOptionCombos.find { it.first == attributeOptionCombo }?.second ?: attributeOptionCombo
    BaseScreen(
        title = "${java.net.URLDecoder.decode(datasetName, "UTF-8")} - ${period.replace("Period(id=", "").replace(")", "")} - $attrComboName",
        navController = navController,
        navigationIcon = baseScreenNavIcon,
        actions = {
            // Add save button to header bar
            IconButton(
                onClick = { viewModel.saveAllDataValues(context) },
                enabled = !state.saveInProgress
            ) {
                if (state.saveInProgress) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = "Save",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            // Add sync button to top bar
            IconButton(
                onClick = {
                    Log.d("EditEntryScreen", "Sync button clicked! Current isSyncing: ${syncState.isRunning}, localDraftCount: ${state.localDraftCount}")
                    showSyncDialog.value = true
                    Log.d("EditEntryScreen", "showSyncDialog set to true")
                },
                enabled = !syncState.isRunning
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
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            // Complete button with improved behavior
            IconButton(
                onClick = {
                    if (state.isCompleted) {
                        // Show dialog asking whether to run validation or mark as incomplete
                        showCompleteDialog = true
                    } else {
                        viewModel.startValidationForCompletion()
                    }
                },
                enabled = !state.isLoading && !state.isValidating
            ) {
                if (state.isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = if (state.isCompleted) Icons.Default.CheckCircle else Icons.Default.Check,
                        contentDescription = if (state.isCompleted) "Already Complete" else "Mark Complete",
                        tint = if (state.isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    ) {
        // Use OverlayLoader for sync operations (header actions)
        OverlayLoader(
            message = "Syncing...",
            isVisible = syncState.isRunning,
            progress = syncState.progress,
            progressStep = syncState.step,
            modifier = Modifier.fillMaxSize()
        ) {
            if (state.isLoading || !isUIReady) {
                // Use FullScreenLoader for navigation loading
                FullScreenLoader(
                    message = "Loading form...",
                    isVisible = true
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {

                        if (state.dataValues.isEmpty()) {
                            Text(
                                text = "No data elements found for this dataset/period/org unit.",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        } else {
                        val bringIntoViewRequester = remember { BringIntoViewRequester() }
                        val coroutineScope = rememberCoroutineScope()
                        val listState = rememberLazyListState()

                        LaunchedEffect(state.currentSectionIndex) {
                            if (state.currentSectionIndex != -1) {
                                listState.animateScrollToItem(state.currentSectionIndex)
                            }
                        }

                        // Data Element First Rendering (Default)
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            // Render sections as top-level accordions with proper scrolling integration
                            itemsIndexed(
                                items = state.dataElementGroupedSections.entries.toList(),
                                key = { _, (sectionName, _) -> "section_$sectionName" }
                            ) { sectionIndex, (sectionName, elementGroups) ->
                                // Create data element ordering map to preserve dataset section order
                                val dataElementOrder = state.dataValues
                                    .filter { it.sectionName == sectionName }
                                    .groupBy { it.dataElement }
                                    .keys
                                    .mapIndexed { index, dataElement -> dataElement to index }
                                    .toMap()
                                val sectionIsExpanded = sectionIndex == state.currentSectionIndex
                                // Count data elements that have any data entered (not individual fields)
                                val elementsWithData = elementGroups.count { (_, dataValues) ->
                                    dataValues.any { !it.value.isNullOrBlank() }
                                }
                                val totalElements = elementGroups.size
                                val bringIntoViewRequester = remember { BringIntoViewRequester() }
                                
                                // Check if ALL data elements in this section have default category combinations
                                val allElementsHaveDefaultCategories = elementGroups.values.all { dataValues ->
                                    val firstDataValue = dataValues.firstOrNull()
                                    val structure = firstDataValue?.let { 
                                        state.categoryComboStructures[it.categoryOptionCombo] 
                                    } ?: emptyList()
                                    structure.isEmpty() || (structure.size == 1 && structure[0].first.lowercase().contains("default"))
                                }
                                
                                // Section Header Accordion
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 72.dp, max = 96.dp) // Allow some growth for long titles
                                        .clickable {
                                            viewModel.setCurrentSectionIndex(sectionIndex)
                                            if (!sectionIsExpanded) {
                                                coroutineScope.launch {
                                                    bringIntoViewRequester.bringIntoView()
                                                }
                                            }
                                        },
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .padding(16.dp)
                                            .bringIntoViewRequester(bringIntoViewRequester)
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = sectionName,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                lineHeight = 20.sp, // Explicit line height for consistency
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "($elementsWithData/$totalElements elements)",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                maxLines = 1
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Expand/Collapse Section",
                                            modifier = Modifier
                                                .rotate(if (sectionIsExpanded) 180f else 0f)
                                                .size(24.dp)
                                        )
                                    }
                                }
                                
                                // Section Content
                                AnimatedVisibility(visible = sectionIsExpanded) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (allElementsHaveDefaultCategories) {
                                            // All elements have default categories - render as simple vertical list
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                elementGroups.entries
                                                    .sortedBy { dataElementOrder[it.key] ?: Int.MAX_VALUE } // Sort by dataset section order
                                                    .forEach { (_, dataValues) ->
                                                        dataValues.forEach { dataValue ->
                                                            DataValueField(
                                                                dataValue = dataValue,
                                                                onValueChange = { value -> onValueChange(value, dataValue) },
                                                                viewModel = viewModel
                                                            )
                                                        }
                                                    }
                                            }
                                        } else {
                                            // Mixed or non-default categories - render data element accordions
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                elementGroups.entries
                                                    .sortedBy { dataElementOrder[it.key] ?: Int.MAX_VALUE } // Sort by dataset section order
                                                    .forEach { (dataElement, dataValues) ->
                                                        val hasAnyData = dataValues.any { !it.value.isNullOrBlank() }
                                                        val elementKey = "de_$sectionName$dataElement"
                                                        val isElementExpanded = expandedAccordions.value[listOf(elementKey)] != null
                                                        
                                                        // Check if this data element has default category
                                                        val firstDataValue = dataValues.firstOrNull()
                                                        val structure = firstDataValue?.let { 
                                                            state.categoryComboStructures[it.categoryOptionCombo] 
                                                        } ?: emptyList()
                                                        val isDefaultCategoryCombo = structure.isEmpty() || 
                                                            (structure.size == 1 && structure[0].first.lowercase().contains("default"))
                                                        
                                                        if (isDefaultCategoryCombo) {
                                                            // Default category - render field directly without accordion
                                                            dataValues.forEach { dataValue ->
                                                                DataValueField(
                                                                    dataValue = dataValue,
                                                                    onValueChange = { value -> onValueChange(value, dataValue) },
                                                                    viewModel = viewModel
                                                                )
                                                            }
                                                        } else {
                                                            // Non-default category - render as data element accordion
                                                            Surface(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .clickable {
                                                                        onAccordionToggle(listOf(elementKey), "toggle")
                                                                    },
                                                                color = MaterialTheme.colorScheme.surface,
                                                                shape = MaterialTheme.shapes.medium,
                                                                tonalElevation = 2.dp
                                                            ) {
                                                                Column {
                                                                    // Data Element Header
                                                                    Row(
                                                                        modifier = Modifier
                                                                            .fillMaxWidth()
                                                                            .heightIn(min = 64.dp, max = 64.dp) // Fixed height for uniformity
                                                                            .padding(16.dp),
                                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                                        verticalAlignment = Alignment.CenterVertically
                                                                    ) {
                                                                        Column(modifier = Modifier.weight(1f)) {
                                                                            Text(
                                                                                text = dataValues.firstOrNull()?.dataElementName ?: dataElement,
                                                                                style = MaterialTheme.typography.titleSmall,
                                                                                fontWeight = FontWeight.Medium,
                                                                                maxLines = 2,
                                                                                overflow = TextOverflow.Ellipsis
                                                                            )
                                                                            Text(
                                                                                text = if (hasAnyData) "Has data" else "No data",
                                                                                style = MaterialTheme.typography.bodySmall,
                                                                                color = if (hasAnyData) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                                                                maxLines = 1
                                                                            )
                                                                        }
                                                                        Icon(
                                                                            imageVector = Icons.Default.KeyboardArrowDown,
                                                                            contentDescription = "Expand/Collapse Data Element",
                                                                            modifier = Modifier
                                                                                .rotate(if (isElementExpanded) 180f else 0f)
                                                                                .size(20.dp)
                                                                        )
                                                                    }
                                                                    
                                                                    // Data Element Content - Category Structure or Direct Fields
                                                                    AnimatedVisibility(visible = isElementExpanded) {
                                                                        Column(
                                                                            modifier = Modifier
                                                                                .fillMaxWidth()
                                                                                .padding(horizontal = 16.dp)
                                                                                .padding(bottom = 16.dp),
                                                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                                                        ) {
                                                                            val sortedCategories = sortCategoriesByOptionCount(structure)
                                                                            val categoryOptionComboUid = firstDataValue?.categoryOptionCombo
                                                                            val optionUidsToComboUidForGroup = state.optionUidsToComboUid[categoryOptionComboUid] ?: emptyMap()
                                                                            
                                                                            CategoryAccordionRecursiveWithFields(
                                                                                categories = sortedCategories,
                                                                                values = dataValues.filter { it.dataElement == dataElement },
                                                                                onValueChange = onValueChange,
                                                                                optionUidsToComboUid = optionUidsToComboUidForGroup,
                                                                                viewModel = viewModel,
                                                                                parentPath = listOf(elementKey),
                                                                                expandedAccordions = expandedAccordions.value,
                                                                                onToggle = onAccordionToggle
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
                                }
                            }
                            item {
                                Spacer(modifier = Modifier.height(80.dp)) // Space for the section navigator
                            }
                        }
                    }

                    // Error message
                    state.error?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    // Validation message
                    state.validationMessage?.let { message ->
                        Text(
                            text = message,
                            color = when (state.validationState) {
                                ValidationState.ERROR -> MaterialTheme.colorScheme.error
                                ValidationState.WARNING -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                                ValidationState.VALID -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                // Section Navigator at the bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 8.dp)
                ) {
                    SectionNavigator(
                        onPreviousClick = { viewModel.goToPreviousSection() },
                        onNextClick = { viewModel.goToNextSection() },
                        currentSectionIndex = state.currentSectionIndex,
                        totalSections = state.totalSections
                    )
                }
                // Snackbar
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    SnackbarHost(
                        hostState = snackbarHostState,
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
            }
        }
    }
    // Show Snackbar on sync error
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
        }
    }
}

@Composable
private fun SectionContent(
    sectionName: String,
    structure: List<Pair<String, List<Pair<String, String>>>>?,
    values: List<DataValue>,
    expandedCategoryGroup: String?,
    onToggleCategoryGroup: (String, String) -> Unit,
    onValueChange: (String, DataValue) -> Unit,
    optionUidsToComboUid: Map<Set<String>, String>,
    viewModel: DataEntryViewModel,
    renderedKeys: MutableSet<String>? = null,
    expandedAccordions: Map<List<String>, String?>,
    onToggle: (List<String>, String) -> Unit
) {
    android.util.Log.d(
        "SectionContent",
        "sectionName=$sectionName, structure=${structure?.map { it.first }}, values=${values.map { it.dataElement + ":" + it.categoryOptionCombo }}"
    )
    val state by viewModel.state.collectAsState()
    val categoryComboStructures = state.categoryComboStructures
    val dataElements = values.map { it.dataElement to it.dataElementName }.distinct()

    // --- New grouping logic per RENDERING_RULES.md ---
    val structureKeyFor = { dataValue: DataValue ->
        val structure = categoryComboStructures[dataValue.categoryOptionCombo]
        structure?.joinToString("|") { cat ->
            cat.first + ":" + cat.second.joinToString(",") { it.first }
        } ?: "__DEFAULT__"
    }
    val groups = values.groupBy(structureKeyFor)
    val allSameStructure = groups.size == 1
    val isAllDefault = groups.keys.singleOrNull() == "__DEFAULT__"

    if (allSameStructure) {
        val groupValues = values
        val structure = groupValues.firstOrNull()?.let { categoryComboStructures[it.categoryOptionCombo] } ?: emptyList()
        if (structure.isEmpty()) {
            // Flat list for default (zero category)
            Column(modifier = Modifier.fillMaxWidth()) {
                dataElements.forEach { (dataElement, dataElementName) ->
                    val dataValue = groupValues.find { it.dataElement == dataElement }
                    val key = dataValue?.dataElement + "_" + dataValue?.categoryOptionCombo
                    renderedKeys?.add(key)
                    DataElementRow(
                        dataElementName = dataElementName,
                        fields = listOf(dataValue),
                        onValueChange = onValueChange
                    )
                }
            }
        } else {
            // Use the new recursive accordion logic for all non-empty category structures
            CategoryAccordionRecursive(
                categories = structure,
                values = groupValues,
                onValueChange = onValueChange,
                optionUidsToComboUid = optionUidsToComboUid,
                viewModel = viewModel,
                parentPath = emptyList(),
                expandedAccordions = expandedAccordions,
                onToggle = onToggle
            )
        }
    }
    // If mixed category combos, handle per rule 5
    // 1. Render all default (zero category) elements as a flat list
    val defaultValues = groups["__DEFAULT__"] ?: emptyList()
    if (defaultValues.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            dataElements.forEach { (dataElement, dataElementName) ->
                val dataValue = defaultValues.find { it.dataElement == dataElement }
                val key = dataValue?.dataElement + "_" + dataValue?.categoryOptionCombo
                renderedKeys?.add(key)
                DataElementRow(
                    dataElementName = dataElementName,
                    fields = listOf(dataValue),
                    onValueChange = onValueChange
                )
            }
        }
        Divider(modifier = Modifier.padding(vertical = 8.dp))
    }
    // 2. For each non-default group, render using the correct rule
    groups.filterKeys { it != "__DEFAULT__" }.forEach { (groupKey, groupValues) ->
        val structure = groupValues.firstOrNull()?.let { categoryComboStructures[it.categoryOptionCombo] } ?: emptyList()
        val categoryOptionComboUid = groupValues.firstOrNull()?.categoryOptionCombo
        val optionUidsToComboUidForGroup = state.optionUidsToComboUid[categoryOptionComboUid] ?: emptyMap()
        CategoryAccordionRecursive(
            categories = structure,
            values = groupValues,
            onValueChange = onValueChange,
            optionUidsToComboUid = optionUidsToComboUidForGroup,
            viewModel = viewModel,
            parentPath = emptyList(),
            expandedAccordions = expandedAccordions,
            onToggle = onToggle
        )
        Divider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun DataElementRow(
    dataElementName: String,
    fields: List<DataValue?>,
    onValueChange: (String, DataValue) -> Unit
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)) {
        Text(text = dataElementName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        fields.filterNotNull().forEach { dataValue ->
            DataValueField(
                dataValue = dataValue,
                onValueChange = { value -> onValueChange(value, dataValue) },
                viewModel = viewModel()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryAccordion(
    header: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    content: @Composable () -> Unit
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp, max = 56.dp) // Fixed height for uniformity
                .clickable {
                    onToggleExpand()
                    if (!expanded) {
                        coroutineScope.launch {
                            bringIntoViewRequester.bringIntoView()
                        }
                    }
                },
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxHeight(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = header,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand/Collapse Category",
                    modifier = Modifier
                        .rotate(if (expanded) 180f else 0f)
                        .size(20.dp)
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp)
                .bringIntoViewRequester(bringIntoViewRequester)) {
                content()
            }
        }
    }
}

@Composable
private fun DataElementGridRow(
    dataElementName: String,
    colCategory: Pair<String, List<Pair<String, String>>>,
    rowCategory: Pair<String, List<Pair<String, String>>>,
    dataValues: List<DataValue>,
    comboMap: Map<Set<String>, String>,
    onValueChange: (String, DataValue) -> Unit
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)) {
        Text(text = dataElementName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.width(100.dp)) // For row header
            colCategory.second.forEach { colOpt ->
                Text(
                    text = colOpt.second,
                    modifier = Modifier.width(100.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        rowCategory.second.forEach { rowOpt ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rowOpt.second,
                    modifier = Modifier.width(100.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                colCategory.second.forEach { colOpt ->
                    val cocUid = comboMap.entries.find { it.key == setOf(rowOpt.first, colOpt.first) || it.key == setOf(colOpt.first, rowOpt.first) }?.value
                    val dataValue = dataValues.find { it.categoryOptionCombo == cocUid }
                    Box(modifier = Modifier
                        .width(100.dp)
                        .padding(4.dp)) {
                        if (dataValue != null) {
                            DataValueField(
                                dataValue = dataValue,
                                onValueChange = { value -> onValueChange(value, dataValue) },
                                viewModel = viewModel()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DataElementSection(
    sectionName: String,
    categoryGroups: Map<String, List<DataValue>>,
    isExpandedSections: Map<String, Boolean>,
    onToggleCategoryGroup: (String, String) -> Unit,
    onValueChange: (String, DataValue) -> Unit
) {
    // Add logging for troubleshooting
    android.util.Log.d("EditEntryScreen", "DataElementSection: sectionName=$sectionName, categoryGroups=${categoryGroups.map { it.key to it.value.map { v -> v.dataElementName } }}")
    var selectedCategory by remember { mutableStateOf("") }
    var expandedFilter by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 16.dp)
    ) {
        if (categoryGroups.size > 1) {
            CategoryFilter(
                categoryGroups = categoryGroups,
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it },
                expanded = expandedFilter,
                onExpandedChange = { expandedFilter = it }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        CategoryContent(
            categoryGroups = categoryGroups,
            selectedCategory = selectedCategory,
            sectionName = sectionName,
            isExpandedSections = isExpandedSections,
            onToggleCategoryGroup = onToggleCategoryGroup,
            onValueChange = onValueChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilter(
    categoryGroups: Map<String, List<DataValue>>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = if (selectedCategory.isEmpty()) "All Categories" else (categoryGroups[selectedCategory]?.firstOrNull()?.categoryOptionComboName ?: selectedCategory),
            onValueChange = {},
            readOnly = true,
            label = { Text("Filter by Category") },
            trailingIcon = { 
                if (selectedCategory.isNotEmpty()) {
                    IconButton(
                        onClick = { 
                            onCategorySelected("")
                            onExpandedChange(false)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear Filter"
                        )
                    }
                } else {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            DropdownMenuItem(
                text = { Text("All Categories") },
                onClick = {
                    onCategorySelected("")
                    onExpandedChange(false)
                }
            )
            categoryGroups.keys.forEach { categoryUid ->
                val categoryName = categoryGroups[categoryUid]?.firstOrNull()?.categoryOptionComboName ?: categoryUid
                DropdownMenuItem(
                    text = { Text(categoryName) },
                    onClick = {
                        onCategorySelected(categoryUid)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

@Composable
private fun CategoryContent(
    categoryGroups: Map<String, List<DataValue>>,
    selectedCategory: String,
    sectionName: String,
    isExpandedSections: Map<String, Boolean>,
    onToggleCategoryGroup: (String, String) -> Unit,
    onValueChange: (String, DataValue) -> Unit
) {
    if (selectedCategory.isEmpty()) {
        categoryGroups.forEach { (categoryGroup, values) ->
            key("${sectionName}_$categoryGroup") {
                if (categoryGroup == "default" || categoryGroup.isBlank()) {
                    values.forEach { dataValue ->
                        key("${dataValue.dataElement}_${dataValue.categoryOptionCombo}") {
                            DataValueField(
                                dataValue = dataValue,
                                onValueChange = { value -> onValueChange(value, dataValue) },
                                viewModel = viewModel()
                            )
                        }
                    }
                } else {
                    CategoryGroup(
                        categoryGroup = values.firstOrNull()?.categoryOptionComboName ?: categoryGroup,
                        values = values,
                        isExpanded = isExpandedSections["$sectionName:$categoryGroup"] == true,
                        onToggleExpand = { onToggleCategoryGroup(sectionName, categoryGroup) },
                        onValueChange = onValueChange
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    } else {
        categoryGroups[selectedCategory]?.let { values ->
            val categoryGroup = selectedCategory
            key("${sectionName}_$categoryGroup") {
                if (categoryGroup == "default" || categoryGroup.isBlank()) {
                    values.forEach { dataValue ->
                        key("${dataValue.dataElement}_${dataValue.categoryOptionCombo}") {
                            DataValueField(
                                dataValue = dataValue,
                                onValueChange = { value -> onValueChange(value, dataValue) },
                                viewModel = viewModel()
                            )
                        }
                    }
                } else {
                    CategoryGroup(
                        categoryGroup = values.firstOrNull()?.categoryOptionComboName ?: selectedCategory,
                        values = values,
                        isExpanded = isExpandedSections["$sectionName:$categoryGroup"] == true,
                        onToggleExpand = { onToggleCategoryGroup(sectionName, categoryGroup) },
                        onValueChange = onValueChange
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryGroup(
    categoryGroup: String,
    values: List<DataValue>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onValueChange: (String, DataValue) -> Unit
) {
    if (categoryGroup == "default" || categoryGroup.isBlank()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            values.forEach { dataValue ->
                key("${dataValue.dataElement}_${dataValue.categoryOptionCombo}") {
                    DataValueField(
                        dataValue = dataValue,
                        onValueChange = { value -> onValueChange(value, dataValue) },
                        viewModel = viewModel()
                    )
                }
            }
        }
        return
    }
    val categoryRotationState by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp, max = 56.dp) // Fixed height for uniformity
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(
                        bounded = true,
                        color = MaterialTheme.colorScheme.primary
                    ),
                    onClick = onToggleExpand
                ),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxHeight(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = categoryGroup,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand/Collapse Category",
                    modifier = Modifier
                        .rotate(categoryRotationState)
                        .size(20.dp)
                )
            }
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp)
            ) {
                values.forEach { dataValue ->
                    key("${dataValue.dataElement}_${dataValue.categoryOptionCombo}") {
                        DataValueField(
                            dataValue = dataValue,
                            onValueChange = { value -> onValueChange(value, dataValue) },
                            viewModel = viewModel()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DataValueField(
    dataValue: DataValue,
    onValueChange: (String) -> Unit,
    viewModel: DataEntryViewModel,
    enabled: Boolean = true
) {
    Log.d(
        "DataValueField",
        "Rendering field for dataElement='${dataValue.dataElement}', categoryOptionCombo='${dataValue.categoryOptionCombo}', value='${dataValue.value}'"
    )
    val key = remember(dataValue.dataElement, dataValue.categoryOptionCombo) {
        "${dataValue.dataElement}|${dataValue.categoryOptionCombo}"
    }
    // Initialize field state if not present
    LaunchedEffect(key) {
        viewModel.initializeFieldState(dataValue)
    }
    val fieldState = viewModel.fieldStates[key] ?: androidx.compose.ui.text.input.TextFieldValue(dataValue.value ?: "")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .let { base ->
                if (!enabled) {
                    base.background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        MaterialTheme.shapes.small
                    )
                } else base
            }
    ) {
        when (dataValue.dataEntryType) {
            DataEntryType.YES_NO -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        text = "Yes",
                        style = if (dataValue.value == "true") ButtonStyle.FILLED else ButtonStyle.OUTLINED,
                        colorStyle = ColorStyle.DEFAULT,
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                        onClick = { if (enabled) onValueChange("true") }
                    )
                    Button(
                        text = "No",
                        style = if (dataValue.value == "false") ButtonStyle.FILLED else ButtonStyle.OUTLINED,
                        colorStyle = ColorStyle.DEFAULT,
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                        onClick = { if (enabled) onValueChange("false") }
                    )
                }
            }
            DataEntryType.NUMBER, 
            DataEntryType.INTEGER,
            DataEntryType.POSITIVE_INTEGER,
            DataEntryType.NEGATIVE_INTEGER -> {
                InputNumber(
                    title = dataValue.dataElementName,
                    state = when {
                        !enabled -> InputShellState.DISABLED
                        dataValue.validationState == ValidationState.ERROR -> InputShellState.ERROR
                        dataValue.validationState == ValidationState.WARNING -> InputShellState.WARNING
                        else -> InputShellState.UNFOCUSED
                    },
                    inputTextFieldValue = fieldState,
                    onValueChanged = { newValue -> 
                        if (newValue != null && enabled) viewModel.onFieldValueChange(newValue, dataValue)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp)
                )
            }
            DataEntryType.PERCENTAGE -> {
                InputText(
                    title = dataValue.dataElementName,
                    state = when {
                        !enabled -> InputShellState.DISABLED
                        dataValue.validationState == ValidationState.ERROR -> InputShellState.ERROR
                        dataValue.validationState == ValidationState.WARNING -> InputShellState.WARNING
                        else -> InputShellState.UNFOCUSED
                    },
                    inputTextFieldValue = fieldState,
                    onValueChanged = { newValue -> 
                        if (newValue != null && enabled) viewModel.onFieldValueChange(newValue, dataValue)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp)
                )
            }
            else -> {
                InputText(
                    title = dataValue.dataElementName,
                    state = when {
                        !enabled -> InputShellState.DISABLED
                        dataValue.validationState == ValidationState.ERROR -> InputShellState.ERROR
                        dataValue.validationState == ValidationState.WARNING -> InputShellState.WARNING
                        else -> InputShellState.UNFOCUSED
                    },
                    inputTextFieldValue = fieldState,
                    onValueChanged = { newValue -> 
                        if (newValue != null && enabled) viewModel.onFieldValueChange(newValue, dataValue)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp)
                )
            }
        }
    }
}

@Composable
fun DataElementGridSection(
    sectionName: String,
    rowCategory: Pair<String, List<Pair<String, String>>>,
    colCategory: Pair<String, List<Pair<String, String>>>,
    dataValues: List<DataValue>,
    onValueChange: (String, DataValue) -> Unit,
    optionUidsToComboUid: Map<Set<String>, String>,
    viewModel: DataEntryViewModel
) {
    // Helper to detect gender categories
    fun isGenderCategory(category: Pair<String, List<Pair<String, String>>>): Boolean {
        val genderKeywords = listOf("gender", "sex", "male", "female")
        val name = category.first.lowercase()
        val options = category.second.map { it.second.lowercase() }
        return genderKeywords.any { k ->
            name.contains(k) || options.any { it.contains(k) }
        }
    }

    // Force gender category to be the columns (smallerCat)
    val (smallerCat, largerCat) = when {
        isGenderCategory(rowCategory) -> rowCategory to colCategory
        isGenderCategory(colCategory) -> colCategory to rowCategory
        rowCategory.second.size <= colCategory.second.size -> rowCategory to colCategory
        else -> colCategory to rowCategory
    }

    var selectedFilter by remember { mutableStateOf<String?>(null) }
    var expandedFilter by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Filter dropdown
        ExposedDropdownMenuBox(
            expanded = expandedFilter,
            onExpandedChange = { expandedFilter = !expandedFilter }
        ) {
            OutlinedTextField(
                value = selectedFilter ?: "All",
                onValueChange = {},
                readOnly = true,
                label = { Text("Filter") },
                trailingIcon = { 
                    if (selectedFilter != null) {
                        IconButton(
                            onClick = { 
                                selectedFilter = null
                                expandedFilter = false
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear Filter"
                            )
                        }
                    } else {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedFilter)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
                    .padding(bottom = 8.dp)
            )
            ExposedDropdownMenu(
                expanded = expandedFilter,
                onDismissRequest = { expandedFilter = false }
            ) {
                DropdownMenuItem(
                    text = { Text("All") },
                    onClick = {
                        selectedFilter = null
                        expandedFilter = false
                    }
                )
                (smallerCat.second + largerCat.second).map { it.second }.distinct().forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            selectedFilter = option
                            expandedFilter = false
                        }
                    )
                }
            }
        }

        val columnHeaders = listOf(largerCat.first) + smallerCat.second.map { it.second }

        val rows = largerCat.second.filter { largeOpt ->
            selectedFilter == null || largeOpt.second == selectedFilter
        }.map { largeOpt ->
            val rowCells = mutableListOf<@Composable () -> Unit>()
            // First cell in row is the row header
            rowCells.add {
                Text(
                    text = largeOpt.second,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            // Add data value fields for each column
            smallerCat.second.filter { smallOpt ->
                selectedFilter == null || smallOpt.second == selectedFilter
            }.forEach { smallOpt ->
                rowCells.add {
                    val optionUids = setOf(largeOpt.first, smallOpt.first)
                    val comboUid = optionUidsToComboUid[optionUids]
                    val cellDataValues = dataValues.filter { it.categoryOptionCombo == comboUid }
                    Column {
                        cellDataValues.forEach { dataValue ->
                            key("${dataValue.dataElement}_${dataValue.categoryOptionCombo}") {
                                DataValueField(
                                    dataValue = dataValue,
                                    onValueChange = { value -> onValueChange(value, dataValue) },
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
            rowCells.toList()
        }

        FrozenHeaderGrid(
            columnHeaders = columnHeaders,
            rows = rows,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun validateDataValue(dataValue: DataValue): ValidationState {
    // Check validation rules
    for (rule in dataValue.validationRules) {
        when (rule.rule) {
            "number" -> {
                if (dataValue.value != null && dataValue.value.isNotBlank()) {
                    if (!dataValue.value.matches(Regex("^-?\\d*\\.?\\d+$"))) {
                        return ValidationState.ERROR
                    }
                }
            }
            "coordinates" -> {
                if (dataValue.value != null && dataValue.value.isNotBlank()) {
                    if (!dataValue.value.matches(Regex("^-?\\d+\\.\\d+,-?\\d+\\.\\d+$"))) {
                        return ValidationState.ERROR
                    }
                }
            }
        }
    }
    return ValidationState.VALID
}

// --- Helper composable to render a group of data elements by category combo ---
@Composable
private fun renderCategoryComboGroup(
    sectionName: String,
    structure: List<Pair<String, List<Pair<String, String>>>>,
    values: List<DataValue>,
    expandedCategoryGroup: String?,
    onToggleCategoryGroup: (String, String) -> Unit,
    onValueChange: (String, DataValue) -> Unit,
    optionUidsToComboUid: Map<Set<String>, String>,
    viewModel: DataEntryViewModel
) {
    when (structure.size) {
        0 -> {
            // Flat list (should not happen here, handled above)
            values.forEach { dataValue ->
                DataElementRow(
                    dataElementName = dataValue.dataElementName,
                    fields = listOf(dataValue),
                    onValueChange = onValueChange
                )
            }
        }
        1 -> {
            val options = structure[0].second
            if (options.size == 2) {
                // Side-by-side fields (Rule 2)
                Row(modifier = Modifier.fillMaxWidth()) {
                    options.forEach { (optUid, optName) ->
                        val cocUid = optionUidsToComboUid.entries.find { it.key == setOf(optUid) }?.value
                        val fields = values.filter { it.categoryOptionCombo == cocUid }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(optName, fontWeight = FontWeight.Medium)
                            fields.forEach { dataValue ->
                                DataValueField(
                                    dataValue = dataValue,
                                    onValueChange = { value -> onValueChange(value, dataValue) },
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            } else {
                // Accordion with list inside (Rule 2)
                CategoryAccordion(
                    header = structure[0].first,
                    expanded = expandedCategoryGroup == "$sectionName:${structure[0].first}",
                    onToggleExpand = {
                        if (expandedCategoryGroup == "$sectionName:${structure[0].first}")
                            onToggleCategoryGroup(sectionName, "")
                        else
                            onToggleCategoryGroup(sectionName, structure[0].first)
                    }
                ) {
                    values.forEach { dataValue ->
                        DataElementRow(
                            dataElementName = dataValue.dataElementName,
                            fields = listOf(dataValue),
                            onValueChange = onValueChange
                        )
                    }
                }
            }
        }
        2 -> {
            val catA = structure[0]
            val catB = structure[1]
            val aOptions = catA.second
            val bOptions = catB.second
            // --- Begin new logic for two categories (see RENDERING_RULES.md) ---
            // Always make sex/gender category the columns in grid
            val (rowCat, colCat) = when {
                isSexCategory(catA) -> catB to catA
                isSexCategory(catB) -> catA to catB
                else -> catA to catB
            }
            if (rowCat.second.size == 2 && colCat.second.size == 2) {
                // Both have 2 options: render as grid
                Column(modifier = Modifier.padding(start = 32.dp, bottom = 2.dp)) {
                    // Header row
                    Row {
                        Spacer(modifier = Modifier.width(100.dp)) // For row header
                        colCat.second.forEach { (_, colName) ->
                            Text(
                                text = colName,
                                modifier = Modifier.width(100.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    // Data rows
                    rowCat.second.forEach { (rowUid, rowName) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = rowName,
                                modifier = Modifier.width(100.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            colCat.second.forEach { (colUid, _) ->
                                val dataValuesForCell = values.filter { dv ->
                                    val comboStructure = viewModel.state.value.categoryComboStructures[dv.categoryOptionCombo]
                                    comboStructure?.any { cat ->
                                        cat.first == rowCat.first && cat.second.any { it.first == rowUid }
                                    } == true &&
                                    comboStructure.any { cat ->
                                        cat.first == colCat.first && cat.second.any { it.first == colUid }
                                    }
                                }
                                Column(modifier = Modifier
                                    .width(100.dp)
                                    .padding(4.dp)) {
                                    dataValuesForCell.forEach { dataValue ->
                                        Text(
                                            text = dataValue.dataElementName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (colCat.second.size == 2) {
                // For each option in the header category, render an accordion header
                rowCat.second.forEach { (headerUid, headerName) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 8.dp, bottom = 4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = headerName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    // Side by side columns for contentCat options, only render elements matching both header and column
                    Row(modifier = Modifier.padding(start = 40.dp, bottom = 2.dp)) {
                        colCat.second.forEach { (optUid, optName) ->
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = optName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                                values.filter { dv ->
                                    val comboStructure = viewModel.state.value.categoryComboStructures[dv.categoryOptionCombo]
                                    comboStructure?.any { cat ->
                                        cat.first == rowCat.first && cat.second.any { it.first == headerUid }
                                    } == true &&
                                    comboStructure.any { cat ->
                                        cat.first == colCat.first && cat.second.any { it.first == optUid }
                                    }
                                }.forEach { dataValue ->
                                    Text(
                                        text = dataValue.dataElementName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Fallback: vertical list
                values.forEach { dataValue ->
                    Text(
                        text = dataValue.dataElementName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 32.dp, bottom = 2.dp)
                    )
                }
            }
            // --- End new logic for two categories ---
            Spacer(modifier = Modifier.height(12.dp))
        }
        else -> {
            // Fallback: render each element by its own combo (Rule 5)
            values.forEach { dataValue ->
                val elementStructure = viewModel.state.value.categoryComboStructures[dataValue.categoryOptionCombo] ?: emptyList()
                if (elementStructure.isEmpty()) {
                    DataElementRow(
                        dataElementName = dataValue.dataElementName,
                        fields = listOf(dataValue),
                        onValueChange = onValueChange
                    )
                } else if (elementStructure.size == 1 && elementStructure[0].second.size == 2) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        elementStructure[0].second.forEach { (optUid, optName) ->
                            val cocUid = optionUidsToComboUid.entries.find { it.key == setOf(optUid) }?.value
                            val fields = listOf(dataValue).filter { it.categoryOptionCombo == cocUid }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(optName, fontWeight = FontWeight.Medium)
                                fields.forEach { dv ->
                                    DataValueField(
                                        dataValue = dv,
                                        onValueChange = { value -> onValueChange(value, dv) },
                                        viewModel = viewModel
                                    )
                                }
                            }
                        }
                    }
                } else {
                    CategoryAccordion(
                        header = elementStructure[0].first,
                        expanded = expandedCategoryGroup == "$sectionName:${elementStructure[0].first}",
                        onToggleExpand = {
                            if (expandedCategoryGroup == "$sectionName:${elementStructure[0].first}")
                                onToggleCategoryGroup(sectionName, "")
                            else
                                onToggleCategoryGroup(sectionName, elementStructure[0].first)
                        }
                    ) {
                        DataElementRow(
                            dataElementName = dataValue.dataElementName,
                            fields = listOf(dataValue),
                            onValueChange = onValueChange
                        )
                    }
                }
            }
        }
    }
}

// Helper to detect sex/gender category
fun isSexCategory(cat: Pair<String, List<Pair<String, String>>>): Boolean {
    val name = cat.first.lowercase()
    val options = cat.second.map { it.second.lowercase() }
    return name.contains("sex") || name.contains("gender") ||
        options.any { it == "male" || it == "female" }
}

/**
 * Recursively renders nested accordions for N categories, except:
 * - If only one category with exactly two options (especially sex/gender), render side by side.
 * - If no categories, render flat list.
 * - Always collapse single-option categories.
 * - Never render sex/gender as an accordion header.
 */
@Composable
fun CategoryAccordionRecursive(
    categories: List<Pair<String, List<Pair<String, String>>>>,
    values: List<DataValue>,
    onValueChange: (String, DataValue) -> Unit,
    optionUidsToComboUid: Map<Set<String>, String>,
    viewModel: DataEntryViewModel,
    parentPath: List<String> = emptyList(),
    expandedAccordions: Map<List<String>, String?>,
    onToggle: (List<String>, String) -> Unit,
) {
    if (categories.isEmpty()) {
        values.forEach { dataValue ->
            DataElementRow(
                dataElementName = dataValue.dataElementName,
                fields = listOf(dataValue),
                onValueChange = onValueChange
            )
        }
        return
    }
    val currentCategory = categories.first()
    val restCategories = categories.drop(1)
    if (restCategories.isEmpty()) {
        // LAST CATEGORY: If <= 3 options, render as a row; if > 3, render each as a nested accordion
        if (currentCategory.second.size <= 3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                currentCategory.second.forEach { (optionUid, optionName) ->
                    val fullPath = parentPath + optionUid
                    val comboUid = optionUidsToComboUid[fullPath.toSet()]
                    val filteredValues = values.filter { it.categoryOptionCombo == comboUid }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = optionName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        filteredValues.forEach { dataValue ->
                            DataElementRow(
                                dataElementName = dataValue.dataElementName,
                                fields = listOf(dataValue),
                                onValueChange = onValueChange
                            )
                        }
                    }
                }
            }
        } else {
            // More than 3 options: render each as a nested accordion
            currentCategory.second.forEach { (optionUid, optionName) ->
                val expanded = expandedAccordions[parentPath] == optionUid
                Box(modifier = Modifier.padding(bottom = 8.dp)) {
                    CategoryAccordion(
                        header = optionName,
                        expanded = expanded,
                        onToggleExpand = { onToggle(parentPath, optionUid) }
                    ) {
                        val fullPath = parentPath + optionUid
                        val comboUid = optionUidsToComboUid[fullPath.toSet()]
                        val filteredValues = values.filter { it.categoryOptionCombo == comboUid }
                        filteredValues.forEach { dataValue ->
                            DataElementRow(
                                dataElementName = dataValue.dataElementName,
                                fields = listOf(dataValue),
                                onValueChange = onValueChange
                            )
                        }
                    }
                }
            }
        }
        return
    }
    // For all other categories, render as accordions, filtering by current path
    currentCategory.second.forEach { (optionUid, optionName) ->
        val expanded = expandedAccordions[parentPath] == optionUid
        Box(modifier = Modifier.padding(bottom = 8.dp)) {
            CategoryAccordion(
                header = optionName,
                expanded = expanded,
                onToggleExpand = { onToggle(parentPath, optionUid) }
            ) {
                val newPath = parentPath + optionUid
                val comboUid = optionUidsToComboUid[newPath.toSet()]
                val filteredValues = if (comboUid != null) values.filter { it.categoryOptionCombo == comboUid } else values
                CategoryAccordionRecursive(
                    categories = restCategories,
                    values = filteredValues,
                    onValueChange = onValueChange,
                    optionUidsToComboUid = optionUidsToComboUid,
                    viewModel = viewModel,
                    parentPath = newPath,
                    expandedAccordions = expandedAccordions,
                    onToggle = onToggle
                )
            }
        }
    }
}


/**
 * Enhanced recursive accordion component that renders actual data value fields at the final category level.
 * This ensures entry fields are displayed when category accordions are opened.
 */
@Composable
fun CategoryAccordionRecursiveWithFields(
    categories: List<Pair<String, List<Pair<String, String>>>>,
    values: List<DataValue>,
    onValueChange: (String, DataValue) -> Unit,
    optionUidsToComboUid: Map<Set<String>, String>,
    viewModel: DataEntryViewModel,
    parentPath: List<String> = emptyList(),
    expandedAccordions: Map<List<String>, String?>,
    onToggle: (List<String>, String) -> Unit,
    selectedOptions: Set<String> = emptySet()
) {
    if (categories.isEmpty()) {
        // Base case: no more categories, render data value fields directly
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            values.forEach { dataValue ->
                DataValueField(
                    dataValue = dataValue,
                    onValueChange = { value -> onValueChange(value, dataValue) },
                    viewModel = viewModel
                )
            }
        }
        return
    }
    
    val currentCategory = categories.first()
    val restCategories = categories.drop(1)
    
    if (restCategories.isEmpty()) {
        // FINAL CATEGORY LEVEL: Render data value fields for each option
        if (currentCategory.second.size <= 3) {
            // Few options: render side by side with fields
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                currentCategory.second.forEach { (optionUid, optionName) ->
                    Column(modifier = Modifier.weight(1f)) {
                        // Find matching data values for EXACT option combination
                        val currentSelectionWithThisOption = selectedOptions + optionUid
                        val expectedComboUid = optionUidsToComboUid[currentSelectionWithThisOption]
                        val matchingValues = values.filter { dataValue ->
                            // Use the exact combo mapping to find values for this specific combination
                            dataValue.categoryOptionCombo == expectedComboUid
                        }
                        
                        android.util.Log.d("CategoryAccordion", "Final level - Option: $optionName, Selected: $currentSelectionWithThisOption, Expected combo: $expectedComboUid, Matching: ${matchingValues.size}/${values.size}")
                        
                        Text(
                            text = optionName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            matchingValues.forEach { dataValue ->
                                DataValueField(
                                    dataValue = dataValue,
                                    onValueChange = { value -> onValueChange(value, dataValue) },
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Many options: render as accordion with fields inside
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                currentCategory.second.forEach { (optionUid, optionName) ->
                    val currentPath = parentPath + listOf("cat_${currentCategory.first}_$optionUid")
                    val expanded = expandedAccordions[currentPath] != null
                    
                    // Find matching data values for EXACT option combination
                    val currentSelectionWithThisOption = selectedOptions + optionUid
                    val expectedComboUid = optionUidsToComboUid[currentSelectionWithThisOption]
                    val matchingValues = values.filter { dataValue ->
                        // Use the exact combo mapping to find values for this specific combination
                        dataValue.categoryOptionCombo == expectedComboUid
                    }
                    
                    android.util.Log.d("CategoryAccordion", "Final accordion - Option: $optionName, Selected: $currentSelectionWithThisOption, Expected combo: $expectedComboUid, Matching: ${matchingValues.size}/${values.size}")
                    
                    CategoryAccordion(
                        header = optionName,
                        expanded = expanded,
                        onToggleExpand = { onToggle(currentPath, "expanded") }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            matchingValues.forEach { dataValue ->
                                DataValueField(
                                    dataValue = dataValue,
                                    onValueChange = { value -> onValueChange(value, dataValue) },
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        // INTERMEDIATE CATEGORY LEVELS: Continue recursion with nested accordions
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            currentCategory.second.forEach { (optionUid, optionName) ->
                val currentPath = parentPath + listOf("cat_${currentCategory.first}_$optionUid")
                val expanded = expandedAccordions[currentPath] != null
                
                // Filter values using consistent logic for N-category robustness
                val currentSelectionWithThisOption = selectedOptions + optionUid
                val filteredValues = values.filter { dataValue ->
                    // Use the same exact matching approach as final level for consistency
                    // This ensures the filtering is mathematically correct for any number of categories
                    val comboStructure = viewModel.state.value.categoryComboStructures[dataValue.categoryOptionCombo]
                    val comboOptions = comboStructure?.flatMap { it.second.map { option -> option.first } }?.toSet() ?: emptySet()
                    
                    // For intermediate levels: keep combinations that contain ALL our selected options
                    // This will progressively filter down until final level has exact matches
                    currentSelectionWithThisOption.all { it in comboOptions }
                }
                
                android.util.Log.d("CategoryAccordion", "Intermediate level - Option: $optionName, Selected: $currentSelectionWithThisOption, Filtered: ${filteredValues.size}/${values.size}, Remaining categories: ${restCategories.size}")
                
                // Remove count as requested - just show option name
                val headerText = optionName
                
                CategoryAccordion(
                    header = headerText,
                    expanded = expanded,
                    onToggleExpand = { onToggle(currentPath, "expanded") }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CategoryAccordionRecursiveWithFields(
                            categories = restCategories,
                            values = filteredValues,
                            onValueChange = onValueChange,
                            optionUidsToComboUid = optionUidsToComboUid,
                            viewModel = viewModel,
                            parentPath = currentPath,
                            expandedAccordions = expandedAccordions,
                            onToggle = onToggle,
                            selectedOptions = currentSelectionWithThisOption
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sorts categories by option count (descending) to determine precedence.
 * Categories with more options take precedence over position in combination definition.
 */
private fun sortCategoriesByOptionCount(
    categories: List<Pair<String, List<Pair<String, String>>>>
): List<Pair<String, List<Pair<String, String>>>> {
    return categories.sortedByDescending { it.second.size }
}


