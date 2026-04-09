package com.ash.simpledataentry.data.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.ash.simpledataentry.data.SessionManager
import com.ash.simpledataentry.data.local.AppDatabase
import com.ash.simpledataentry.data.security.AccountEncryption
import com.ash.simpledataentry.domain.model.Dhis2Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.hisp.dhis.android.core.D2
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enhanced session data with persistence state
 */
data class SessionState(
    val isActive: Boolean = false,
    val isOnline: Boolean = false,
    val username: String? = null,
    val serverUrl: String? = null,
    val lastSyncTime: Long? = null,
    val sessionExpiryTime: Long? = null,
    val offlineMode: Boolean = false,
    val credentialsStored: Boolean = false
)

/**
 * Session persistence preferences
 */
data class SessionPreferences(
    val autoReconnect: Boolean = true,
    val offlineTimeout: Long = 24 * 60 * 60 * 1000L, // 24 hours
    val maxRetryAttempts: Int = 3,
    val sessionTimeout: Long = 8 * 60 * 60 * 1000L // 8 hours
)

/**
 * Enhanced session manager with improved persistence and offline capabilities
 */
@Singleton
class EnhancedSessionManager @Inject constructor(
    private val baseSessionManager: SessionManager,
    private val accountEncryption: AccountEncryption
) {

    companion object {
        private const val TAG = "EnhancedSessionManager"
        private const val SESSION_PREFS = "enhanced_session_prefs"
        private const val ENCRYPTED_PREFS = "encrypted_session_data"
        private const val SESSION_TOKEN_KEY = "session_token"
        private const val CREDENTIALS_HASH_KEY = "credentials_hash"
        private const val LAST_ACTIVITY_KEY = "last_activity"
        private const val SESSION_EXPIRY_KEY = "session_expiry"
        private const val OFFLINE_MODE_KEY = "offline_mode"
        private const val AUTO_RECONNECT_KEY = "auto_reconnect"
    }

    private val _sessionState = MutableStateFlow(SessionState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var sessionPreferences = SessionPreferences()
    private var lastActivityTime = System.currentTimeMillis()

    /**
     * Initialize enhanced session manager
     */
    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        loadSessionPreferences(context)
        restoreSessionIfValid(context)
        updateSessionState(context)
    }

    /**
     * Enhanced login with session persistence
     */
    suspend fun login(
        context: Context,
        dhis2Config: Dhis2Config,
        database: AppDatabase,
        rememberCredentials: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Perform base login
            baseSessionManager.login(context, dhis2Config, database)

            // Create session data
            val sessionToken = generateSessionToken(dhis2Config)
            val credentialsHash = hashCredentials(dhis2Config)
            val sessionExpiry = System.currentTimeMillis() + sessionPreferences.sessionTimeout

            // Store session data
            storeSessionData(context, sessionToken, credentialsHash, sessionExpiry)

            // Store encrypted credentials if requested
            if (rememberCredentials) {
                storeEncryptedCredentials(context, dhis2Config)
            }

            updateSessionState(context)
            updateLastActivity()

            Log.d(TAG, "Enhanced login successful for ${dhis2Config.username}")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Enhanced login failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Check if session is still valid and refresh if needed
     */
    suspend fun validateAndRefreshSession(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefs = getSessionPrefs(context)
            val sessionExpiry = prefs.getLong(SESSION_EXPIRY_KEY, 0)
            val now = System.currentTimeMillis()

            // Check if session has expired
            if (sessionExpiry > 0 && now > sessionExpiry) {
                Log.d(TAG, "Session expired, attempting auto-refresh")
                return@withContext attemptAutoReconnect(context)
            }

            // Check if base session is still active
            val isBaseActive = baseSessionManager.isSessionActive()
            if (!isBaseActive) {
                Log.d(TAG, "Base session inactive, attempting auto-refresh")
                return@withContext attemptAutoReconnect(context)
            }

            updateLastActivity()
            updateSessionState(context)
            true

        } catch (e: Exception) {
            Log.e(TAG, "Session validation failed: ${e.message}", e)
            false
        }
    }

    /**
     * Enable offline mode with stored credentials
     */
    suspend fun enableOfflineMode(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefs = getSessionPrefs(context)
            prefs.edit {
                putBoolean(OFFLINE_MODE_KEY, true)
                putLong(LAST_ACTIVITY_KEY, System.currentTimeMillis())
            }

            updateSessionState(context)
            Log.d(TAG, "Offline mode enabled")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable offline mode: ${e.message}", e)
            false
        }
    }

    /**
     * Disable offline mode and attempt reconnection
     */
    suspend fun disableOfflineMode(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefs = getSessionPrefs(context)
            prefs.edit {
                putBoolean(OFFLINE_MODE_KEY, false)
            }

            // Attempt to reconnect
            val reconnected = attemptAutoReconnect(context)
            updateSessionState(context)

            Log.d(TAG, "Offline mode disabled, reconnection: $reconnected")
            reconnected

        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable offline mode: ${e.message}", e)
            false
        }
    }

    /**
     * Get current D2 instance
     */
    fun getD2(): D2? = baseSessionManager.getD2()

    /**
     * Check if session is active (online or valid offline)
     */
    fun isSessionActive(): Boolean {
        val state = _sessionState.value
        return state.isActive || (state.offlineMode && state.credentialsStored)
    }

    /**
     * Logout and clear session data
     */
    suspend fun logout(context: Context) = withContext(Dispatchers.IO) {
        try {
            baseSessionManager.logout()
            clearSessionData(context)
            updateSessionState(context)
            Log.d(TAG, "Enhanced logout completed")

        } catch (e: Exception) {
            Log.e(TAG, "Logout failed: ${e.message}", e)
        }
    }

    /**
     * Update session preferences
     */
    fun updatePreferences(preferences: SessionPreferences) {
        sessionPreferences = preferences
    }

    /**
     * Private helper methods
     */

    private fun getSessionPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
    }

    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(ENCRYPTED_PREFS, Context.MODE_PRIVATE)
    }

    private suspend fun loadSessionPreferences(context: Context) {
        val prefs = getSessionPrefs(context)
        sessionPreferences = SessionPreferences(
            autoReconnect = prefs.getBoolean(AUTO_RECONNECT_KEY, true),
            offlineTimeout = prefs.getLong("offline_timeout", sessionPreferences.offlineTimeout),
            maxRetryAttempts = prefs.getInt("max_retry_attempts", sessionPreferences.maxRetryAttempts),
            sessionTimeout = prefs.getLong("session_timeout", sessionPreferences.sessionTimeout)
        )
    }

    private suspend fun restoreSessionIfValid(context: Context): Boolean {
        if (!sessionPreferences.autoReconnect) return false

        val prefs = getSessionPrefs(context)
        val sessionExpiry = prefs.getLong(SESSION_EXPIRY_KEY, 0)
        val lastActivity = prefs.getLong(LAST_ACTIVITY_KEY, 0)
        val now = System.currentTimeMillis()

        // Check if we can restore session
        if (sessionExpiry > now && (now - lastActivity) < sessionPreferences.offlineTimeout) {
            return attemptAutoReconnect(context)
        }

        return false
    }

    private suspend fun attemptAutoReconnect(context: Context): Boolean {
        if (!sessionPreferences.autoReconnect) return false

        try {
            val credentials = loadEncryptedCredentials(context) ?: return false
            val database = getDatabase(context) ?: return false

            Log.d(TAG, "Attempting auto-reconnect for ${credentials.username}")

            val result = login(context, credentials, database, rememberCredentials = true)
            return result.isSuccess

        } catch (e: Exception) {
            Log.e(TAG, "Auto-reconnect failed: ${e.message}", e)
            return false
        }
    }

    private fun generateSessionToken(config: Dhis2Config): String {
        val data = "${config.username}:${config.serverUrl}:${System.currentTimeMillis()}"
        return hashString(data)
    }

    private fun hashCredentials(config: Dhis2Config): String {
        val data = "${config.username}:${config.password}:${config.serverUrl}"
        return hashString(data)
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private suspend fun storeSessionData(
        context: Context,
        sessionToken: String,
        credentialsHash: String,
        sessionExpiry: Long
    ) {
        val prefs = getSessionPrefs(context)
        prefs.edit {
            putString(SESSION_TOKEN_KEY, sessionToken)
            putString(CREDENTIALS_HASH_KEY, credentialsHash)
            putLong(SESSION_EXPIRY_KEY, sessionExpiry)
            putLong(LAST_ACTIVITY_KEY, System.currentTimeMillis())
        }
    }

    private suspend fun storeEncryptedCredentials(context: Context, config: Dhis2Config) {
        try {
            val encryptedUsername = accountEncryption.encryptPassword(config.username)
            val encryptedPassword = accountEncryption.encryptPassword(config.password)
            val encryptedServerUrl = accountEncryption.encryptPassword(config.serverUrl)

            val prefs = getEncryptedPrefs(context)
            prefs.edit {
                putString("encrypted_username", encryptedUsername)
                putString("encrypted_password", encryptedPassword)
                putString("encrypted_server_url", encryptedServerUrl)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to store encrypted credentials: ${e.message}", e)
        }
    }

    private suspend fun loadEncryptedCredentials(context: Context): Dhis2Config? {
        return try {
            val prefs = getEncryptedPrefs(context)
            val encryptedUsername = prefs.getString("encrypted_username", null) ?: return null
            val encryptedPassword = prefs.getString("encrypted_password", null) ?: return null
            val encryptedServerUrl = prefs.getString("encrypted_server_url", null) ?: return null

            val username = accountEncryption.decryptPassword(encryptedUsername)
            val password = accountEncryption.decryptPassword(encryptedPassword)
            val serverUrl = accountEncryption.decryptPassword(encryptedServerUrl)

            Dhis2Config(serverUrl ?: "", username ?: "", password ?: "")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load encrypted credentials: ${e.message}", e)
            null
        }
    }

    private suspend fun clearSessionData(context: Context) {
        getSessionPrefs(context).edit().clear().apply()
        getEncryptedPrefs(context).edit().clear().apply()
    }

    private fun updateLastActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    private suspend fun updateSessionState(context: Context) {
        val prefs = getSessionPrefs(context)
        val encryptedPrefs = getEncryptedPrefs(context)
        val isBaseActive = baseSessionManager.isSessionActive()
        val offlineMode = prefs.getBoolean(OFFLINE_MODE_KEY, false)
        val credentialsStored = encryptedPrefs.contains("encrypted_username")

        val currentUser = if (isBaseActive) {
            getD2()?.userModule()?.user()?.blockingGet()?.username()
        } else null

        val serverUrl = if (isBaseActive) {
            getD2()?.systemInfoModule()?.systemInfo()?.blockingGet()?.contextPath()
        } else null

        _sessionState.value = SessionState(
            isActive = isBaseActive,
            isOnline = isBaseActive && !offlineMode,
            username = currentUser,
            serverUrl = serverUrl,
            lastSyncTime = prefs.getLong("last_sync_time", 0).takeIf { it > 0 },
            sessionExpiryTime = prefs.getLong(SESSION_EXPIRY_KEY, 0).takeIf { it > 0 },
            offlineMode = offlineMode,
            credentialsStored = credentialsStored
        )
    }

    private fun getDatabase(context: Context): AppDatabase? {
        // This would need to be injected properly in a real implementation
        // For now, return null to indicate database unavailable
        return null
    }
}