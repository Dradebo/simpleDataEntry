package com.ash.simpledataentry.data.sync

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configuration for sync timeouts based on network conditions
 */
data class SyncTimeouts(
    val connectionTimeoutMs: Long,
    val uploadTimeoutMs: Long,
    val downloadTimeoutMs: Long,
    val retryDelayMs: Long,
    val maxRetryDelayMs: Long
)

/**
 * Adaptive sync strategy that adjusts parameters based on network quality
 */
@Singleton
class AdaptiveSyncStrategy @Inject constructor() {

    /**
     * Calculate optimal chunk size based on connection quality
     */
    fun calculateOptimalChunkSize(quality: ConnectionQuality): Int {
        return when (quality) {
            ConnectionQuality.EXCELLENT -> 50 // Large chunks for excellent connections
            ConnectionQuality.GOOD -> 25      // Medium chunks for good connections
            ConnectionQuality.FAIR -> 15      // Smaller chunks for fair connections
            ConnectionQuality.POOR -> 5       // Very small chunks for poor connections
            ConnectionQuality.UNKNOWN -> 10   // Conservative default
        }
    }

    /**
     * Calculate timeouts based on connection quality
     */
    fun calculateTimeouts(quality: ConnectionQuality): SyncTimeouts {
        return when (quality) {
            ConnectionQuality.EXCELLENT -> SyncTimeouts(
                connectionTimeoutMs = 15_000,   // 15 seconds
                uploadTimeoutMs = 660_000,      // 11 minutes (longer than DHIS2 SDK 10 min)
                downloadTimeoutMs = 240_000,    // 4 minutes (longer than DHIS2 SDK 3 min)
                retryDelayMs = 2_000,          // 2 seconds
                maxRetryDelayMs = 30_000       // 30 seconds max
            )
            ConnectionQuality.GOOD -> SyncTimeouts(
                connectionTimeoutMs = 30_000,   // 30 seconds
                uploadTimeoutMs = 660_000,      // 11 minutes (longer than DHIS2 SDK 10 min)
                downloadTimeoutMs = 240_000,    // 4 minutes (longer than DHIS2 SDK 3 min)
                retryDelayMs = 5_000,          // 5 seconds
                maxRetryDelayMs = 60_000       // 1 minute max
            )
            ConnectionQuality.FAIR -> SyncTimeouts(
                connectionTimeoutMs = 45_000,   // 45 seconds
                uploadTimeoutMs = 660_000,      // 11 minutes (longer than DHIS2 SDK 10 min)
                downloadTimeoutMs = 240_000,    // 4 minutes (longer than DHIS2 SDK 3 min)
                retryDelayMs = 10_000,         // 10 seconds
                maxRetryDelayMs = 120_000      // 2 minutes max
            )
            ConnectionQuality.POOR -> SyncTimeouts(
                connectionTimeoutMs = 60_000,   // 1 minute
                uploadTimeoutMs = 720_000,      // 12 minutes (longer than DHIS2 SDK, more time for poor connections)
                downloadTimeoutMs = 300_000,    // 5 minutes (longer than DHIS2 SDK 3 min)
                retryDelayMs = 15_000,         // 15 seconds
                maxRetryDelayMs = 300_000      // 5 minutes max
            )
            ConnectionQuality.UNKNOWN -> SyncTimeouts(
                connectionTimeoutMs = 30_000,   // 30 seconds (conservative)
                uploadTimeoutMs = 660_000,      // 11 minutes (longer than DHIS2 SDK 10 min)
                downloadTimeoutMs = 240_000,    // 4 minutes (longer than DHIS2 SDK 3 min)
                retryDelayMs = 10_000,         // 10 seconds
                maxRetryDelayMs = 120_000      // 2 minutes max
            )
        }
    }

