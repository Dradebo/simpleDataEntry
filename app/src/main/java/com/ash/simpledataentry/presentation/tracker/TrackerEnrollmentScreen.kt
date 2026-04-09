package com.ash.simpledataentry.presentation.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ash.simpledataentry.presentation.core.AdaptiveLoadingOverlay
import com.ash.simpledataentry.presentation.core.BaseScreen
import com.ash.simpledataentry.presentation.core.DatePickerDialog
import com.ash.simpledataentry.presentation.core.Section
import com.ash.simpledataentry.presentation.core.SectionNavigationBar
import com.ash.simpledataentry.presentation.core.SimpleProgressOverlay
import com.ash.simpledataentry.presentation.core.StepLoadingType
import com.ash.simpledataentry.presentation.core.ValidationErrorDialog
import com.ash.simpledataentry.domain.model.TrackedEntityAttributeValue
import com.ash.simpledataentry.ui.theme.DHIS2Blue
import com.ash.simpledataentry.ui.theme.DHIS2BlueDark
import com.ash.simpledataentry.ui.theme.DHIS2BlueLight
import org.hisp.dhis.mobile.ui.designsystem.component.*
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch
import java.net.URLDecoder

/**
 * Screen for creating and editing tracker enrollments
 * Reuses EditEntryScreen patterns and UI components
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerEnrollmentScreen(
    navController: NavController,
    programId: String,
    programName: String,
    enrollmentId: String? = null, // null for new enrollment
    viewModel: TrackerEnrollmentViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showSaveDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val sectionNames = remember(state.trackedEntityAttributes) {
        buildList {
            add("Enrollment Information")
            if (state.trackedEntityAttributes.isNotEmpty()) {
                add("Personal Information")
            }
        }
    }
    var currentSectionIndex by remember(sectionNames) { mutableStateOf(0) }
    if (currentSectionIndex !in sectionNames.indices) {
        currentSectionIndex = 0
    }
    val sectionStartIndexes = remember(sectionNames) {
        sectionNames.mapIndexed { index, _ ->
            if (index == 0) 0 else 1
        }
    }
    val scrollToSection: (Int) -> Unit = { index ->
        if (index in sectionNames.indices) {
            currentSectionIndex = index
        }
    }
    LaunchedEffect(currentSectionIndex, sectionStartIndexes) {
        sectionStartIndexes.getOrNull(currentSectionIndex)?.let { listState.animateScrollToItem(it) }
    }

    // Initialize the view model with parameters
    LaunchedEffect(programId, enrollmentId) {
        if (enrollmentId != null) {
            viewModel.loadEnrollment(enrollmentId)
        } else {
            viewModel.initializeNewEnrollment(programId)
        }
    }

    // Handle save success navigation
    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) {
            navController.popBackStack()
        }
    }

    // Show snackbar for messages (reuse DataEntry pattern)
    LaunchedEffect(state.error, state.successMessage) {
        state.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearMessages()
        }
        state.successMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessages()
        }
    }

    val title = if (state.isEditMode) "Edit Enrollment" else "New Enrollment"
    val decodedProgramName = remember(programName) { URLDecoder.decode(programName, "UTF-8") }
    val progressValue = state.detailedSyncProgress?.overallPercentage?.let { it / 100f }
        ?: state.navigationProgress?.overallPercentage?.let { it / 100f }
    val syncInProgress = state.detailedSyncProgress != null ||
        state.isSyncing ||
        state.navigationProgress?.loadingType == StepLoadingType.SYNC

    val syncProgress = state.detailedSyncProgress

    BaseScreen(
        title = title,
        subtitle = decodedProgramName,
        navController = navController,
        navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        syncStatusController = viewModel.syncController,
        showProgress = state.isLoading || syncInProgress || state.navigationProgress != null,
        progress = progressValue,
        actions = {
            IconButton(
                onClick = { viewModel.syncEnrollment() },
                enabled = !syncInProgress && !state.isLoading
            ) {
                if (syncInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Sync"
                    )
                }
            }
        }
    ) {
        AdaptiveLoadingOverlay(
            uiState = uiState,
            modifier = Modifier.fillMaxSize()
        ) {
            val gradientBrush = Brush.verticalGradient(
                colors = listOf(DHIS2Blue, DHIS2BlueDark)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(gradientBrush)
            ) {
                when {
                    state.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    state.error != null -> {
                        ErrorContent(
                            error = state.error!!,
                            onRetry = {
                                if (enrollmentId != null) {
                                    viewModel.loadEnrollment(enrollmentId)
                                } else {
                                    viewModel.initializeNewEnrollment(programId)
                                }
                            }
                        )
                    }
                    else -> {
                        val formContent: @Composable () -> Unit = {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp, vertical = 24.dp)
                            ) {
                                Card(
                                    modifier = Modifier.fillMaxSize(),
                                    shape = RoundedCornerShape(24.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .background(DHIS2BlueLight, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Person,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(28.dp),
                                                    tint = DHIS2Blue
                                                )
                                            }
                                            Column {
                                                Text(
                                                    text = if (state.isEditMode) "Edit Enrollment" else "New Enrollment",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = state.programName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Surface(
                                            color = DHIS2BlueLight.copy(alpha = 0.35f),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text(
                                                text = "Offline mode supported. Enrollment will sync when connected.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(12.dp)
                                            )
                                        }

                                        if (sectionNames.isNotEmpty()) {
                                            SectionNavigationBar(
                                                currentSection = Section(sectionNames[currentSectionIndex]),
                                                currentSubsection = null,
                                                sectionIndex = currentSectionIndex.coerceAtLeast(0),
                                                totalSections = sectionNames.size.coerceAtLeast(1),
                                                onPreviousSection = {
                                                    scrollToSection((currentSectionIndex - 1).coerceAtLeast(0))
                                                },
                                                onNextSection = {
                                                    scrollToSection(
                                                        (currentSectionIndex + 1).coerceAtMost(sectionNames.lastIndex)
                                                    )
                                                },
                                                onPreviousSubsection = {},
                                                onNextSubsection = {},
                                                canPreviousSubsection = false,
                                                canNextSubsection = false,
                                                hasSubsections = false
                                            )
                                        }

                                        EnrollmentFormContent(
                                            state = state,
                                            onEnrollmentDateChanged = viewModel::updateEnrollmentDate,
                                            onIncidentDateChanged = viewModel::updateIncidentDate,
                                            onAttributeValueChanged = viewModel::updateAttributeValue,
                                            onOrganisationUnitChanged = viewModel::updateOrganisationUnit,
                                            listState = listState,
                                            modifier = Modifier.weight(1f)
                                        )

                                        Button(
                                            onClick = {
                                                if (state.canSave) {
                                                    showSaveDialog = true
                                                } else {
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar("Please fill all required fields")
                                                    }
                                                }
                                            },
                                            enabled = state.canSave,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                        ) {
                                            Text(if (state.isEditMode) "Update Enrollment" else "Create Enrollment")
                                        }

                                        TextButton(
                                            onClick = { navController.popBackStack() },
                                            modifier = Modifier.align(Alignment.CenterHorizontally)
                                        ) {
                                            Text("Cancel")
                                        }
                                    }
                                }
                            }
                        }
                        if (state.saveInProgress) {
                            SimpleProgressOverlay(
                                message = if (state.isEditMode) "Updating enrollment..." else "Creating enrollment...",
                                modifier = Modifier.fillMaxSize()
                            ) {
                                formContent()
                            }
                        } else {
                            formContent()
                        }
                    }
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }
    }

    // Save confirmation dialog (reuse DataEntry pattern)
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(if (state.isEditMode) "Update Enrollment" else "Save Enrollment") },
            text = {
                Text(
                    if (state.isEditMode) {
                        "Update this enrollment with the current information?"
                    } else {
                        "Create new enrollment for ${state.programName}?"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveEnrollment()
                        showSaveDialog = false
                    }
                ) {
                    Text(if (state.isEditMode) "Update" else "Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (!state.validationMessage.isNullOrBlank() && state.validationErrors.isNotEmpty()) {
        ValidationErrorDialog(
            title = "Validation issues",
            message = state.validationMessage ?: "Please review the following issues:",
            errors = state.validationErrors,
            onDismiss = { viewModel.clearValidationMessage() }
        )
    }
}

@Composable
private fun EnrollmentFormContent(
    state: TrackerEnrollmentState,
    onEnrollmentDateChanged: (Date) -> Unit,
    onIncidentDateChanged: (Date?) -> Unit,
    onAttributeValueChanged: (String, String) -> Unit,
    onOrganisationUnitChanged: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Enrollment Information Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Enrollment Information",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Organisation Unit Selection
                    OrganisationUnitSelector(
                        selectedOrgUnitId = state.selectedOrganisationUnitId,
                        orgUnits = state.availableOrganisationUnits,
                        onOrgUnitSelected = onOrganisationUnitChanged,
                        enabled = !state.isEditMode // Disable editing org unit for existing enrollments
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Enrollment Date using DHIS2 UI components
                    DateField(
                        label = "Enrollment Date",
                        date = state.enrollmentDate,
                        onDateSelected = onEnrollmentDateChanged,
                        icon = Icons.Default.DateRange,
                        required = true,
                        error = state.validationErrors.any { it.contains("Enrollment date") }
                    )

                    // Incident Date (if program supports it)
                    if (state.supportsIncidentDate) {
                        Spacer(modifier = Modifier.height(16.dp))

                        DateField(
                            label = state.incidentDateLabel ?: "Incident Date",
                            date = state.incidentDate,
                            onDateSelected = { onIncidentDateChanged(it) },
                            icon = Icons.Default.DateRange,
                            required = false
                        )
                    }
                }
            }
        }

        // Tracked Entity Attributes Section (using DHIS2 UI patterns)
        if (state.trackedEntityAttributes.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Personal Information",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Individual attribute items using DHIS2 UI components
            items(items = state.trackedEntityAttributes, key = { it.id }) { attribute ->
                val programRuleEffect = state.programRuleEffect
                if (programRuleEffect?.hiddenFields?.contains(attribute.id) == true) {
                    return@items
                }
                val warningMessage = programRuleEffect?.fieldWarnings?.get(attribute.id)
                val errorMessage = programRuleEffect?.fieldErrors?.get(attribute.id)
                val isMandatory = attribute.mandatory || (programRuleEffect?.mandatoryFields?.contains(attribute.id) == true)
                TrackerAttributeField(
                    attribute = attribute,
                    value = state.attributeValues[attribute.id] ?: "",
                    onValueChanged = { newValue ->
                        onAttributeValueChanged(attribute.id, newValue)
                    },
                    hasError = errorMessage != null || state.validationErrors.any { it.contains(attribute.displayName) },
                    isMandatory = isMandatory,
                    warningMessage = warningMessage,
                    errorMessage = errorMessage
                )
            }
        }

        // Validation Summary (reuse DataEntry validation pattern)
        if (state.validationErrors.isNotEmpty()) {
            item {
                ValidationErrorCard(
                    errors = state.validationErrors,
                    validationState = state.validationState
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrganisationUnitSelector(
    selectedOrgUnitId: String?,
    orgUnits: List<com.ash.simpledataentry.domain.model.OrganisationUnit>,
    onOrgUnitSelected: (String) -> Unit,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOrgUnit = orgUnits.find { it.id == selectedOrgUnitId }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedOrgUnit?.name ?: "",
            onValueChange = { },
            readOnly = true,
            enabled = enabled,
            label = { Text("Organisation Unit *") },
            placeholder = { Text("Select organisation unit") },
            trailingIcon = {
                if (enabled) ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled),
            isError = selectedOrgUnitId.isNullOrBlank()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            orgUnits.forEach { orgUnit ->
                DropdownMenuItem(
                    text = { Text(orgUnit.name) },
                    onClick = {
                        onOrgUnitSelected(orgUnit.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DateField(
    label: String,
    date: Date?,
    onDateSelected: (Date) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    required: Boolean = false,
    error: Boolean = false
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    OutlinedTextField(
        value = date?.let { dateFormatter.format(it) } ?: "",
        onValueChange = { },
        readOnly = true,
        label = {
            Text(if (required) "$label *" else label)
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDatePicker = true },
        isError = error
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDateSelected = { selectedDate ->
                onDateSelected(selectedDate)
                showDatePicker = false
            },
            onDismissRequest = { showDatePicker = false },
            initialDate = date ?: Date(),
            title = "Select $label",
            maxDate = Date() // Enrollment and incident dates cannot be in future
        )
    }
}

@Composable
private fun TrackerAttributeField(
    attribute: com.ash.simpledataentry.domain.model.TrackedEntityAttribute,
    value: String,
    onValueChanged: (String) -> Unit,
    hasError: Boolean = false,
    isMandatory: Boolean = false,
    warningMessage: String? = null,
    errorMessage: String? = null
) {
    // Fix cursor jumping: Use internal state for TextFieldValue, sync only when external value changes
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value)) }

    // Detect when external value changes (e.g., from ViewModel) and update internal state
    LaunchedEffect(value) {
        if (textFieldValue.text != value) {
            textFieldValue = TextFieldValue(
                text = value,
                selection = TextRange(value.length) // Place cursor at end
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Use DHIS2 UI components for consistency
            when (attribute.valueType) {
                "INTEGER", "POSITIVE_INTEGER", "NEGATIVE_INTEGER", "ZERO_OR_POSITIVE_INTEGER" -> {
                    InputNumber(
                        title = if (isMandatory) "${attribute.displayName} *" else attribute.displayName,
                        inputTextFieldValue = textFieldValue,
                        onValueChanged = { newValue ->
                            newValue?.let {
                                textFieldValue = it
                                onValueChanged(it.text)
                            }
                        },
                        state = if (hasError || errorMessage != null) InputShellState.ERROR else InputShellState.UNFOCUSED,
                        supportingText = listOfNotNull(
                            attribute.description?.let {
                                SupportingTextData(
                                    text = it,
                                    state = SupportingTextState.DEFAULT
                                )
                            }
                        )
                    )
                    errorMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    warningMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                "BOOLEAN", "TRUE_ONLY" -> {
                    // Switch for boolean values
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isMandatory) "${attribute.displayName} *" else attribute.displayName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (attribute.description != null) {
                                Text(
                                    text = attribute.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = value.equals("true", ignoreCase = true),
                            onCheckedChange = { checked ->
                                onValueChanged(if (checked) "true" else "false")
                            }
                        )
                    }
                    errorMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    warningMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                else -> {
                    // Text input for all other types
                    InputText(
                        title = if (isMandatory) "${attribute.displayName} *" else attribute.displayName,
                        inputTextFieldValue = textFieldValue,
                        onValueChanged = { newValue ->
                            newValue?.let {
                                textFieldValue = it
                                onValueChanged(it.text)
                            }
                        },
                        state = if (hasError || errorMessage != null) InputShellState.ERROR else InputShellState.UNFOCUSED,
                        supportingText = listOfNotNull(
                            attribute.description?.let {
                                SupportingTextData(
                                    text = it,
                                    state = SupportingTextState.DEFAULT
                                )
                            }
                        )
                    )
                    errorMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    warningMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ValidationErrorCard(
    errors: List<String>,
    validationState: com.ash.simpledataentry.domain.model.ValidationState
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Please fix the following errors:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            errors.forEach { error ->
                Text(
                    text = "• $error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun ErrorContent(
    error: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Error loading enrollment",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}
