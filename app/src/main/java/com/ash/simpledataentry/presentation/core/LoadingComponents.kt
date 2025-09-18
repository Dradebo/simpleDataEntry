package com.ash.simpledataentry.presentation.core

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// LOADING ANIMATION CONFIGURATION - Edit this object to change animations globally
object LoadingConfig {
    // Choose your loading animation type here
    val primaryAnimation: LoadingAnimationType = LoadingAnimationType.DHIS2_PULSING_DOTS
    val overlayAnimation: LoadingAnimationType = LoadingAnimationType.COMPACT_SPINNER

    // Animation parameters
    val pulsingDotSize = 12.dp
    val pulsingDotSpacing = 12.dp
    val overlayBlurRadius = 8.dp
    val overlayAlpha = 0.7f
}

enum class LoadingAnimationType {
    DHIS2_PULSING_DOTS,
    COMPACT_SPINNER,
    MATERIAL_SPINNER,
    BOUNCING_DOTS
}

/**
 * Centralized loading animation selector
 * Change animation type in LoadingConfig to swap globally
 */
@Composable
fun LoadingAnimation(
    type: LoadingAnimationType = LoadingConfig.primaryAnimation,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    size: androidx.compose.ui.unit.Dp = 20.dp
) {
    when (type) {
        LoadingAnimationType.DHIS2_PULSING_DOTS -> Dhis2PulsingDots(color = color)
        LoadingAnimationType.COMPACT_SPINNER -> CompactSpinner(color = color, size = size)
        LoadingAnimationType.MATERIAL_SPINNER -> MaterialSpinner(color = color, size = size)
        LoadingAnimationType.BOUNCING_DOTS -> BouncingDots(color = color)
    }
}

/**
 * DHIS2-style pulsing loader with three animated dots
 */
@Composable
private fun Dhis2PulsingDots(
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(LoadingConfig.pulsingDotSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition$index")

            val scale by infiniteTransition.animateFloat(
                initialValue = 0.8f,
                targetValue = 1.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 800,
                        delayMillis = index * 200,
                        easing = EaseInOut
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scaleAnimation$index"
            )

            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 800,
                        delayMillis = index * 200,
                        easing = EaseInOut
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alphaAnimation$index"
            )

            Box(
                modifier = Modifier
                    .size(LoadingConfig.pulsingDotSize)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        alpha = alpha
                    )
                    .background(
                        color = color,
                        shape = CircleShape
                    )
            )
        }
    }
}

/**
 * Compact circular spinner
 */
@Composable
private fun CompactSpinner(
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    size: androidx.compose.ui.unit.Dp = 20.dp
) {
    CircularProgressIndicator(
        modifier = Modifier.size(size),
        strokeWidth = 2.dp,
        color = color
    )
}

/**
 * Material Design spinner
 */
@Composable
private fun MaterialSpinner(
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    size: androidx.compose.ui.unit.Dp = 24.dp
) {
    CircularProgressIndicator(
        modifier = Modifier.size(size),
        strokeWidth = 3.dp,
        color = color
    )
}

/**
 * Bouncing dots animation
 */
@Composable
private fun BouncingDots(
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val infiniteTransition = rememberInfiniteTransition(label = "bounceTransition$index")

            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -10f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 600,
                        delayMillis = index * 100,
                        easing = EaseInOut
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bounceAnimation$index"
            )

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { translationY = offsetY }
                    .background(
                        color = color,
                        shape = CircleShape
                    )
            )
        }
    }
}

/**
 * Full-screen loading overlay - completely obscures content
 * Use for navigation between major screens
 */
@Composable
fun FullScreenLoader(
    message: String = "Loading...",
    isVisible: Boolean = true,
    animationType: LoadingAnimationType = LoadingConfig.primaryAnimation,
    progress: Int? = null,
    progressStep: String? = null,
    modifier: Modifier = Modifier
) {
    if (isVisible) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                LoadingAnimation(type = animationType, size = 32.dp)
                Spacer(modifier = Modifier.height(24.dp))

                // Display progress step or main message
                Text(
                    text = progressStep ?: message,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                // Display progress bar if progress is available
                progress?.let { progressValue ->
                    Spacer(modifier = Modifier.height(24.dp))
                    LinearProgressIndicator(
                        progress = { progressValue / 100f },
                        modifier = Modifier.width(280.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "$progressValue%",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/**
 * Overlay loading - blurs background content but doesn't completely obscure it
 * Use for header actions like sync, refresh, etc.
 */
@Composable
fun OverlayLoader(
    message: String = "Syncing...",
    isVisible: Boolean = true,
    animationType: LoadingAnimationType = LoadingConfig.overlayAnimation,
    progress: Int? = null,
    progressStep: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Main content
        Box(
            modifier = if (isVisible) {
                Modifier
                    .fillMaxSize()
                    .blur(LoadingConfig.overlayBlurRadius)
                    .alpha(LoadingConfig.overlayAlpha)
            } else {
                Modifier.fillMaxSize()
            }
        ) {
            content()
        }

        // Loading overlay
        if (isVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                    )
                    .clickable(enabled = true, onClick = {}),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.padding(32.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        LoadingAnimation(type = animationType)
                        Spacer(modifier = Modifier.height(16.dp))

                        // Display progress step or main message
                        Text(
                            text = progressStep ?: message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Display progress bar if progress is available
                        progress?.let { progressValue ->
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = { progressValue / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$progressValue%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Backward compatibility - keep existing function names
 */
@Composable
fun Dhis2PulsingLoader(
    modifier: Modifier = Modifier,
    dotColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    LoadingAnimation(
        type = LoadingAnimationType.DHIS2_PULSING_DOTS,
        color = dotColor
    )
}

/**
 * Compact loading indicator for buttons and inline elements
 */
@Composable
fun CompactLoader(
    size: androidx.compose.ui.unit.Dp = 20.dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 2.dp,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    CircularProgressIndicator(
        modifier = Modifier.size(size),
        strokeWidth = strokeWidth,
        color = color
    )
}