package com.ash.simpledataentry.presentation.core

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ash.simpledataentry.domain.model.*
import org.hisp.dhis.mobile.ui.designsystem.theme.TextColor

/**
 * Seamless filter panel that integrates with the header bar
 * Uses slightly deeper shade of header color for seamless appearance
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeamlessFilterPanel(
    isVisible: Boolean,
    currentFilter: DatasetInstanceFilterState,
    onApplyFilter: (DatasetInstanceFilterState) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = tween(300, easing = EaseOutCubic)
        ),
        exit = slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = tween(300, easing = EaseInCubic)
        ),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = AppDesignSystem.getFilterPanelColor()
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = AppDesignSystem.Elevation.none // Seamless with header
            ),
            shape = RoundedCornerShape(
                topStart = 0.dp,
                topEnd = 0.dp,
                bottomStart = AppDesignSystem.Spacing.small,
                bottomEnd = AppDesignSystem.Spacing.small
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppDesignSystem.Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(AppDesignSystem.Spacing.small)
            ) {
                // Search field with DHIS2 design system colors
                SearchFilterRow(currentFilter, onApplyFilter)

                // Sort controls row
                SortControlsRow(currentFilter, onApplyFilter)

                // Status filters row
                StatusFiltersRow(currentFilter, onApplyFilter)

                // Clear filters button
                if (currentFilter.hasActiveFilters()) {
                    ClearFiltersButton(currentFilter, onApplyFilter)
                }
            }
        }
    }
}

@Composable
private fun SearchFilterRow(
    currentFilter: DatasetInstanceFilterState,
    onApplyFilter: (DatasetInstanceFilterState) -> Unit
) {
    var searchQuery by remember { mutableStateOf(currentFilter.searchQuery) }

    OutlinedTextField(
        value = searchQuery,
        onValueChange = {
            searchQuery = it
            onApplyFilter(currentFilter.copy(searchQuery = searchQuery))
        },
        label = {
            Text(
                "Search dataset instances",
                color = TextColor.OnPrimary
            )
        },
        placeholder = {
            Text(
                "Enter org unit, period...",
                color = TextColor.OnPrimary.copy(alpha = 0.7f)
            )
        },
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = TextColor.OnPrimary
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextColor.OnPrimary,
            unfocusedTextColor = TextColor.OnPrimary,
            focusedBorderColor = TextColor.OnPrimary,
            unfocusedBorderColor = TextColor.OnPrimary.copy(alpha = 0.5f),
            focusedLabelColor = TextColor.OnPrimary,
            unfocusedLabelColor = TextColor.OnPrimary.copy(alpha = 0.7f),
            cursorColor = TextColor.OnPrimary
        ),
        singleLine = true
    )
}

@Composable
private fun SortControlsRow(
    currentFilter: DatasetInstanceFilterState,
    onApplyFilter: (DatasetInstanceFilterState) -> Unit
) {
    var sortBy by remember { mutableStateOf(currentFilter.sortBy) }
    var sortOrder by remember { mutableStateOf(currentFilter.sortOrder) }

    var showSortByDropdown by remember { mutableStateOf(false) }
    var showSortOrderDropdown by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppDesignSystem.Spacing.small)
    ) {
        // Sort By dropdown
        Box(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = sortBy.displayName,
                onValueChange = { },
                label = {
                    Text(
                        "Sort By",
                        color = TextColor.OnPrimary.copy(alpha = 0.7f)
                    )
                },
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                colors = getFilterFieldColors(),
                trailingIcon = {
                    IconButton(onClick = { showSortByDropdown = !showSortByDropdown }) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = TextColor.OnPrimary
                        )
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

        // Sort Order dropdown
        Box(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = sortOrder.displayName,
                onValueChange = { },
                label = {
                    Text(
                        "Order",
                        color = TextColor.OnPrimary.copy(alpha = 0.7f)
                    )
                },
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                colors = getFilterFieldColors(),
                trailingIcon = {
                    IconButton(onClick = { showSortOrderDropdown = !showSortOrderDropdown }) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = TextColor.OnPrimary
                        )
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
}

@Composable
private fun StatusFiltersRow(
    currentFilter: DatasetInstanceFilterState,
    onApplyFilter: (DatasetInstanceFilterState) -> Unit
) {
    var syncStatus by remember { mutableStateOf(currentFilter.syncStatus) }
    var completionStatus by remember { mutableStateOf(currentFilter.completionStatus) }

    var showSyncDropdown by remember { mutableStateOf(false) }
    var showCompletionDropdown by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppDesignSystem.Spacing.small)
    ) {
        // Sync Status Filter
        Box(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = syncStatus.displayName,
                onValueChange = { },
                label = {
                    Text(
                        "Sync Status",
                        color = TextColor.OnPrimary.copy(alpha = 0.7f)
                    )
                },
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                colors = getFilterFieldColors(),
                trailingIcon = {
                    IconButton(onClick = { showSyncDropdown = !showSyncDropdown }) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = TextColor.OnPrimary
                        )
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
                                    completionStatus = completionStatus
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
                label = {
                    Text(
                        "Completion",
                        color = TextColor.OnPrimary.copy(alpha = 0.7f)
                    )
                },
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                colors = getFilterFieldColors(),
                trailingIcon = {
                    IconButton(onClick = { showCompletionDropdown = !showCompletionDropdown }) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = TextColor.OnPrimary
                        )
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
                                    syncStatus = syncStatus,
                                    completionStatus = completionStatus
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ClearFiltersButton(
    currentFilter: DatasetInstanceFilterState,
    onApplyFilter: (DatasetInstanceFilterState) -> Unit
) {
    OutlinedButton(
        onClick = {
            onApplyFilter(DatasetInstanceFilterState())
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = TextColor.OnPrimary
        ),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                TextColor.OnPrimary.copy(alpha = 0.5f)
            )
        )
    ) {
        Text("Clear All Filters")
    }
}

/**
 * Consistent colors for filter fields that work with the header-integrated design
 */
@Composable
private fun getFilterFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextColor.OnPrimary,
    unfocusedTextColor = TextColor.OnPrimary,
    focusedBorderColor = TextColor.OnPrimary,
    unfocusedBorderColor = TextColor.OnPrimary.copy(alpha = 0.5f),
    focusedLabelColor = TextColor.OnPrimary,
    unfocusedLabelColor = TextColor.OnPrimary.copy(alpha = 0.7f),
    cursorColor = TextColor.OnPrimary
)