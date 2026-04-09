package com.ash.simpledataentry.data

import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import org.hisp.dhis.android.core.D2
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized provider for D2 SDK access.
 *
 * This class provides safe access to the D2 instance, ensuring that:
 * 1. Suspending operations wait for the session to be ready (awaitD2)
 * 2. Non-suspending access returns null if session isn't ready (getD2OrNull)
 * 3. Session readiness can be checked without blocking (isReady)
 *
 * Usage:
 * - In suspend functions, use awaitD2() to wait for session readiness
 * - In non-suspend contexts, use getD2OrNull() with null checks
 * - Check isReady() before starting operations that require D2
 */
@Singleton
class D2Provider @Inject constructor(
    private val sessionManager: SessionManager
) {
    /**
     * Suspending access that waits for D2 to be ready.
     * Use this in all repository suspend functions to avoid race conditions.
     *
     * @return The D2 instance, guaranteed to be non-null and ready
     * @throws IllegalStateException if session cannot be established
     */
    suspend fun awaitD2(): D2 {
        return try {
            sessionManager.awaitD2()
        } catch (e: Exception) {
            Log.e("D2Provider", "Failed to await D2 session", e)
            throw IllegalStateException("D2 session not available", e)
        }
    }

    /**
     * Non-suspending access that returns null if session isn't ready.
     * Use sparingly - prefer awaitD2() in suspend functions.
     *
     * @return The D2 instance if ready, null otherwise
     */
    fun getD2OrNull(): D2? {
        return if (isReady()) {
            sessionManager.getD2()
        } else {
            Log.w("D2Provider", "D2 requested but session not ready")
            null
        }
    }

    /**
     * Check if the D2 session is ready without blocking.
     *
     * @return true if the session is initialized and ready for use
     */
    fun isReady(): Boolean {
        return sessionManager.sessionReady.value
    }

    /**
     * Observable session ready state for UI components.
     */
    val sessionReady: StateFlow<Boolean>
        get() = sessionManager.sessionReady
}
