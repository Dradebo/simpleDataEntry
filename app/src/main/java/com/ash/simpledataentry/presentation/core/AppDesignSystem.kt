package com.ash.simpledataentry.presentation.core

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.hisp.dhis.mobile.ui.designsystem.theme.SurfaceColor

/**
 * Centralized design system for consistent spacing, colors, and styling
 * across all screens in the Simple Data Entry app
 */
object AppDesignSystem {

    /**
     * Standardized spacing system
     */
    object Spacing {
        val xs: Dp = 4.dp
        val small: Dp = 8.dp
        val medium: Dp = 16.dp
        val large: Dp = 24.dp
        val xl: Dp = 32.dp
        val xxl: Dp = 48.dp
    }

    /**
     * Standardized elevation levels
     */
    object Elevation {
        val none: Dp = 0.dp
        val low: Dp = 2.dp
        val medium: Dp = 4.dp
        val high: Dp = 8.dp
        val highest: Dp = 16.dp
    }

    /**
     * Standardized corner radius values
     */
    object CornerRadius {
        val small = RoundedCornerShape(8.dp)
        val medium = RoundedCornerShape(12.dp)
        val large = RoundedCornerShape(16.dp)
        val chip = RoundedCornerShape(12.dp)
        val button = RoundedCornerShape(28.dp)
    }

    /**
     * Card color variants for different semantic meanings
     */
    enum class CardType {
        DEFAULT,
        SUCCESS,
        WARNING,
        ERROR,
        INFO,
        HIGHLIGHT
    }

    /**
     * Get card colors based on semantic type
     */
    @Composable
    fun getCardColors(type: CardType) = when (type) {
        CardType.DEFAULT -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
        CardType.SUCCESS -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
        CardType.WARNING -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
        CardType.ERROR -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
        CardType.INFO -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
        CardType.HIGHLIGHT -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }

    /**
     * Get seamless filter panel color (slightly deeper than header)
     */
    @Composable
    fun getFilterPanelColor(): Color {
        return SurfaceColor.Primary.copy(alpha = 0.95f)
    }

    /**
     * Standardized icon sizes
     */
    object IconSize {
        val small: Dp = 16.dp
        val medium: Dp = 20.dp
        val large: Dp = 24.dp
        val xl: Dp = 32.dp
        val chip: Dp = 16.dp
    }

    /**
     * Standardized button heights
     */
    object ButtonHeight {
        val standard: Dp = 48.dp
        val large: Dp = 56.dp
        val chip: Dp = 28.dp
    }
}