@file:OptIn(ExperimentalMaterial3Api::class)

package com.ash.simpledataentry.presentation.tracker

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ash.simpledataentry.presentation.core.AdaptiveLoadingOverlay
import com.ash.simpledataentry.presentation.core.DatePickerDialog
import com.ash.simpledataentry.presentation.core.LoadingOperation
import com.ash.simpledataentry.presentation.core.UiState
import com.ash.simpledataentry.domain.model.DataValue
import com.ash.simpledataentry.domain.model.DataEntryType
import org.hisp.dhis.mobile.ui.designsystem.component.InputText
import org.hisp.dhis.mobile.ui.designsystem.component.InputNumber
import org.hisp.dhis.mobile.ui.designsystem.component.InputShellState
import androidx.compose.ui.text.input.TextFieldValue
import com.ash.simpledataentry.presentation.datasetInstances.SyncConfirmationDialog
import com.ash.simpledataentry.presentation.datasetInstances.SyncOptions
import com.ash.simpledataentry.presentation.dataEntry.components.OptionSetDropdown
import com.ash.simpledataentry.presentation.dataEntry.components.OptionSetRadioGroup
import com.ash.simpledataentry.domain.model.computeRenderType
import com.ash.simpledataentry.domain.model.RenderType
import com.ash.simpledataentry.presentation.core.Section
import com.ash.simpledataentry.presentation.core.SectionNavigationBar
import com.ash.simpledataentry.presentation.core.StepLoadingType
import com.ash.simpledataentry.ui.theme.DHIS2Blue
import com.ash.simpledataentry.ui.theme.DHIS2BlueDark
import com.ash.simpledataentry.ui.theme.DHIS2BlueLight
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EventCaptureScreen(
    viewModel: EventCaptureViewModel = hiltViewModel(),
    navController: NavController,
    programId: String,
    programStageId: String? = null,
    enrollmentId: String? = null,
    eventId: String? = null
) {
    val state by viewModel.state.collectAsState()
    val rawOverlayState by viewModel.uiState.collectAsState()
    val overlayState = rawOverlayState
    val adaptiveUiState = remember(overlayState) {
        when (overlayState) {
            is UiState.Loading -> overlayState
            else -> UiState.Success(Unit)
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val syncInProgress = overlayState is UiState.Loading &&
        (overlayState.operation is LoadingOperation.Syncing ||
            (overlayState.operation is LoadingOperation.Navigation &&
                overlayState.operation.progress.loadingType == StepLoadingType.SYNC))

    var showSaveDialog by remember { mutableStateOf(false) }
    var showSyncDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showOrgUnitPicker by remember { mutableStateOf(false) }
    var showPostSaveDialog by remember { mutableStateOf(false) }
    val pendingNavAction = remember { mutableStateOf<(() -> Unit)?>(null) }

    // Initialize event data
    LaunchedEffect(programId, programStageId, enrollmentId, eventId) {
        viewModel.initializeEvent(programId, programStageId, enrollmentId, eventId)
    }

    // Reuse EditEntry unsaved changes detection pattern
    val hasUnsavedChanges = viewModel.hasUnsavedChanges()

    // Back handler for unsaved changes
    BackHandler(enabled = hasUnsavedChanges && !state.saveInProgress) {
        showSaveDialog = true
        pendingNavAction.value = { navController.popBackStack() }
    }

    // Navigation icon with unsaved changes protection
    val navigationIcon: @Composable (() -> Unit) = {
        IconButton(onClick = {
            if (state.saveInProgress) return@IconButton
            if (hasUnsavedChanges) {
                showSaveDialog = true
                pendingNavAction.value = { navController.popBackStack() }
            } else {
                navController.popBackStack()
            }
        }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back"
            )
        }
    }

    // Save confirmation dialog (reuse EditEntry pattern)
    if (showSaveDialog && hasUnsavedChanges) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Unsaved Changes") },
            text = { Text("You have unsaved changes. Save before leaving?") },
            confirmButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    viewModel.saveEvent()
                    pendingNavAction.value?.invoke()
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showSaveDialog = false
                        pendingNavAction.value?.invoke()
                    }) { Text("Discard") }
                    TextButton(onClick = {
                        showSaveDialog = false
                    }) { Text("Cancel") }
                }
            }
        )
    }

    // Sync confirmation dialog (reuse EditEntry pattern)
    if (showSyncDialog) {
        SyncConfirmationDialog(
            syncOptions = SyncOptions(
                uploadLocalData = true,
                localInstanceCount = 1,
                isEditEntryContext = false
            ),
            onConfirm = { _ ->
                viewModel.syncEvent()
                showSyncDialog = false
            },
            onDismiss = {
                showSyncDialog = false
            }
        )
    }

    // Date picker dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDateSelected = { date ->
                viewModel.updateEventDate(date)
                showDatePicker = false
            },
            onDismissRequest = { showDatePicker = false },
            initialDate = state.eventDate ?: Date()
        )
    }

    // Organization unit picker dialog
    if (showOrgUnitPicker) {
        AlertDialog(
            onDismissRequest = { showOrgUnitPicker = false },
            title = { Text("Select Organization Unit") },
            text = {
                LazyColumn {
                    items(items = state.availableOrganisationUnits, key = { it.id }) { orgUnit ->
                        TextButton(
                            onClick = {
                                viewModel.updateOrganisationUnit(orgUnit.id)
                                showOrgUnitPicker = false
                            }
                        ) {
                            Text(orgUnit.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOrgUnitPicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Show snackbar messages (reuse EditEntry pattern)
    var lastHandledSaveResult by remember { mutableStateOf<Result<Unit>?>(null) }
    LaunchedEffect(state.saveResult) {
        val result = state.saveResult
        if (result != null && result != lastHandledSaveResult) {
            lastHandledSaveResult = result
            if (result.isSuccess) {
                showPostSaveDialog = true
            }
        }
    }

    LaunchedEffect(state.successMessage) {
        state.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    if (showPostSaveDialog) {
        AlertDialog(
            onDismissRequest = { showPostSaveDialog = false },
            title = { Text("Saved") },
            text = { Text("Mark this event as complete?") },
            confirmButton = {
                TextButton(onClick = {
                    showPostSaveDialog = false
                    viewModel.completeEvent()
                }) {
                    Text("Complete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPostSaveDialog = false }) {
                    Text("Not now")
                }
            }
        )
    }

    val sections = remember(state.dataValues) {
        state.dataValues.groupBy { it.sectionName }
    }
    val sectionNames = remember(sections) {
        sections.keys.toList()
    }
    var currentSectionIndex by remember(sectionNames) { mutableStateOf(0) }
    var expandedSection by remember(sectionNames) { mutableStateOf(sectionNames.firstOrNull()) }
    var pendingScrollIndex by remember { mutableStateOf<Int?>(null) }
    if (currentSectionIndex !in sectionNames.indices) {
        currentSectionIndex = 0
    }
    if (expandedSection !in sectionNames) {
        expandedSection = sectionNames.firstOrNull()
    }
    if (pendingScrollIndex != null && pendingScrollIndex !in sectionNames.indices) {
        pendingScrollIndex = null
    }
    val currentSectionName = sectionNames.getOrNull(currentSectionIndex) ?: "Section"

    val selectSection: (Int, Boolean) -> Unit = { index, shouldScroll ->
        if (index in sectionNames.indices) {
            currentSectionIndex = index
            expandedSection = sectionNames[index]
            pendingScrollIndex = if (shouldScroll) index else null
        }
    }

    LaunchedEffect(pendingScrollIndex, sectionNames.size) {
        val target = pendingScrollIndex ?: return@LaunchedEffect
        val sectionStartIndex = 1
        listState.animateScrollToItem(index = sectionStartIndex + target)
        pendingScrollIndex = null
    }

    // Main screen with Material 3 Scaffold (reuse TrackerEnrollment pattern)
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = state.programName.ifBlank { "Event Capture" },
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (state.programStageName.isNotBlank()) {
                                Text(
                                    text = state.programStageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = navigationIcon,
                    actions = {
                        // Sync button
                        IconButton(
                            onClick = { showSyncDialog = true },
                            enabled = !syncInProgress
                        ) {
                            if (syncInProgress) {
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
                    }
                )

            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!state.saveInProgress) {
                        viewModel.saveEvent()
                    }
                },
                containerColor = if (state.saveInProgress) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                contentColor = if (state.saveInProgress) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimary
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Save event"
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        AdaptiveLoadingOverlay(
            uiState = adaptiveUiState,
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(DHIS2Blue, DHIS2BlueDark)
                                )
                            )
                    )
                }
                state.error != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(DHIS2Blue, DHIS2BlueDark)
                                )
                            )
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Error loading event",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = state.error ?: "Unknown error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                else -> {
                    val gradientBrush = Brush.verticalGradient(
                        colors = listOf(DHIS2Blue, DHIS2BlueDark)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .background(gradientBrush)
                            .padding(horizontal = 20.dp, vertical = 24.dp)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            ) {
                                // Show warning banner if rule evaluation had issues
                                if (state.ruleEvaluationWarning != null) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = state.ruleEvaluationWarning ?: "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(onClick = { viewModel.clearRuleWarning() }) {
                                                Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer)
                                            }
                                        }
                                    }
                                }

                                if (sectionNames.isNotEmpty()) {
                                    SectionNavigationBar(
                                        currentSection = Section(currentSectionName),
                                        currentSubsection = null,
                                        sectionIndex = currentSectionIndex.coerceAtLeast(0),
                                        totalSections = sectionNames.size.coerceAtLeast(1),
                                        onPreviousSection = {
                                            selectSection((currentSectionIndex - 1).coerceAtLeast(0), true)
                                        },
                                        onNextSection = {
                                            selectSection((currentSectionIndex + 1).coerceAtMost(sectionNames.lastIndex), true)
                                        },
                                        onPreviousSubsection = {},
                                        onNextSubsection = {},
                                        canPreviousSubsection = false,
                                        canNextSubsection = false,
                                        hasSubsections = false,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }

                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Event metadata section
                                    item {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Text(
                                                text = "Event Details",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )

                                            // Event date picker
                                            OutlinedTextField(
                                                value = state.eventDate?.let {
                                                    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(it)
                                                } ?: "",
                                                onValueChange = { },
                                                label = { Text("Event Date *") },
                                                readOnly = true,
                                                modifier = Modifier.fillMaxWidth(),
                                                trailingIcon = {
                                                    TextButton(onClick = { showDatePicker = true }) {
                                                        Text("Select")
                                                    }
                                                }
                                            )

                                            // Organization unit picker (for standalone events)
                                            if (state.enrollmentId == null && state.availableOrganisationUnits.isNotEmpty()) {
                                                OutlinedTextField(
                                                    value = state.availableOrganisationUnits.find {
                                                        it.id == state.selectedOrganisationUnitId
                                                    }?.name ?: "",
                                                    onValueChange = { },
                                                    label = { Text("Organisation Unit *") },
                                                    readOnly = true,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    trailingIcon = {
                                                        TextButton(onClick = { showOrgUnitPicker = true }) {
                                                            Text("Select")
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    // Data values with nested accordion structure
                                    // Level 1: Sections (program stage sections)
                                    // Level 2+: Implied categories (inferred from data element names)
                                    // Leaf: Data value fields
                                    if (state.dataValues.isEmpty()) {
                                        item {
                                            Column {
                                                Text(
                                                    text = "Event Data",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "No data elements found for this event",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(top = 8.dp)
                                                )
                                            }
                                        }
                                    } else {
                                        sectionNames.forEach { sectionName ->
                                            val sectionValues = sections[sectionName] ?: emptyList()
                                            item {
                                                EventSectionAccordion(
                                                    sectionName = sectionName,
                                                    dataValues = sectionValues,
                                                    impliedCombination = state.impliedCategoriesBySection[sectionName],
                                                    impliedMappings = state.impliedCategoryMappingsBySection[sectionName] ?: emptyList(),
                                                    hasData = state.sectionHasData[sectionName] == true,
                                                    isExpanded = expandedSection == sectionName,
                                                    onToggle = {
                                                        expandedSection = if (expandedSection == sectionName) {
                                                            null
                                                        } else {
                                                            selectSection(sectionNames.indexOf(sectionName), false)
                                                            sectionName
                                                        }
                                                    },
                                                    onValueChange = { value, dataValue ->
                                                        viewModel.updateDataValue(value, dataValue)
                                                    },
                                                    onValueCommitted = { viewModel.onFieldCommit() },
                                                    programRuleEffect = state.programRuleEffect
                                                )
                                            }
                                        }
                                    }

                                    // Validation errors
                                    if (state.validationErrors.isNotEmpty()) {
                                        item {
                                            Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                                )
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(16.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(
                                                        text = "Validation Errors",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onErrorContainer
                                                    )
                                                    state.validationErrors.forEach { error ->
                                                        Text(
                                                            text = "• $error",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onErrorContainer
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Success message
                                    state.successMessage?.let { message ->
                                        item {
                                            Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                                )
                                            ) {
                                                Text(
                                                    text = message,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(16.dp)
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

}

/**
 * Section-level accordion for event/tracker programs
 * Sections are the first level of grouping from program stage sections
 */
@Composable
private fun EventSectionAccordion(
    sectionName: String,
    dataValues: List<DataValue>,
    impliedCombination: com.ash.simpledataentry.domain.model.ImpliedCategoryCombination?,
    impliedMappings: List<com.ash.simpledataentry.domain.model.ImpliedCategoryMapping>,
    hasData: Boolean,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onValueChange: (String, DataValue) -> Unit,
    onValueCommitted: () -> Unit,
    programRuleEffect: com.ash.simpledataentry.domain.model.ProgramRuleEffect?
) {
    val totalElements = dataValues.size
    val elementsWithData = dataValues.count { !it.value.isNullOrBlank() }

    EventDataElementAccordion(
        header = "$sectionName ($elementsWithData/$totalElements elements)",
        hasData = hasData,
        expanded = isExpanded,
        onToggleExpand = onToggle
    ) {
        if (impliedCombination != null && impliedMappings.isNotEmpty()) {
            // Render nested accordions based on implied categories
            ImpliedCategoryGroupRecursive(
                mappings = impliedMappings,
                categories = impliedCombination.categories,
                dataValues = dataValues,
                onValueChange = onValueChange,
                onValueCommitted = onValueCommitted,
                programRuleEffect = programRuleEffect,
                level = 0
            )
            val mappedIds = remember(impliedMappings) {
                impliedMappings.map { it.dataElementId }.toSet()
            }
            val unmappedValues = remember(dataValues, mappedIds) {
                dataValues.filterNot { it.dataElement in mappedIds }
            }
            if (unmappedValues.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                unmappedValues.forEach { dataValue ->
                    key(dataValue.dataElement) {
                        EventDataValueField(
                            dataValue = dataValue,
                            onValueChange = { value -> onValueChange(value, dataValue) },
                            onValueCommitted = onValueCommitted,
                            programRuleEffect = programRuleEffect,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        } else {
            // No implied categories - render flat list
            dataValues.forEach { dataValue ->
                key(dataValue.dataElement) {
                    EventDataValueField(
                        dataValue = dataValue,
                        onValueChange = { value -> onValueChange(value, dataValue) },
                        onValueCommitted = onValueCommitted,
                        programRuleEffect = programRuleEffect,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventDataElementAccordion(
    header: String,
    hasData: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    content: @Composable () -> Unit
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left accent border
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .heightIn(min = 64.dp)
                    .background(
                        if (hasData) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                    )
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onToggleExpand()
                            if (!expanded) {
                                coroutineScope.launch {
                                    bringIntoViewRequester.bringIntoView()
                                }
                            }
                        }
                        .bringIntoViewRequester(bringIntoViewRequester)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = header,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (hasData) "Has data" else "No data",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasData) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand/Collapse",
                        modifier = Modifier
                            .rotate(if (expanded) 180f else 0f)
                            .size(24.dp)
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 16.dp)
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

/**
 * Nested accordion for implied categories
 * Recursively renders category levels from the inferred structure.
 */
@Composable
private fun ImpliedCategoryGroupRecursive(
    mappings: List<com.ash.simpledataentry.domain.model.ImpliedCategoryMapping>,
    categories: List<com.ash.simpledataentry.domain.model.ImpliedCategory>,
    dataValues: List<DataValue>,
    onValueChange: (String, DataValue) -> Unit,
    onValueCommitted: () -> Unit,
    programRuleEffect: com.ash.simpledataentry.domain.model.ProgramRuleEffect?,
    level: Int = 0
) {
    if (level >= categories.size) {
        mappings.forEach { mapping ->
            val dataValue = dataValues.find { it.dataElement == mapping.dataElementId }
            if (dataValue != null) {
                key(dataValue.dataElement) {
                    EventDataValueField(
                        dataValue = dataValue,
                        onValueChange = { value -> onValueChange(value, dataValue) },
                        onValueCommitted = onValueCommitted,
                        programRuleEffect = programRuleEffect,
                        labelOverride = mapping.fieldName,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
        return
    }

    val currentCategory = categories[level]
    val groupedByCurrentLevel = mappings
        .mapNotNull { mapping ->
            val option = mapping.categoryOptionsByLevel[currentCategory.level] ?: return@mapNotNull null
            option to mapping
        }
        .groupBy({ it.first }, { it.second })
    val unmappedAtCurrentLevel = mappings.filter { it.categoryOptionsByLevel[currentCategory.level] == null }

    if (groupedByCurrentLevel.isEmpty()) {
        mappings.forEach { mapping ->
            val dataValue = dataValues.find { it.dataElement == mapping.dataElementId }
            if (dataValue != null) {
                key(dataValue.dataElement) {
                    EventDataValueField(
                        dataValue = dataValue,
                        onValueChange = { value -> onValueChange(value, dataValue) },
                        onValueCommitted = onValueCommitted,
                        programRuleEffect = programRuleEffect,
                        labelOverride = mapping.fieldName,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
        return
    }

    if (level == categories.lastIndex && groupedByCurrentLevel.size <= 3) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            groupedByCurrentLevel.forEach { (optionName, submappings) ->
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = optionName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    submappings.forEach { mapping ->
                        val dataValue = dataValues.find { it.dataElement == mapping.dataElementId }
                        if (dataValue != null) {
                            key(dataValue.dataElement) {
                                EventDataValueField(
                                    dataValue = dataValue,
                                    onValueChange = { value -> onValueChange(value, dataValue) },
                                    onValueCommitted = onValueCommitted,
                                    programRuleEffect = programRuleEffect,
                                    labelOverride = mapping.fieldName,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    groupedByCurrentLevel.forEach { (optionName, submappings) ->
        key("${level}_$optionName") {
            var isExpanded by remember { mutableStateOf(false) }
            EventCategoryAccordion(
                header = optionName,
                expanded = isExpanded,
                onToggleExpand = { isExpanded = !isExpanded }
            ) {
                ImpliedCategoryGroupRecursive(
                    mappings = submappings,
                    categories = categories,
                    dataValues = dataValues,
                    onValueChange = onValueChange,
                    onValueCommitted = onValueCommitted,
                    programRuleEffect = programRuleEffect,
                    level = level + 1
                )
            }
        }
    }

    if (unmappedAtCurrentLevel.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        unmappedAtCurrentLevel.forEach { mapping ->
        val dataValue = dataValues.find { it.dataElement == mapping.dataElementId }
        if (dataValue != null) {
            key("unmapped_${level}_${dataValue.dataElement}") {
                EventDataValueField(
                    dataValue = dataValue,
                    onValueChange = { value -> onValueChange(value, dataValue) },
                    onValueCommitted = onValueCommitted,
                    programRuleEffect = programRuleEffect,
                    labelOverride = mapping.fieldName,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventCategoryAccordion(
    header: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    content: @Composable () -> Unit
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable {
                    onToggleExpand()
                    if (!expanded) {
                        coroutineScope.launch {
                            bringIntoViewRequester.bringIntoView()
                        }
                    }
                },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = header,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
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

        // Category content
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 8.dp)
                    .bringIntoViewRequester(bringIntoViewRequester)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun EventDataValueField(
    dataValue: DataValue,
    onValueChange: (String) -> Unit,
    onValueCommitted: () -> Unit,
    programRuleEffect: com.ash.simpledataentry.domain.model.ProgramRuleEffect? = null,
    labelOverride: String? = null,
    modifier: Modifier = Modifier
) {
    // Skip rendering if field is hidden by program rules
    if (dataValue.dataElement in (programRuleEffect?.hiddenFields ?: emptySet())) {
        return
    }

    val value = dataValue.value ?: ""
    val label = labelOverride?.ifBlank { null } ?: dataValue.dataElementName
    // Fix cursor jumping: Don't recreate TextFieldValue on every value change
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(value))
    }
    var isFocused by remember { mutableStateOf(false) }
    val focusModifier = modifier.onFocusChanged { focusState ->
        if (isFocused && !focusState.isFocused) {
            onValueCommitted()
        }
        isFocused = focusState.isFocused
    }

    // Update text field when external value changes (e.g., from ViewModel)
    LaunchedEffect(value) {
        if (textFieldValue.text != value) {
            // Preserve cursor position when updating value from external source
            val selection = textFieldValue.selection
            textFieldValue = TextFieldValue(
                text = value,
                selection = androidx.compose.ui.text.TextRange(
                    start = selection.start.coerceAtMost(value.length),
                    end = selection.end.coerceAtMost(value.length)
                )
            )
        }
    }

    Column(modifier = modifier) {
        // Check if data value has an option set
        if (dataValue.optionSet != null) {
            val optionSet = dataValue.optionSet!!
            val renderType = optionSet.computeRenderType()

            when (renderType) {
                RenderType.DROPDOWN, RenderType.DEFAULT -> {
                    OptionSetDropdown(
                        optionSet = optionSet,
                        selectedCode = dataValue.value,
                        title = label,
                        onOptionSelected = { code ->
                            onValueChange(code ?: "")
                            onValueCommitted()
                        },
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                RenderType.RADIO_BUTTONS, RenderType.YES_NO_BUTTONS -> {
                    OptionSetRadioGroup(
                        optionSet = optionSet,
                        selectedCode = dataValue.value,
                        title = label,
                        onOptionSelected = { code ->
                            onValueChange(code ?: "")
                            onValueCommitted()
                        },
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                else -> {
                    // Fallback to dropdown for other render types
                    OptionSetDropdown(
                        optionSet = optionSet,
                        selectedCode = dataValue.value,
                        title = label,
                        onOptionSelected = { code ->
                            onValueChange(code ?: "")
                            onValueCommitted()
                        },
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        } else when (dataValue.dataEntryType) {
        DataEntryType.NUMBER -> {
            InputNumber(
                title = label,
                state = InputShellState.UNFOCUSED,
                inputTextFieldValue = textFieldValue,
                onValueChanged = { newValue: TextFieldValue? ->
                    newValue?.let { textField ->
                        textFieldValue = textField
                        onValueChange(textField.text)
                    }
                },
                modifier = focusModifier.padding(vertical = 4.dp)
            )
        }
        DataEntryType.PERCENTAGE -> {
            InputNumber(
                title = label,
                state = InputShellState.UNFOCUSED,
                inputTextFieldValue = textFieldValue,
                onValueChanged = { newValue: TextFieldValue? ->
                    newValue?.let { textField ->
                        textFieldValue = textField
                        onValueChange(textField.text)
                    }
                },
                modifier = focusModifier.padding(vertical = 4.dp)
            )
        }
        DataEntryType.YES_NO -> {
            // Use a simple dropdown for Yes/No since YesNoField is not available
            var expanded by remember { mutableStateOf(false) }
            val yesNoOptions = listOf("", "true", "false")
            val selectedValue = when (value.lowercase()) {
                "true", "1", "yes" -> "true"
                "false", "0", "no" -> "false"
                else -> ""
            }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = modifier.padding(vertical = 4.dp)
            ) {
                OutlinedTextField(
                    value = when (selectedValue) {
                        "true" -> "Yes"
                        "false" -> "No"
                        else -> "Select..."
                    },
                    onValueChange = { },
                    readOnly = true,
                    label = { Text(label) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Select...") },
                        onClick = {
                            onValueChange("")
                            onValueCommitted()
                            expanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Yes") },
                        onClick = {
                            onValueChange("true")
                            onValueCommitted()
                            expanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("No") },
                        onClick = {
                            onValueChange("false")
                            onValueCommitted()
                            expanded = false
                        }
                    )
                }
            }
        }
        else -> {
            InputText(
                title = label,
                state = InputShellState.UNFOCUSED,
                inputTextFieldValue = textFieldValue,
                onValueChanged = { newValue: TextFieldValue? ->
                    newValue?.let { textField ->
                        textFieldValue = textField
                        onValueChange(textField.text)
                    }
                },
                modifier = focusModifier.padding(vertical = 4.dp)
            )
        }
        }

        // Display program rule warnings
        programRuleEffect?.fieldWarnings?.get(dataValue.dataElement)?.let { warning ->
            Text(
                text = "⚠ $warning",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }

        // Display program rule errors
        programRuleEffect?.fieldErrors?.get(dataValue.dataElement)?.let { error ->
            Text(
                text = "❌ $error",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
    }
}
