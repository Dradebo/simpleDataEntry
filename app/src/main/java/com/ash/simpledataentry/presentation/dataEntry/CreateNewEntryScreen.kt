package com.ash.simpledataentry.presentation.dataEntry

import android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ash.simpledataentry.domain.model.OrganisationUnit
import com.ash.simpledataentry.domain.model.Period
import com.ash.simpledataentry.presentation.core.BaseScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateNewEntryScreen(
    navController: NavController,
    datasetId: String,
    datasetName: String,
    viewModel: DataEntryViewModel = hiltViewModel()
) {
    var periods by remember { mutableStateOf<List<Period>>(emptyList()) }
    var selectedPeriod by remember { mutableStateOf("") }
    var orgUnits by remember { mutableStateOf<List<OrganisationUnit>>(emptyList()) }
    var selectedOrgUnit by remember { mutableStateOf<OrganisationUnit?>(null) }
    var defaultAttributeOptionCombo by remember { mutableStateOf("") }
    var expandedPeriod by remember { mutableStateOf(false) }
    var expandedOrgUnit by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attributeOptionCombos by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedAttributeOptionCombo by remember { mutableStateOf("") }
    var expandedAttributeOptionCombo by remember { mutableStateOf(false) }
    val combinedState by viewModel.combinedState.collectAsState()
    val state = combinedState.uiState

    LaunchedEffect(datasetId) {
        try {
            isLoading = true
            error = null
            viewModel.loadDataValues(datasetId, datasetName, "", "", "", isEditMode = false)
            periods = viewModel.getAvailablePeriods(datasetId)
            orgUnits = viewModel.getUserOrgUnits(datasetId) // Get multiple org units
            selectedOrgUnit = orgUnits.firstOrNull() // Select first org unit by default
            defaultAttributeOptionCombo = viewModel.getDefaultAttributeOptionCombo()
            attributeOptionCombos = viewModel.getAttributeOptionCombos(datasetId)
            selectedAttributeOptionCombo = attributeOptionCombos.firstOrNull()?.first ?: ""
            isLoading = false
        } catch (e: Exception) {
            error = e.message ?: "Failed to load data"
            isLoading = false
        }
    }

    BaseScreen(
        title = "Create New Entry",
        navController = navController
    ) {
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (error != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = error!!)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_menu_edit),
                    contentDescription = "New Entry",
                    modifier = Modifier.size(48.dp)
                )

                // Organization Unit Dropdown
                ExposedDropdownMenuBox(
                    expanded = expandedOrgUnit,
                    onExpandedChange = { expandedOrgUnit = !expandedOrgUnit }
                ) {
                    OutlinedTextField(
                        value = selectedOrgUnit?.name ?: "Select Organization Unit",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Organization Unit") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedOrgUnit) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedOrgUnit,
                        onDismissRequest = { expandedOrgUnit = false }
                    ) {
                        orgUnits.forEach { orgUnit ->
                            DropdownMenuItem(
                                text = { Text(orgUnit.name) },
                                onClick = {
                                    selectedOrgUnit = orgUnit
                                    expandedOrgUnit = false
                                }
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = expandedPeriod,
                    onExpandedChange = { expandedPeriod = !expandedPeriod }
                ) {
                    OutlinedTextField(
                        value = selectedPeriod,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Period") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedPeriod) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedPeriod,
                        onDismissRequest = { expandedPeriod = false }
                    ) {
                        periods.forEach { period ->
                            DropdownMenuItem(
                                text = { Text(period.id) },
                                onClick = {
                                    selectedPeriod = period.id
                                    expandedPeriod = false
                                }
                            )
                        }
                    }
                }

                // Attribute Option Combo Dropdown
                ExposedDropdownMenuBox(
                    expanded = expandedAttributeOptionCombo,
                    onExpandedChange = { expandedAttributeOptionCombo = !expandedAttributeOptionCombo }
                ) {
                    OutlinedTextField(
                        value = attributeOptionCombos.find { it.first == selectedAttributeOptionCombo }?.second ?: "Select Attribute Option Combo",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Attribute Option Combo") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAttributeOptionCombo) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedAttributeOptionCombo,
                        onDismissRequest = { expandedAttributeOptionCombo = false }
                    ) {
                        attributeOptionCombos.forEach { (uid, displayName) ->
                            DropdownMenuItem(
                                text = { Text(displayName) },
                                onClick = {
                                    selectedAttributeOptionCombo = uid
                                    expandedAttributeOptionCombo = false
                                }
                            )
                        }
                    }
                }
            }

            if (selectedOrgUnit != null && selectedPeriod.isNotEmpty() && selectedAttributeOptionCombo.isNotEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Button(
                        onClick = {
                            val encodedDatasetName = java.net.URLEncoder.encode(datasetName, "UTF-8")
                            val encodedPeriod = java.net.URLEncoder.encode(selectedPeriod, "UTF-8")
                            val encodedOrgUnit = java.net.URLEncoder.encode(selectedOrgUnit!!.id, "UTF-8")
                            val encodedAttributeOptionCombo = java.net.URLEncoder.encode(selectedAttributeOptionCombo, "UTF-8")
                            navController.navigate(
                                "EditEntry/$datasetId/$encodedPeriod/$encodedOrgUnit/$encodedAttributeOptionCombo/$encodedDatasetName"
                            ) {
                                popUpTo("CreateDataEntry/$datasetId/$datasetName") { inclusive = true }
                            }
                        },
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}