    /**
     * Determine if we should retry immediately based on connection quality and error type
     */
    fun shouldRetryImmediately(
        quality: ConnectionQuality,
        error: Exception,
        currentAttempt: Int,
        maxAttempts: Int
    ): Boolean {
        // Don't retry if we've reached max attempts
        if (currentAttempt >= maxAttempts) return false

        // Check error type first
        val isRetryableError = isRetryableError(error)
        if (!isRetryableError) return false

        // For poor connections, be more conservative with retries
        return when (quality) {
            ConnectionQuality.EXCELLENT, ConnectionQuality.GOOD -> true
            ConnectionQuality.FAIR -> currentAttempt <= 2 // Max 2 retries for fair
            ConnectionQuality.POOR -> currentAttempt <= 1 // Max 1 retry for poor
            ConnectionQuality.UNKNOWN -> currentAttempt <= 2 // Conservative for unknown
        }
    }

    /**
     * Calculate progressive backoff delay with quality-based adjustments
     */
    fun calculateBackoffDelay(
        quality: ConnectionQuality,
        attempt: Int,
        baseDelayMs: Long,
        maxDelayMs: Long
    ): Long {
        val timeouts = calculateTimeouts(quality)

        // Use quality-specific base delay
        val qualityBaseDelay = timeouts.retryDelayMs
        val qualityMaxDelay = timeouts.maxRetryDelayMs

        // Progressive backoff: base * 2^(attempt-1)
        val progressiveDelay = qualityBaseDelay * (1L shl (attempt - 1))

        // Cap at quality-specific maximum
        return minOf(progressiveDelay, qualityMaxDelay)
    }

    /**
     * Determine optimal number of retry attempts based on connection quality
     */
    fun getMaxRetryAttempts(quality: ConnectionQuality): Int {
        return when (quality) {
            ConnectionQuality.EXCELLENT -> 5
            ConnectionQuality.GOOD -> 4
            ConnectionQuality.FAIR -> 3
            ConnectionQuality.POOR -> 2
            ConnectionQuality.UNKNOWN -> 3
        }
    }

    /**
     * Check if an error is retryable based on its type
     */
    private fun isRetryableError(exception: Exception): Boolean {
        return when {
            // Retry for network/timeout issues
            exception is kotlinx.coroutines.TimeoutCancellationException -> true
            exception.message?.contains("SocketTimeoutException") == true -> true
            exception.message?.contains("ConnectException") == true -> true
            exception.message?.contains("UnknownHostException") == true -> true
            exception.message?.contains("D2Error") == true -> true
            exception.message?.contains("500") == true -> true
            exception.message?.contains("503") == true -> true
            exception.message?.contains("502") == true -> true
            exception.message?.contains("504") == true -> true

            // Don't retry for authentication issues
            exception.message?.contains("401") == true -> false
            exception.message?.contains("Unauthorized") == true -> false
            exception.message?.contains("403") == true -> false
            exception.message?.contains("Forbidden") == true -> false

            // Don't retry for client errors
            exception.message?.contains("400") == true -> false
            exception.message?.contains("404") == true -> false
            exception.message?.contains("422") == true -> false

            // Default to retry for unknown errors
            else -> true
        }
    }

    /**
     * Determine if connection quality is adequate for sync
     */
    fun isQualityAdequateForSync(quality: ConnectionQuality): Boolean {
        return quality != ConnectionQuality.UNKNOWN && quality != ConnectionQuality.POOR
    }

    /**
     * Get recommended sync approach based on connection quality
     */
    fun getRecommendedSyncApproach(quality: ConnectionQuality): SyncApproach {
        return when (quality) {
            ConnectionQuality.EXCELLENT -> SyncApproach.FULL_SYNC
            ConnectionQuality.GOOD -> SyncApproach.FULL_SYNC
            ConnectionQuality.FAIR -> SyncApproach.CHUNKED_SYNC
            ConnectionQuality.POOR -> SyncApproach.MINIMAL_SYNC
            ConnectionQuality.UNKNOWN -> SyncApproach.CONSERVATIVE_SYNC
        }
    }
}

/**
 * Different sync approaches based on network conditions
 */
enum class SyncApproach {
    FULL_SYNC,        // Sync everything in optimal chunks
    CHUNKED_SYNC,     // Use smaller chunks, more conservative
    MINIMAL_SYNC,     // Only essential data, smallest chunks
    CONSERVATIVE_SYNC // Very conservative approach for unknown conditions
}