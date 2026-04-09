package com.ash.simpledataentry.presentation.core

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ash.simpledataentry.domain.model.DatasetInstance
import com.ash.simpledataentry.domain.model.DatasetInstanceState
import java.text.SimpleDateFormat
import java.util.*

/**
 * Enhanced Dataset Instance Card with smart chip layout
 * - Complete chip is always rightmost if present
 * - Not synced chip is rightmost if no complete chip, otherwise leftmost
 * - Maintains consistent card size regardless of chip presence
 */
@Composable
fun EnhancedDatasetInstanceCard(
    instance: DatasetInstance,
    organisationUnitName: String,
    periodText: String,
    attributeOptionComboName: String? = null,
    formattedDate: String? = null,
    isComplete: Boolean,
    hasLocalChanges: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = AppDesignSystem.getCardColors(AppDesignSystem.CardType.DEFAULT),
        elevation = CardDefaults.cardElevation(defaultElevation = AppDesignSystem.Elevation.low),
        onClick = onClick,
        shape = AppDesignSystem.CornerRadius.medium
    ) {
        Column(
            modifier = Modifier.padding(AppDesignSystem.Spacing.medium)
        ) {
            // Main content row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$organisationUnitName • $periodText",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                formattedDate?.let { date ->
                    Text(
                        text = date,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Attribute combo if applicable
            attributeOptionComboName?.let { attrName ->
                if (!attrName.equals("default", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(AppDesignSystem.Spacing.xs))
                    Text(
                        text = attrName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Smart chip layout - maintains consistent card height
            Spacer(modifier = Modifier.height(AppDesignSystem.Spacing.small))

            // Status chip row with smart positioning
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmartChipLayout(
                    isComplete = isComplete,
                    hasLocalChanges = hasLocalChanges
                )
            }
        }
    }
}

/**
 * Smart chip layout following the specified positioning rules:
 * 1. Complete chip is always rightmost if present
 * 2. Not synced chip is rightmost if no complete chip, otherwise leftmost
 * 3. Consistent spacing maintained even with no chips
 */
@Composable
private fun SmartChipLayout(
    isComplete: Boolean,
    hasLocalChanges: Boolean
) {
    when {
        isComplete && hasLocalChanges -> {
            // Both chips: Not synced (left), Complete (right)
            StatusChip(
                text = "Not synced",
                icon = Icons.Default.Sync,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            )
            Spacer(modifier = Modifier.width(AppDesignSystem.Spacing.small))
            StatusChip(
                text = "Complete",
                icon = Icons.Default.CheckCircle,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
        isComplete -> {
            // Only complete chip (rightmost)
            StatusChip(
                text = "Complete",
                icon = Icons.Default.CheckCircle,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
        hasLocalChanges -> {
            // Only not synced chip (rightmost)
            StatusChip(
                text = "Not synced",
                icon = Icons.Default.Sync,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            )
        }
        else -> {
            // No chips - maintain consistent spacing with invisible spacer
            Spacer(modifier = Modifier.height(AppDesignSystem.ButtonHeight.chip))
        }
    }
}

/**
 * Consistent status chip component
 */
@Composable
private fun StatusChip(
    text: String,
    icon: ImageVector,
    colors: ChipColors
) {
    AssistChip(
        onClick = { /* No action needed for status chips */ },
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(AppDesignSystem.IconSize.chip)
            )
        },
        colors = colors,
        shape = AppDesignSystem.CornerRadius.chip,
        modifier = Modifier.height(AppDesignSystem.ButtonHeight.chip)
    )
}

/**
 * Enhanced card for bulk mode with checkbox integration
 */
@Composable
fun EnhancedDatasetInstanceCardWithCheckbox(
    instance: DatasetInstance,
    organisationUnitName: String,
    periodText: String,
    attributeOptionComboName: String? = null,
    formattedDate: String? = null,
    isComplete: Boolean,
    hasLocalChanges: Boolean,
    isSelected: Boolean,
    onSelectionChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        // Checkbox for bulk selection
        Checkbox(
            checked = isSelected || isComplete,
            onCheckedChange = { checked ->
                if (enabled && !isComplete) {
                    onSelectionChanged(checked)
                }
            },
            enabled = enabled && !isComplete,
            colors = CheckboxDefaults.colors(
                checkedColor = if (isComplete) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
                uncheckedColor = if (isComplete) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        )

        // Enhanced card
        Box(modifier = Modifier.weight(1f)) {
            EnhancedDatasetInstanceCard(
                instance = instance,
                organisationUnitName = organisationUnitName,
                periodText = periodText,
                attributeOptionComboName = attributeOptionComboName,
                formattedDate = formattedDate,
                isComplete = isComplete,
                hasLocalChanges = hasLocalChanges,
                onClick = onClick
            )
        }
    }
}