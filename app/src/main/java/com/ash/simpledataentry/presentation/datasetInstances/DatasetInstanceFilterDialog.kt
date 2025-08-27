package com.ash.simpledataentry.presentation.datasetInstances

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ash.simpledataentry.domain.model.CompletionStatus
import com.ash.simpledataentry.domain.model.DatasetInstanceFilterState
import com.ash.simpledataentry.domain.model.PeriodFilterType
import com.ash.simpledataentry.domain.model.RelativePeriod
import com.ash.simpledataentry.domain.model.SyncStatus
import org.hisp.dhis.android.core.period.PeriodType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatasetInstanceFilterDialog(
    currentFilter: DatasetInstanceFilterState,
    attributeOptionCombos: List<Pair<String, String>> = emptyList(),
    dataset: com.ash.simpledataentry.domain.model.Dataset? = null,
    onFilterChanged: (DatasetInstanceFilterState) -> Unit,
    onClearFilters: () -> Unit,
    onDismiss: () -> Unit
) {
    var filterState by remember { mutableStateOf(currentFilter) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = null
                )
                Text("Filter Dataset Instances")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Period Filter Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Period Filter",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        Column(modifier = Modifier.selectableGroup()) {
                            PeriodFilterType.values().forEach { periodType ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = filterState.periodType == periodType,
                                            onClick = {
                                                filterState = filterState.copy(
                                                    periodType = periodType,
                                                    relativePeriod = if (periodType != PeriodFilterType.RELATIVE) null else filterState.relativePeriod
                                                )
                                            },
                                            role = Role.RadioButton
                                        )
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = filterState.periodType == periodType,
                                        onClick = null
                                    )
                                    Text(
                                        text = when (periodType) {
                                            PeriodFilterType.ALL -> "All Periods"
                                            PeriodFilterType.RELATIVE -> "Relative Period"
                                            PeriodFilterType.CUSTOM_RANGE -> "Custom Range"
                                        },
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                        
                        // Relative Period options
                        if (filterState.periodType == PeriodFilterType.RELATIVE) {
                            val relevantPeriods = getRelevantPeriodsForDataset(dataset?.periodType)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp)
                                    .selectableGroup()
                            ) {
                                relevantPeriods.forEach { relativePeriod ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = filterState.relativePeriod == relativePeriod,
                                                onClick = {
                                                    filterState = filterState.copy(relativePeriod = relativePeriod)
                                                },
                                                role = Role.RadioButton
                                            )
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = filterState.relativePeriod == relativePeriod,
                                            onClick = null
                                        )
                                        Text(
                                            text = relativePeriod.displayName,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Sync Status Filter Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Sync Status",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        Column(modifier = Modifier.selectableGroup()) {
                            SyncStatus.values().forEach { syncStatus ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = filterState.syncStatus == syncStatus,
                                            onClick = {
                                                filterState = filterState.copy(syncStatus = syncStatus)
                                            },
                                            role = Role.RadioButton
                                        )
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = filterState.syncStatus == syncStatus,
                                        onClick = null
                                    )
                                    Text(
                                        text = syncStatus.displayName,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Completion Status Filter Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Completion Status",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        Column(modifier = Modifier.selectableGroup()) {
                            CompletionStatus.values().forEach { completionStatus ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = filterState.completionStatus == completionStatus,
                                            onClick = {
                                                filterState = filterState.copy(completionStatus = completionStatus)
                                            },
                                            role = Role.RadioButton
                                        )
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = filterState.completionStatus == completionStatus,
                                        onClick = null
                                    )
                                    Text(
                                        text = completionStatus.displayName,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Attribute Option Combo Filter Section
                android.util.Log.d("FilterDialog", "attributeOptionCombos received: $attributeOptionCombos")
                val hasNonDefaultOptions = attributeOptionCombos.any { !it.first.equals("default", ignoreCase = true) }
                android.util.Log.d("FilterDialog", "hasNonDefaultOptions: $hasNonDefaultOptions")
                if (hasNonDefaultOptions) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Attribute Option Combo",
                                style = MaterialTheme.typography.titleMedium
                            )
                            
                            Column(modifier = Modifier.selectableGroup()) {
                                // All option
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = filterState.attributeOptionCombo == null,
                                            onClick = {
                                                filterState = filterState.copy(attributeOptionCombo = null)
                                            },
                                            role = Role.RadioButton
                                        )
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = filterState.attributeOptionCombo == null,
                                        onClick = null
                                    )
                                    Text(
                                        text = "All",
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                                
                                // Specific attribute option combos
                                val filteredCombos = attributeOptionCombos.filter { !it.first.equals("default", ignoreCase = true) }
                                android.util.Log.d("FilterDialog", "Filtered combos: $filteredCombos")
                                filteredCombos.forEach { (id, name) ->
                                    android.util.Log.d("FilterDialog", "Rendering option: $id -> $name")
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = filterState.attributeOptionCombo == id,
                                                onClick = {
                                                    filterState = filterState.copy(attributeOptionCombo = id)
                                                },
                                                role = Role.RadioButton
                                            )
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = filterState.attributeOptionCombo == id,
                                            onClick = null
                                        )
                                        Text(
                                            text = name,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        onClearFilters()
                        onDismiss()
                    }
                ) {
                    Text("Clear All")
                }
                Button(
                    onClick = {
                        onFilterChanged(filterState)
                        onDismiss()
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

private fun getRelevantPeriodsForDataset(periodType: PeriodType?): List<RelativePeriod> {
    return when (periodType) {
        PeriodType.Daily -> listOf(
            RelativePeriod.TODAY,
            RelativePeriod.YESTERDAY,
            RelativePeriod.LAST_3_DAYS,
            RelativePeriod.LAST_7_DAYS,
            RelativePeriod.LAST_14_DAYS
        )
        PeriodType.Weekly, PeriodType.WeeklyWednesday, PeriodType.WeeklyThursday, PeriodType.WeeklySaturday, PeriodType.WeeklySunday -> listOf(
            RelativePeriod.THIS_WEEK,
            RelativePeriod.LAST_WEEK,
            RelativePeriod.LAST_4_WEEKS,
            RelativePeriod.LAST_12_WEEKS
        )
        PeriodType.Monthly -> listOf(
            RelativePeriod.THIS_MONTH,
            RelativePeriod.LAST_MONTH,
            RelativePeriod.LAST_3_MONTHS,
            RelativePeriod.LAST_6_MONTHS,
            RelativePeriod.LAST_12_MONTHS
        )
        PeriodType.BiMonthly -> listOf(
            RelativePeriod.THIS_BIMONTH,
            RelativePeriod.LAST_BIMONTH,
            RelativePeriod.LAST_6_BIMONTHS
        )
        PeriodType.Quarterly -> listOf(
            RelativePeriod.THIS_QUARTER,
            RelativePeriod.LAST_QUARTER,
            RelativePeriod.LAST_4_QUARTERS
        )
        PeriodType.SixMonthly, PeriodType.SixMonthlyApril -> listOf(
            RelativePeriod.THIS_SIX_MONTH,
            RelativePeriod.LAST_SIX_MONTH,
            RelativePeriod.LAST_2_SIXMONTHS
        )
        PeriodType.Yearly, PeriodType.FinancialApril, PeriodType.FinancialJuly, PeriodType.FinancialOct -> listOf(
            RelativePeriod.THIS_YEAR,
            RelativePeriod.LAST_YEAR,
            RelativePeriod.LAST_5_YEARS,
            RelativePeriod.THIS_FINANCIAL_YEAR,
            RelativePeriod.LAST_FINANCIAL_YEAR
        )
        else -> RelativePeriod.values().toList() // Fallback to all periods if unknown
    }
}
