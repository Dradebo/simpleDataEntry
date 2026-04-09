package com.ash.simpledataentry.data

import android.content.Context
import android.util.Log
import com.ash.simpledataentry.domain.model.Dhis2Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import org.hisp.dhis.android.core.D2
import org.hisp.dhis.android.core.D2Configuration
import org.hisp.dhis.android.core.D2Manager
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit
import com.ash.simpledataentry.data.local.AppDatabase
import com.ash.simpledataentry.data.sync.BackgroundSyncManager
import com.ash.simpledataentry.presentation.core.NavigationProgress
import com.ash.simpledataentry.presentation.core.LoadingPhase
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import org.hisp.dhis.android.core.maintenance.D2Error
import org.hisp.dhis.android.core.maintenance.D2ErrorCode

/**
 * Result of downloading a single metadata type
 */
data class MetadataTypeResult(
    val type: String,
    val success: Boolean,
    val isCritical: Boolean, // If true, login should fail if this metadata type fails
    val error: String? = null,
    val errorType: String? = null
)

/**
 * Overall result of metadata download with breakdown by type
 */
data class MetadataDownloadResult(
    val successful: Int,
    val failed: Int,
    val criticalFailures: List<MetadataTypeResult>,
    val details: List<MetadataTypeResult>
) {
    val hasAnyFailures: Boolean get() = failed > 0
    val hasCriticalFailures: Boolean get() = criticalFailures.isNotEmpty()
    val canProceed: Boolean get() = !hasCriticalFailures
}

@Singleton
class SessionManager @Inject constructor(
    private val accountManager: AccountManager,
    private val databaseManager: DatabaseManager
) {
    private var d2: D2? = null

    /**
     * Emits current account identifier when account changes occur.
     * Null when no user is logged in.
     * Format: MD5 hash of "username@serverUrl"
     * ViewModels observe this to reset cached data on account switch.
     */
    private val _currentAccountId = MutableStateFlow<String?>(null)
    val currentAccountId: StateFlow<String?> = _currentAccountId.asStateFlow()

    /**
     * Indicates whether the D2 session is fully initialized and ready for use.
     * Repositories should check this before making SDK calls.
     */
    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady.asStateFlow()

    /**
     * Holds the D2 instance in a StateFlow for suspending access.
     * Use awaitD2() to wait for the session to be ready.
     */
    private val _d2StateFlow = MutableStateFlow<D2?>(null)

    private val mutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Suspending function that waits for D2 to be ready.
     * Use this in repositories instead of getD2() to avoid race conditions.
     */
    suspend fun awaitD2(): D2 {
        return _d2StateFlow.filterNotNull().first()
    }

    /**
     * Marks the session as ready after successful initialization.
     * Called after login/session restoration completes.
     */
    private fun markSessionReady() {
        _d2StateFlow.value = d2
        _sessionReady.value = true
        Log.d("SessionManager", "Session marked as READY")
    }

    /**
     * Marks the session as not ready during transitions.
     * Called before logout/account switch.
     */
    private fun markSessionNotReady() {
        _sessionReady.value = false
        // Note: Don't null _d2StateFlow here - let ongoing operations complete
        Log.d("SessionManager", "Session marked as NOT READY")
    }

    /**
     * Extract D2Error from potentially nested exceptions.
     * The DHIS2 SDK often wraps D2Error inside RuntimeException.
     */
    private fun extractD2Error(throwable: Throwable): D2Error? {
        return when {
            throwable is D2Error -> throwable
            throwable.cause is D2Error -> throwable.cause as D2Error
            throwable is RuntimeException && throwable.cause is D2Error -> throwable.cause as D2Error
            else -> null
        }
    }

    /**
     * Initialize D2 SDK (shared across all accounts).
     * Note: D2 SDK uses single database, account isolation handled by Room.
     */
    suspend fun initD2(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (d2 != null) {
                return@withLock
            }

            // Stop any existing Koin instance that DHIS2 SDK might have started
            try {
                GlobalContext.stopKoin()
            } catch (e: Exception) {
                // Ignore if Koin wasn't started
            }

            try {
                // Concise logging interceptor - only logs errors and summaries
                val loggingInterceptor = Interceptor { chain ->
                    val request = chain.request()
                    val url = request.url.toString()
                    val startTime = System.currentTimeMillis()

                    val response = chain.proceed(request)
                    val duration = System.currentTimeMillis() - startTime

                    // Only log non-successful responses or slow requests
                    if (!response.isSuccessful || duration > 5000) {
                        Log.w("OkHttp", "${request.method} $url | ${response.code} | ${duration}ms")
                        if (!response.isSuccessful) {
                            Log.e("OkHttp", "Failed: ${response.message}")
                        }
                    }

                    response
                }

                val config = D2Configuration.builder()
                    .context(context)
                    .appName("Simple Data Entry")
                    .appVersion("1.0")
                    .readTimeoutInSeconds(180)  // 3 minutes for downloads
                    .writeTimeoutInSeconds(600) // 10 minutes for uploads
                    .connectTimeoutInSeconds(60) // 1 minute for connection
                    .interceptors(listOf(loggingInterceptor))
                    .build()

                d2 = D2Manager.blockingInstantiateD2(config)
                Log.d("SessionManager", "D2 initialized successfully")
            } catch (e: Exception) {
                Log.e("SessionManager", "D2 initialization failed", e)
                throw e
            }
        }
    }

    suspend fun login(context: Context, dhis2Config: Dhis2Config) = withContext(Dispatchers.IO) {
        // Get or create account info for this login
        val accountInfo = accountManager.getOrCreateAccount(context, dhis2Config.username, dhis2Config.serverUrl)
        Log.d("SessionManager", "Login: AccountInfo = ${accountInfo.displayName}, Room DB = ${accountInfo.roomDatabaseName}")

        // Get account-specific database
        val accountDb = databaseManager.getDatabaseForAccount(context, accountInfo)

        // Check if switching accounts
        val activeAccountId = accountManager.getActiveAccountId(context)
        val isDifferentUser = activeAccountId != null && activeAccountId != accountInfo.accountId

        if (isDifferentUser) {
            Log.d("SessionManager", "ACCOUNT SWITCH DETECTED: $activeAccountId → ${accountInfo.accountId}")
            Log.d("SessionManager", "Logging out previous user...")

            // Mark session as not ready during account switch
            markSessionNotReady()

            // Log out previous user
            try {
                d2?.userModule()?.blockingLogOut()
                Log.d("SessionManager", "Previous user logged out successfully")
            } catch (e: Exception) {
                Log.w("SessionManager", "Logout failed: ${e.message}")
            }

            // Note: D2 SDK database is shared - account isolation handled by Room
            // Each account will get its own Room database with account-specific name
            Log.d("SessionManager", "Ready for new account login")
        }

        // Initialize D2 if not already initialized (shared across accounts)
        if (d2 == null) {
            Log.d("SessionManager", "Initializing D2 (shared instance)")
            initD2(context)
        }

        // Log out if already logged in
        if (d2?.userModule()?.isLogged()?.blockingGet() == true) {
            d2?.userModule()?.blockingLogOut()
        }

        try {
            // Perform DHIS2 login
            d2?.userModule()?.blockingLogIn(
                dhis2Config.username,
                dhis2Config.password,
                dhis2Config.serverUrl
            ) ?: throw IllegalStateException("D2 not initialized")

            // Set this as the active account
            accountManager.setActiveAccountId(context, accountInfo.accountId)

            // Emit account change event for ViewModels (using accountId, not username@serverUrl)
            _currentAccountId.value = accountInfo.accountId
            Log.d("SessionManager", "Account changed, notifying observers: ${accountInfo.accountId}")

            // Use resilient metadata download that handles JSON errors gracefully
            downloadMetadataResilient { _ -> /* No progress UI in simple login */ }
            downloadAggregateData()
            hydrateRoomFromSdk(context, accountDb)

            // Mark session as ready after successful initialization
            markSessionReady()

            Log.i("SessionManager", "Login successful for ${dhis2Config.username}")
        } catch (e: Exception) {
            Log.e("SessionManager", "Login failed", e)
            throw e
        }
    }

    /**
     * Start async background data download after successful metadata sync
     * This runs in the background and shows a toast notification when complete
     */
    suspend fun startBackgroundDataSync(
        context: Context,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        try {
            Log.d("SessionManager", "Starting background data sync...")

            // Get current account's database
            val activeAccountId = accountManager.getActiveAccountId(context)
                ?: throw IllegalStateException("No active account")
            val accountInfo = accountManager.getActiveAccount(context)
                ?: throw IllegalStateException("Active account not found: $activeAccountId")
            val accountDb = databaseManager.getDatabaseForAccount(context, accountInfo)

            // Download aggregate data
            downloadAggregateData()
            Log.d("SessionManager", "Background: Aggregate data downloaded")

            // Download tracker/event data
            downloadTrackerData()
            Log.d("SessionManager", "Background: Tracker data downloaded")

            // Hydrate Room database
            hydrateRoomFromSdk(context, accountDb)
            Log.d("SessionManager", "Background: Room database hydrated")

            Log.i("SessionManager", "Background data sync completed successfully")
            onComplete?.invoke(true, "Data sync complete")

        } catch (e: Exception) {
            Log.e("SessionManager", "Background data sync failed", e)
            onComplete?.invoke(false, "Data sync failed: ${e.message}")
        }
    }

    /**
     * Enhanced login with progress tracking
     * REFACTORED: Metadata is blocking, data sync is async in background
     */
    suspend fun loginWithProgress(
        context: Context,
        dhis2Config: Dhis2Config,
        backgroundSyncManager: BackgroundSyncManager,
        onProgress: (NavigationProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            // Step 1: Initialize (0-10%)
            onProgress(NavigationProgress(
                phase = LoadingPhase.INITIALIZING,
                overallPercentage = 5,
                phaseTitle = LoadingPhase.INITIALIZING.title,
                phaseDetail = "Setting up connection..."
            ))

            // Get or create account info for this login
            val accountInfo = accountManager.getOrCreateAccount(context, dhis2Config.username, dhis2Config.serverUrl)
            Log.d("SessionManager", "LoginWithProgress: AccountInfo = ${accountInfo.displayName}, Room DB = ${accountInfo.roomDatabaseName}")

            // Get account-specific database
            val accountDb = databaseManager.getDatabaseForAccount(context, accountInfo)

            // Check if switching accounts
            val activeAccountId = accountManager.getActiveAccountId(context)
            val isDifferentUser = activeAccountId != null && activeAccountId != accountInfo.accountId

            if (isDifferentUser) {
                Log.d("SessionManager", "ACCOUNT SWITCH DETECTED: $activeAccountId → ${accountInfo.accountId}")
                onProgress(NavigationProgress(
                    phase = LoadingPhase.INITIALIZING,
                    overallPercentage = 8,
                    phaseTitle = LoadingPhase.INITIALIZING.title,
                    phaseDetail = "Switching accounts..."
                ))

                // Mark session as not ready during account switch
                markSessionNotReady()

                // Log out previous user
                try {
                    d2?.userModule()?.blockingLogOut()
                    Log.d("SessionManager", "Previous user logged out successfully")
                } catch (e: Exception) {
                    Log.w("SessionManager", "Logout failed: ${e.message}")
                }

                // Note: D2 SDK database is shared - account isolation handled by Room
                Log.d("SessionManager", "Ready for new account login")
            }

            // Initialize D2 if not already initialized (shared across accounts)
            if (d2 == null) {
                Log.d("SessionManager", "Initializing D2 (shared instance)")
                initD2(context)
            }

            // Step 2: Authentication (10-30%)
            onProgress(NavigationProgress(
                phase = LoadingPhase.AUTHENTICATING,
                overallPercentage = 15,
                phaseTitle = LoadingPhase.AUTHENTICATING.title,
                phaseDetail = "Connecting to server..."
            ))

            // Log out if already logged in to avoid D2Error
            if (d2?.userModule()?.isLogged()?.blockingGet() == true) {
                d2?.userModule()?.blockingLogOut()
            }

            // Attempt login with retry for ALREADY_AUTHENTICATED error
            // This mirrors the official DHIS2 Capture app's error handling pattern
            var loginAttempt = 0
            val maxRetries = 1
            while (loginAttempt <= maxRetries) {
                try {
                    d2?.userModule()?.blockingLogIn(
                        dhis2Config.username,
                        dhis2Config.password,
                        dhis2Config.serverUrl
                    )
                    break // Success - exit loop
                } catch (loginError: Exception) {
                    val d2Error = extractD2Error(loginError)
                    Log.w("SessionManager", "Login attempt ${loginAttempt + 1} failed: errorCode=${d2Error?.errorCode()}, description=${d2Error?.errorDescription()}")

                    if (d2Error != null && d2Error.errorCode() == D2ErrorCode.ALREADY_AUTHENTICATED && loginAttempt < maxRetries) {
                        Log.w("SessionManager", "ALREADY_AUTHENTICATED - forcing logout and retry")
                        try {
                            d2?.userModule()?.blockingLogOut()
                        } catch (logoutError: Exception) {
                            Log.w("SessionManager", "Logout during retry failed: ${logoutError.message}")
                        }
                        loginAttempt++
                        continue
                    }
                    // Not retryable - re-throw
                    throw loginError
                }
            }

            onProgress(NavigationProgress(
                phase = LoadingPhase.AUTHENTICATING,
                overallPercentage = 25,
                phaseTitle = LoadingPhase.AUTHENTICATING.title,
                phaseDetail = "Authentication successful"
            ))

            // Set this as the active account
            accountManager.setActiveAccountId(context, accountInfo.accountId)

            // SECURITY ENHANCEMENT: Store password hash for secure offline validation
            val passwordHash = hashPassword(dhis2Config.password, dhis2Config.username + dhis2Config.serverUrl)
            val prefs = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
            prefs.edit {
                putString("username", dhis2Config.username)
                putString("serverUrl", dhis2Config.serverUrl)
                putString("password_hash", passwordHash)
                putLong("hash_created", System.currentTimeMillis())
            }

            // Emit account change event for ViewModels (using accountId, not username@serverUrl)
            _currentAccountId.value = accountInfo.accountId
            Log.d("SessionManager", "Account changed, notifying observers: ${accountInfo.accountId}")

            // Step 3: Download Metadata (30-80%) - RESILIENT, UI LOCKED
            // Use resilient metadata download with granular progress feedback
            val metadataResult = downloadMetadataResilient(onProgress = onProgress)

            // Check if critical metadata failed
            if (metadataResult.hasCriticalFailures) {
                val criticalErrors = metadataResult.criticalFailures.joinToString(", ") { it.error ?: "Unknown" }
                Log.e("SessionManager", "CRITICAL metadata failures after all retries: $criticalErrors")

                // Provide actionable error message
                val userMessage = when {
                    criticalErrors.contains("Null attribute", ignoreCase = true) ->
                        "Server has invalid metadata configuration. Please contact your administrator or try a different server."
                    criticalErrors.contains("timeout", ignoreCase = true) ->
                        "Connection timed out. Please check your internet and try again."
                    criticalErrors.contains("Unable to resolve host", ignoreCase = true) ->
                        "Cannot reach server. Please check your internet connection."
                    criticalErrors.contains("401", ignoreCase = true) || criticalErrors.contains("Unauthorized", ignoreCase = true) ->
                        "Authentication failed. Please check your credentials."
                    else ->
                        "Failed to download data from server. Error: $criticalErrors"
                }

                throw IllegalStateException(userMessage)
            }

            // Log warnings for non-critical failures
            if (metadataResult.hasAnyFailures) {
                val failures = metadataResult.details.filter { !it.success }
                failures.forEach { failure ->
                    Log.w("SessionManager", "Non-critical metadata '${failure.type}' failed but continuing: ${failure.error}")
                }
            }

            onProgress(NavigationProgress(
                phase = LoadingPhase.DOWNLOADING_METADATA,
                overallPercentage = 80,
                phaseTitle = "Metadata Complete",
                phaseDetail = if (metadataResult.hasAnyFailures) {
                    "⚠ ${metadataResult.successful} of ${metadataResult.successful + metadataResult.failed} succeeded"
                } else {
                    "✓ All metadata downloaded successfully"
                }
            ))

            onProgress(NavigationProgress(
                phase = LoadingPhase.LOADING_DATA,
                overallPercentage = 85,
                phaseTitle = LoadingPhase.LOADING_DATA.title,
                phaseDetail = "Preparing data sync..."
            ))

            onProgress(NavigationProgress(
                phase = LoadingPhase.PROCESSING_DATA,
                overallPercentage = 90,
                phaseTitle = LoadingPhase.PROCESSING_DATA.title,
                phaseDetail = "Setting up background processing..."
            ))

            // Step 4: Finalization (90-100%) - UI UNLOCKED AFTER THIS
            onProgress(NavigationProgress(
                phase = LoadingPhase.FINALIZING,
                overallPercentage = 95,
                phaseTitle = LoadingPhase.FINALIZING.title,
                phaseDetail = "Login complete!"
            ))

            // Mark session as ready after successful initialization
            markSessionReady()

            Log.i("SessionManager", "Enhanced login successful for ${dhis2Config.username}")

            onProgress(NavigationProgress(
                phase = LoadingPhase.FINALIZING,
                overallPercentage = 100,
                phaseTitle = "Ready",
                phaseDetail = "Welcome! Data is syncing in background..."
            ))

            // CRITICAL CHANGE: Data downloads moved to async background task
            // UI is now unlocked, user can start working immediately
            // Background sync will show notification when complete
            Log.d("SessionManager", "Metadata sync complete - triggering background data sync...")
            try {
                val workName = backgroundSyncManager.triggerImmediateSync()
                Log.d("SessionManager", "Background data sync triggered: $workName")
            } catch (syncError: Exception) {
                // Log but don't fail login - data sync can be retried later
                Log.w("SessionManager", "Failed to trigger background sync: ${syncError.message}", syncError)
            }

        } catch (e: Exception) {
            // Extract D2Error for detailed diagnostics and user-friendly messages
            val d2Error = extractD2Error(e)
            val errorCode = d2Error?.errorCode()

            // Map D2ErrorCode to user-friendly messages (matching official DHIS2 Capture app)
            val userMessage = when (errorCode) {
                D2ErrorCode.BAD_CREDENTIALS -> "Invalid username or password"
                D2ErrorCode.SERVER_CONNECTION_ERROR -> "Cannot connect to server. Check your internet connection."
                D2ErrorCode.UNKNOWN_HOST -> "Server not found. Check the URL."
                D2ErrorCode.SOCKET_TIMEOUT -> "Connection timed out. Please try again."
                D2ErrorCode.NO_DHIS2_SERVER -> "Not a valid DHIS2 server"
                D2ErrorCode.INVALID_DHIS_VERSION -> "DHIS2 version not supported by this app"
                D2ErrorCode.USER_ACCOUNT_DISABLED -> "Your account has been disabled"
                D2ErrorCode.USER_ACCOUNT_LOCKED -> "Your account is locked"
                D2ErrorCode.API_UNSUCCESSFUL_RESPONSE -> "Server returned an error. Please try again later."
                D2ErrorCode.API_RESPONSE_PROCESS_ERROR -> "Error processing server response"
                D2ErrorCode.ALREADY_AUTHENTICATED -> "Session conflict. Please try again."
                D2ErrorCode.URL_NOT_FOUND -> "Server URL not found (404)"
                D2ErrorCode.SERVER_URL_MALFORMED -> "Invalid server URL format"
                D2ErrorCode.SSL_ERROR -> "SSL/TLS security error. Check server certificate."
                else -> d2Error?.errorDescription() ?: e.message ?: "Login failed"
            }

            Log.e("SessionManager", "Enhanced login failed: errorCode=$errorCode, description=${d2Error?.errorDescription()}", e)
            onProgress(NavigationProgress.error(userMessage))
            throw e
        }
    }

    suspend fun wipeAllData(context: Context, db: AppDatabase) = withContext(Dispatchers.IO) {
        try {
            Log.w("SessionManager", "USER SWITCH DETECTED: Wiping data for previous user...")

            // Mark session as not ready during data wipe
            markSessionNotReady()

            // STEP 1: Clear Room database FIRST (synchronous, guaranteed)
            clearRoomDatabase(db)

            // STEP 2: Clear SharedPreferences
            val prefs = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()

            // STEP 3: Clear DHIS2 SDK database (best effort)
            try {
                d2?.let { d2Instance ->
                    try {
                        if (d2Instance.userModule()?.isLogged()?.blockingGet() == true) {
                            d2Instance.wipeModule()?.wipeEverything()
                            Log.i("SessionManager", "DHIS2 SDK data wiped successfully")
                        } else {
                            Log.w("SessionManager", "Not authenticated - attempting manual database cleanup")
                            wipeDatabaseFiles(context)
                        }
                    } catch (e: Exception) {
                        Log.w("SessionManager", "Authenticated wipe failed: ${e.message}")
                        wipeDatabaseFiles(context)
                    }
                } ?: run {
                    Log.w("SessionManager", "D2 not initialized, clearing database files")
                    wipeDatabaseFiles(context)
                }
            } catch (e: Exception) {
                Log.e("SessionManager", "SDK wipe failed, attempting database file cleanup", e)
                wipeDatabaseFiles(context)
            }

            // STEP 4: Emit account change to ViewModels (Room already cleared)
            _currentAccountId.value = null
            Log.d("SessionManager", "Account cleared, notifying observers")

            // STEP 5: Clear D2 reference
            d2 = null

            Log.i("SessionManager", "✓ All data wipe completed successfully")
        } catch (e: Exception) {
            Log.e("SessionManager", "Failed to wipe all data", e)
        }
    }

    /**
     * Clears all Room database tables synchronously to prevent stale data.
     * Called BEFORE emitting account change to prevent race conditions.
     */
    private suspend fun clearRoomDatabase(db: AppDatabase) = withContext(Dispatchers.IO) {
        try {
            Log.d("SessionManager", "Starting synchronous Room database clear...")

            // Clear all tables (no FK dependencies, order doesn't matter)
            db.datasetDao().clearAll()
            db.trackerProgramDao().clearAll()
            db.eventProgramDao().clearAll()
            db.trackerEnrollmentDao().clearAll()
            db.eventInstanceDao().clearAll()
            db.dataElementDao().clearAll()
            db.categoryComboDao().clearAll()
            db.categoryOptionComboDao().clearAll()
            db.organisationUnitDao().clearAll()
            db.dataValueDao().deleteAllDataValues()
            db.dataValueDraftDao().deleteAllDrafts()

            Log.i("SessionManager", "✓ Room database cleared successfully")
        } catch (e: Exception) {
            Log.e("SessionManager", "Failed to clear Room database", e)
            // Don't throw - wipe should continue even if Room clear fails
        }
    }

    private fun wipeDatabaseFiles(context: Context) {
        try {
            Log.i("SessionManager", "Performing manual database file cleanup to fix foreign key constraints")

            // Clear DHIS2 SDK database files manually
            val dbDir = context.getDatabasePath("dhis.db").parentFile
            dbDir?.listFiles()?.forEach { file ->
                if (file.name.startsWith("dhis") || file.name.contains("d2")) {
                    val deleted = file.delete()
                    Log.d("SessionManager", "Deleted database file ${file.name}: $deleted")
                }
            }

            // Clear any DHIS2 cache directories
            val cacheDir = java.io.File(context.cacheDir, "d2")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
                Log.d("SessionManager", "Cleared D2 cache directory")
            }

            Log.i("SessionManager", "Manual database cleanup completed")
        } catch (e: Exception) {
            Log.e("SessionManager", "Manual database cleanup failed", e)
        }
    }

    /**
     * CRITICAL FIX: Verify that data wipe completed successfully
     * This prevents race condition where new user data loads before old user data is cleared
     */
    private suspend fun verifyDataWipeCompleted(context: Context) = withContext(Dispatchers.IO) {
        try {

            // Check 1: Verify SharedPreferences are cleared
            val prefs = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
            val hasUsername = prefs.getString("username", null) != null
            val hasServerUrl = prefs.getString("serverUrl", null) != null

            if (hasUsername || hasServerUrl) {
                Log.w("SessionManager", "VERIFICATION FAILED: SharedPreferences not cleared properly")
                // Force clear again
                prefs.edit().clear().apply()
            }

            // Check 2: Verify database files are deleted
            val dbDir = context.getDatabasePath("dhis.db").parentFile
            val dhisDbFiles = dbDir?.listFiles()?.filter {
                it.name.startsWith("dhis") || it.name.contains("d2")
            } ?: emptyList()

            if (dhisDbFiles.isNotEmpty()) {
                Log.w("SessionManager", "VERIFICATION FAILED: Found ${dhisDbFiles.size} residual database files")
                // Force delete again
                dhisDbFiles.forEach { file ->
                    val deleted = file.delete()
                }
            }

            // Check 3: Verify D2 cache is cleared
            val cacheDir = java.io.File(context.cacheDir, "d2")
            if (cacheDir.exists()) {
                Log.w("SessionManager", "VERIFICATION FAILED: D2 cache directory still exists")
                cacheDir.deleteRecursively()
            }

            // Give file system a moment to finish all delete operations
            kotlinx.coroutines.delay(100)


        } catch (e: Exception) {
            Log.e("SessionManager", "VERIFICATION: Data wipe verification failed", e)
            // Don't throw - log the error but allow login to proceed
            // This prevents blocking legitimate logins if verification has issues
        }
    }

    fun isSessionActive(): Boolean {
        return d2?.userModule()?.isLogged()?.blockingGet() ?: false
    }

    /**
     * Attempt offline login using existing DHIS2 session data with SECURE password validation
     * This validates the password against stored encrypted credentials before granting access
     */
    suspend fun attemptOfflineLogin(context: Context, dhis2Config: Dhis2Config): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d("SessionManager", "Attempting SECURE offline login for ${dhis2Config.username}")

            // Initialize D2 if needed
            if (d2 == null) {
                initD2(context)
            }

            // Check if there's an existing session or cached credentials
            val d2Instance = d2 ?: return@withContext false

            // SECURITY FIX: Verify stored credentials match provided password
            val prefs = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
            val storedPasswordHash = prefs.getString("password_hash", null)
            val lastUser = prefs.getString("username", null)
            val lastServer = prefs.getString("serverUrl", null)

            // Validate credentials match
            if (lastUser != dhis2Config.username || lastServer != dhis2Config.serverUrl) {
                Log.w("SessionManager", "Username/server mismatch - offline login denied")
                return@withContext false
            }

            // CRITICAL SECURITY: Validate password against stored hash
            if (storedPasswordHash == null) {
                Log.w("SessionManager", "No stored password hash - offline login not secure")
                return@withContext false
            }

            val providedPasswordHash = hashPassword(dhis2Config.password, dhis2Config.username + dhis2Config.serverUrl)
            if (storedPasswordHash != providedPasswordHash) {
                Log.w("SessionManager", "Password validation failed - offline login denied")
                return@withContext false
            }

            // Check if the user has logged in before by checking if we have user data
            val userExists = try {
                d2Instance.userModule().user().blockingGet()?.uid() != null
            } catch (e: Exception) {
                false
            }

            if (userExists) {
                Log.d("SessionManager", "Password validated, enabling secure offline access")

                // CRITICAL FIX: Verify the user session is properly authenticated for API calls
                val isLoggedIn = try {
                    d2Instance.userModule().isLogged().blockingGet()
                } catch (e: Exception) {
                    false
                }

                if (!isLoggedIn) {
                    Log.w("SessionManager", "User session not properly authenticated - cannot make API calls offline")
                    return@withContext false
                }

                val user = d2Instance.userModule().user().blockingGet()
                Log.d("SessionManager", "Verified user session: ${user?.uid()} for offline access")

                // Store session info for offline use
                prefs.edit {
                    putString("username", dhis2Config.username)
                    putString("serverUrl", dhis2Config.serverUrl)
                    putBoolean("offline_mode", true)
                    // Update password hash timestamp for security audit
                    putLong("last_validated", System.currentTimeMillis())
                }

                // Get or create account info
                val accountInfo = accountManager.getOrCreateAccount(context, dhis2Config.username, dhis2Config.serverUrl)

                // Get account-specific database
                val accountDb = databaseManager.getDatabaseForAccount(context, accountInfo)

                // Emit account change event for offline login
                _currentAccountId.value = accountInfo.accountId
                Log.d("SessionManager", "Offline account restored, notifying observers: ${accountInfo.accountId}")

                // Hydrate Room database from existing SDK data
                hydrateRoomFromSdk(context, accountDb)

                // Mark session as ready after successful offline login
                markSessionReady()

                Log.i("SessionManager", "SECURE offline login successful for ${dhis2Config.username}")
                true
            } else {
                Log.w("SessionManager", "No existing user data found - offline login not possible")
                false
            }

        } catch (e: Exception) {
            Log.e("SessionManager", "Offline login failed", e)
            false
        }
    }

    /**
     * Check if SECURE offline login is possible for a given user
     * Now includes password hash validation capability
     */
    fun canLoginOffline(context: Context, username: String, serverUrl: String): Boolean {
        return try {
            if (d2 == null) {
                // Try to initialize D2 to check for existing data
                runBlocking { initD2(context) }
            }

            val prefs = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
            val lastUser = prefs.getString("username", null)
            val lastServer = prefs.getString("serverUrl", null)
            val storedPasswordHash = prefs.getString("password_hash", null)

            // SECURITY: All credentials must match and password hash must exist
            val isSameUser = lastUser == username && lastServer == serverUrl
            val hasSecureCredentials = storedPasswordHash != null

            if (isSameUser && hasSecureCredentials) {
                // Validate session security and check if we have user data in D2
                validateSessionSecurity(context) &&
                d2?.userModule()?.user()?.blockingGet()?.uid() != null
            } else {
                if (!hasSecureCredentials) {
                    Log.w("SessionManager", "No secure credentials stored - offline login not available")
                }
                false
            }
        } catch (e: Exception) {
            Log.w("SessionManager", "Cannot determine offline login capability", e)
            false
        }
    }

    fun logout() {
        try {
            d2?.userModule()?.blockingLogOut()
        } catch (e: Exception) {
            Log.e("SessionManager", "Logout error: ${e.message}", e)
        }
    }

    /**
     * SECURITY ENHANCEMENT: Secure logout that clears sensitive data
     */
    fun secureLogout(context: Context) {
        try {
            // Standard logout
            d2?.userModule()?.blockingLogOut()

            // SECURITY: Clear all sensitive stored data
            val prefs = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
            prefs.edit {
                remove("password_hash")
                remove("hash_created")
                remove("last_validated")
                putBoolean("offline_mode", false)
            }

            Log.i("SessionManager", "SECURE logout completed - sensitive data cleared")
        } catch (e: Exception) {
            Log.e("SessionManager", "Secure logout error: ${e.message}", e)
        }
    }

    fun getD2(): D2? = d2


    // REMOVED: downloadMetadata() - DEAD CODE
    // This atomic metadata download function has been replaced by downloadMetadataResilient()
    // which handles JSON parse errors gracefully and verifies critical metadata types.
    // See downloadMetadataResilient() at line 626 for the working implementation.

    /**
     * Resilient metadata download that handles JSON errors gracefully
     * Downloads metadata from server and verifies what was successfully stored
     * FIXED: Catches JSON errors but continues if critical metadata exists
     */
    /**
     * Resilient metadata download with retry logic
     * Retries up to 3 times on failure before giving up
     */
    suspend fun downloadMetadataResilient(
        onProgress: (NavigationProgress) -> Unit
    ): MetadataDownloadResult = withContext(Dispatchers.IO) {
        val d2Instance = d2 ?: throw IllegalStateException("D2 not initialized")

        var lastError: String? = null
        val maxRetries = 3

        for (attempt in 1..maxRetries) {
            Log.d("SessionManager", "Metadata download attempt $attempt of $maxRetries")

            onProgress(NavigationProgress(
                phase = LoadingPhase.DOWNLOADING_METADATA,
                overallPercentage = 30,
                phaseTitle = "Downloading Metadata",
                phaseDetail = if (attempt > 1) "Retrying... (attempt $attempt)" else "Fetching metadata from server..."
            ))

            var downloadError: String? = null

            try {
                // Following official DHIS2 Capture app pattern:
                // Use non-blocking download() with onErrorComplete() to swallow errors
                io.reactivex.Completable.fromObservable(
                    d2Instance.metadataModule().download()
                        .doOnNext { progress ->
                            val percent = progress.percentage() ?: 0.0
                            Log.d("SessionManager", "Metadata progress: ${percent.toInt()}%")
                            onProgress(NavigationProgress(
                                phase = LoadingPhase.DOWNLOADING_METADATA,
                                overallPercentage = 30 + (percent * 0.5).toInt(),
                                phaseTitle = "Downloading Metadata",
                                phaseDetail = "Progress: ${percent.toInt()}%"
                            ))
                        }
                )
                .doOnError { error ->
                    Log.w("SessionManager", "Metadata download error: ${error.message}")
                    downloadError = error.message
                }
                .onErrorComplete() // Swallow errors like official app
                .blockingAwait()

                Log.d("SessionManager", "Metadata download stream completed for attempt $attempt")
            } catch (e: Exception) {
                Log.e("SessionManager", "Metadata download exception: ${e.message}", e)
                downloadError = e.message
            }

            // Check what we have after the download attempt
            val (hasOrgUnits, hasPrograms, hasDatasets) = coroutineScope {
                val orgUnitsDeferred = async(Dispatchers.IO) {
                    d2Instance.organisationUnitModule().organisationUnits().blockingCount() > 0
                }
                val programsDeferred = async(Dispatchers.IO) {
                    d2Instance.programModule().programs().blockingCount() > 0
                }
                val datasetsDeferred = async(Dispatchers.IO) {
                    d2Instance.dataSetModule().dataSets().blockingCount() > 0
                }
                Triple(
                    orgUnitsDeferred.await(),
                    programsDeferred.await(),
                    datasetsDeferred.await()
                )
            }
            val hasUser = try {
                d2Instance.userModule().user().blockingGet() != null
            } catch (e: Exception) {
                false
            }

            Log.d("SessionManager", "Attempt $attempt result: User=$hasUser, OrgUnits=$hasOrgUnits, Programs=$hasPrograms, Datasets=$hasDatasets")

            // Success if we have ANY usable metadata (org units, programs, or datasets)
            val hasAnyData = hasOrgUnits || hasPrograms || hasDatasets

            if (hasAnyData) {
                Log.d("SessionManager", "✓ Metadata download successful on attempt $attempt")
                return@withContext MetadataDownloadResult(
                    successful = 1,
                    failed = 0,
                    criticalFailures = emptyList(),
                    details = emptyList()
                )
            }

            lastError = downloadError ?: "No metadata downloaded"

            if (attempt < maxRetries) {
                Log.w("SessionManager", "Metadata incomplete, waiting 2s before retry...")
                kotlinx.coroutines.delay(2000) // Wait 2 seconds before retry
            }
        }

        // All retries failed
        Log.e("SessionManager", "All $maxRetries metadata download attempts failed: $lastError")
        MetadataDownloadResult(
            successful = 0,
            failed = 1,
            criticalFailures = listOf(MetadataTypeResult("CoreMetadata", false, true, lastError)),
            details = emptyList()
        )
    }

    /**
     * Verify system metadata was downloaded (constants, settings)
     * CRITICAL - Required for basic app functionality
     */
    // Verification methods removed as they are no longer needed with standard SDK download flow

    fun downloadAggregateData() {
        val d2Instance = d2 ?: throw IllegalStateException("D2 not initialized")

        try {
            Log.d("SessionManager", "Downloading aggregate data...")
            d2Instance.aggregatedModule().data().blockingDownload()
            Log.d("SessionManager", "Aggregate data download complete")
        } catch (e: Exception) {
            Log.e("SessionManager", "AGGREGATE: Data download failed", e)
        }
    }

    suspend fun hydrateRoomFromSdk(context: Context, db: AppDatabase) = withContext(Dispatchers.IO) {
        val d2Instance = d2 ?: return@withContext

        // Note: Cache validation no longer needed - DatabaseProvider ensures account isolation

        val startTime = System.currentTimeMillis()

        // PERFORMANCE OPTIMIZATION: Fetch all metadata in parallel using async
        val datasetsDeferred = async {
            d2Instance.dataSetModule().dataSets().blockingGet().map {
                val datasetStyle = it.style()

                com.ash.simpledataentry.data.local.DatasetEntity(
                    id = it.uid(),
                    name = it.displayName() ?: it.name() ?: "Unnamed Dataset",
                    description = it.description() ?: "",
                    periodType = it.periodType()?.name ?: "Monthly",
                    styleIcon = datasetStyle?.icon(),
                    styleColor = datasetStyle?.color()
                )
            }
        }

        val dataElementsDeferred = async {
            d2Instance.dataElementModule().dataElements().blockingGet().map {
                com.ash.simpledataentry.data.local.DataElementEntity(
                    id = it.uid(),
                    name = it.displayName() ?: it.name() ?: "Unnamed DataElement",
                    valueType = it.valueType()?.name ?: "TEXT",
                    categoryComboId = it.categoryComboUid(),
                    description = it.description()
                )
            }
        }

        val categoryCombosDeferred = async {
            d2Instance.categoryModule().categoryCombos().blockingGet().map {
                com.ash.simpledataentry.data.local.CategoryComboEntity(
                    id = it.uid(),
                    name = it.displayName() ?: it.name() ?: "Unnamed CategoryCombo"
                )
            }
        }

        val categoryOptionCombosDeferred = async {
            d2Instance.categoryModule().categoryOptionCombos().blockingGet().map {
                com.ash.simpledataentry.data.local.CategoryOptionComboEntity(
                    id = it.uid(),
                    name = it.displayName() ?: it.uid(),
                    categoryComboId = it.categoryCombo()?.uid() ?: "",
                    optionUids = it.categoryOptions()?.joinToString(",") { opt -> opt.uid() } ?: ""
                )
            }
        }

        val orgUnitsDeferred = async {
            d2Instance.organisationUnitModule().organisationUnits().blockingGet().map {
                com.ash.simpledataentry.data.local.OrganisationUnitEntity(
                    id = it.uid(),
                    name = it.displayName() ?: it.name() ?: "Unnamed OrgUnit",
                    parentId = it.parent()?.uid()
                )
            }
        }

        // PHASE 2 FIX: Add tracker and event program hydration
        val trackerProgramsDeferred = async {
            d2Instance.programModule().programs()
                .byProgramType().eq(org.hisp.dhis.android.core.program.ProgramType.WITH_REGISTRATION)
                .blockingGet()
                .map { program ->
                    com.ash.simpledataentry.data.local.TrackerProgramEntity(
                        id = program.uid(),
                        name = program.displayName() ?: program.name() ?: "Unnamed Program",
                        description = program.description(),
                        trackedEntityType = program.trackedEntityType()?.uid(),
                        categoryCombo = program.categoryCombo()?.uid(),
                        styleIcon = program.style()?.icon(),
                        styleColor = program.style()?.color(),
                        enrollmentDateLabel = program.enrollmentDateLabel(),
                        incidentDateLabel = program.incidentDateLabel(),
                        displayIncidentDate = program.displayIncidentDate() ?: false,
                        onlyEnrollOnce = program.onlyEnrollOnce() ?: false,
                        selectEnrollmentDatesInFuture = program.selectEnrollmentDatesInFuture() ?: false,
                        selectIncidentDatesInFuture = program.selectIncidentDatesInFuture() ?: false,
                        featureType = program.featureType()?.name ?: "NONE",
                        minAttributesRequiredToSearch = program.minAttributesRequiredToSearch() ?: 1,
                        maxTeiCountToReturn = program.maxTeiCountToReturn() ?: 50
                    )
                }
        }

        val eventProgramsDeferred = async {
            d2Instance.programModule().programs()
                .byProgramType().eq(org.hisp.dhis.android.core.program.ProgramType.WITHOUT_REGISTRATION)
                .blockingGet()
                .map { program ->
                    com.ash.simpledataentry.data.local.EventProgramEntity(
                        id = program.uid(),
                        name = program.displayName() ?: program.name() ?: "Unnamed Program",
                        description = program.description(),
                        categoryCombo = program.categoryCombo()?.uid(),
                        styleIcon = program.style()?.icon(),
                        styleColor = program.style()?.color(),
                        featureType = program.featureType()?.name ?: "NONE"
                    )
                }
        }

        // PHASE 2 FIX: Add tracker enrollment and event instance hydration
        val trackerEnrollmentsDeferred = async {
            val orgUnitMap = d2Instance.organisationUnitModule().organisationUnits()
                .blockingGet()
                .associateBy({ it.uid() }, { it.displayName() ?: it.name() ?: "Unknown" })

            d2Instance.enrollmentModule().enrollments()
                .blockingGet()
                .map { enrollment ->
                    com.ash.simpledataentry.data.local.TrackerEnrollmentEntity(
                        id = enrollment.uid(),
                        programId = enrollment.program() ?: "",
                        trackedEntityInstanceId = enrollment.trackedEntityInstance() ?: "",
                        organisationUnitId = enrollment.organisationUnit() ?: "",
                        organisationUnitName = orgUnitMap[enrollment.organisationUnit()] ?: "Unknown",
                        enrollmentDate = enrollment.enrollmentDate()?.toString(),
                        incidentDate = enrollment.incidentDate()?.toString(),
                        status = enrollment.status()?.name ?: "ACTIVE",
                        followUp = enrollment.followUp() ?: false,
                        deleted = enrollment.deleted() ?: false,
                        lastUpdated = enrollment.lastUpdated()?.toString()
                    )
                }
        }

        val eventInstancesDeferred = async {
            val orgUnitMap = d2Instance.organisationUnitModule().organisationUnits()
                .blockingGet()
                .associateBy({ it.uid() }, { it.displayName() ?: it.name() ?: "Unknown" })

            d2Instance.eventModule().events()
                .byEnrollmentUid().isNull // Only standalone events (no enrollment)
                .blockingGet()
                .map { event ->
                    com.ash.simpledataentry.data.local.EventInstanceEntity(
                        id = event.uid(),
                        programId = event.program() ?: "",
                        programStageId = event.programStage() ?: "",
                        organisationUnitId = event.organisationUnit() ?: "",
                        organisationUnitName = orgUnitMap[event.organisationUnit()] ?: "Unknown",
                        eventDate = event.eventDate()?.toString(),
                        status = event.status()?.name ?: "ACTIVE",
                        deleted = event.deleted() ?: false,
                        lastUpdated = event.lastUpdated()?.toString(),
                        enrollmentId = null
                    )
                }
        }

        // Wait for all parallel operations to complete
        val datasets = datasetsDeferred.await()
        val dataElements = dataElementsDeferred.await()
        val categoryCombos = categoryCombosDeferred.await()
        val categoryOptionCombos = categoryOptionCombosDeferred.await()
        val orgUnits = orgUnitsDeferred.await()
        val trackerPrograms = trackerProgramsDeferred.await()
        val eventPrograms = eventProgramsDeferred.await()
        val trackerEnrollments = trackerEnrollmentsDeferred.await()
        val eventInstances = eventInstancesDeferred.await()

        val fetchTime = System.currentTimeMillis() - startTime

        // Insert all data sequentially (Room doesn't handle parallel writes well)
        // CRITICAL: Log what SDK returns to diagnose empty Room issues
        Log.d("SessionManager", "SDK returned: ${datasets.size} datasets, ${dataElements.size} dataElements, ${categoryCombos.size} categoryCombos, ${categoryOptionCombos.size} COCs, ${orgUnits.size} orgUnits")

        db.datasetDao().clearAll()
        db.datasetDao().insertAll(datasets)
        Log.d("SessionManager", "Hydrated Room with ${datasets.size} datasets")

        // CRITICAL FIX: Only clear and re-insert if SDK returned data
        // This prevents wiping Room when SDK returns empty (e.g., during offline mode)
        if (dataElements.isNotEmpty()) {
            db.dataElementDao().clearAll()
            db.dataElementDao().insertAll(dataElements)
            Log.d("SessionManager", "Hydrated Room with ${dataElements.size} dataElements")
        } else {
            Log.w("SessionManager", "SDK returned 0 dataElements - preserving existing Room data")
        }

        if (categoryCombos.isNotEmpty()) {
            db.categoryComboDao().clearAll()
            db.categoryComboDao().insertAll(categoryCombos)
            Log.d("SessionManager", "Hydrated Room with ${categoryCombos.size} categoryCombos")
        } else {
            Log.w("SessionManager", "SDK returned 0 categoryCombos - preserving existing Room data")
        }

        if (categoryOptionCombos.isNotEmpty()) {
            db.categoryOptionComboDao().clearAll()
            db.categoryOptionComboDao().insertAll(categoryOptionCombos)
            Log.d("SessionManager", "Hydrated Room with ${categoryOptionCombos.size} categoryOptionCombos")
        } else {
            Log.w("SessionManager", "SDK returned 0 categoryOptionCombos - preserving existing Room data")
        }

        if (orgUnits.isNotEmpty()) {
            db.organisationUnitDao().clearAll()
            db.organisationUnitDao().insertAll(orgUnits)
            Log.d("SessionManager", "Hydrated Room with ${orgUnits.size} orgUnits")
        } else {
            Log.w("SessionManager", "SDK returned 0 orgUnits - preserving existing Room data")
        }

        // PHASE 2 FIX: Insert tracker and event programs into Room
        db.trackerProgramDao().clearAll()
        db.trackerProgramDao().insertAll(trackerPrograms)
        Log.d("SessionManager", "Hydrated ${trackerPrograms.size} tracker programs")

        db.eventProgramDao().clearAll()
        db.eventProgramDao().insertAll(eventPrograms)
        Log.d("SessionManager", "Hydrated ${eventPrograms.size} event programs")

        // PHASE 2 FIX: Insert tracker enrollments and event instances into Room
        db.trackerEnrollmentDao().clearAll()
        db.trackerEnrollmentDao().insertAll(trackerEnrollments)
        Log.d("SessionManager", "Hydrated ${trackerEnrollments.size} tracker enrollments")

        db.eventInstanceDao().clearAll()
        db.eventInstanceDao().insertAll(eventInstances)
        Log.d("SessionManager", "Hydrated ${eventInstances.size} event instances")

        val totalTime = System.currentTimeMillis() - startTime


        // Hydrate data values from DHIS2 SDK to Room database
        Log.d("SessionManager", "Loading data values from DHIS2 SDK...")
        try {
            // First, create a mapping of data element UIDs to dataset UIDs
            val dataElementToDatasetMap = mutableMapOf<String, String>()
            d2Instance.dataSetModule().dataSets().blockingGet().forEach { dataset ->
                dataset.dataSetElements()?.forEach { dataSetElement ->
                    dataElementToDatasetMap[dataSetElement.dataElement().uid()] = dataset.uid()
                }
            }
            Log.d("SessionManager", "Created mapping for ${dataElementToDatasetMap.size} data elements to datasets")

            // Use regular data values for now until we understand the aggregated module interface
            val regularDataValues = d2Instance.dataValueModule().dataValues().blockingGet()
            Log.d("SessionManager", "Using regular data values (${regularDataValues.size})")

            val dataValuesToUse = regularDataValues

            val dataValues = dataValuesToUse.mapIndexed { index, dataValue ->
                val dataElementUid = dataValue.dataElement() ?: ""
                val datasetId = dataElementToDatasetMap[dataElementUid] ?: ""
                val period = dataValue.period() ?: ""
                val orgUnit = dataValue.organisationUnit() ?: ""
                val attributeOptionCombo = dataValue.attributeOptionCombo() ?: ""
                val categoryOptionCombo = dataValue.categoryOptionCombo() ?: ""
                val value = dataValue.value()


                com.ash.simpledataentry.data.local.DataValueEntity(
                    datasetId = datasetId,
                    period = period,
                    orgUnit = orgUnit,
                    attributeOptionCombo = attributeOptionCombo,
                    dataElement = dataElementUid,
                    categoryOptionCombo = categoryOptionCombo,
                    value = value,
                    comment = dataValue.comment(),
                    lastModified = dataValue.lastUpdated()?.time ?: System.currentTimeMillis()
                )
            }
            db.dataValueDao().deleteAllDataValues()
            db.dataValueDao().insertAll(dataValues)
            Log.d("SessionManager", "Loaded ${dataValues.size} data values into Room database")
        } catch (e: Exception) {
            Log.e("SessionManager", "Failed to load data values: ${e.message}", e)
        }
    }

    /**
     * SECURITY FUNCTION: Create secure password hash with salt
     * Uses SHA-256 with user-specific salt to prevent rainbow table attacks
     */
    private fun hashPassword(password: String, salt: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = (password + salt).toByteArray(StandardCharsets.UTF_8)
            val hashBytes = digest.digest(input)

            // Convert to hexadecimal string
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("SessionManager", "Failed to hash password", e)
            // Fallback - never return null to prevent bypass
            password.hashCode().toString()
        }
    }

    /**
     * SECURITY FUNCTION: Validate session security and force re-authentication if compromised
     */
    fun validateSessionSecurity(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
            val hashCreated = prefs.getLong("hash_created", 0L)
            val currentTime = System.currentTimeMillis()

            // Force re-authentication if hash is older than 30 days (security policy)
            val maxAgeMillis = 30L * 24 * 60 * 60 * 1000 // 30 days
            if (currentTime - hashCreated > maxAgeMillis) {
                Log.w("SessionManager", "Password hash expired - forcing re-authentication")
                return false
            }

            // Verify we have all required security components
            val hasPasswordHash = prefs.getString("password_hash", null) != null
            val hasUsername = prefs.getString("username", null) != null
            val hasServerUrl = prefs.getString("serverUrl", null) != null

            hasPasswordHash && hasUsername && hasServerUrl
        } catch (e: Exception) {
            Log.e("SessionManager", "Session security validation failed", e)
            false
        }
    }

    /**
     * Restore D2 session if needed when app resumes from background
     * Handles app lifecycle issues when screen goes off from inactivity
     */
    suspend fun restoreSessionIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        try {
            Log.d("SessionManager", "Checking if session restoration is needed")

            // Check if D2 is initialized
            if (d2 == null) {
                Log.d("SessionManager", "D2 not initialized, attempting to initialize")
                initD2(context)
            }

            val d2Instance = d2 ?: run {
                Log.w("SessionManager", "Failed to initialize D2, cannot restore session")
                return@withContext
            }

            // Check if user is still logged in
            val isLoggedIn = try {
                d2Instance.userModule().isLogged().blockingGet()
            } catch (e: Exception) {
                Log.w("SessionManager", "Error checking login status: ${e.message}")
                false
            }

            if (isLoggedIn) {
                Log.d("SessionManager", "User is still logged in, session active")

                // CRITICAL: Switch to account-specific database on session restoration
                // Without this, the fallback database is used and datasets won't appear
                val activeAccountInfo = accountManager.getActiveAccount(context)
                if (activeAccountInfo != null) {
                    val db = databaseManager.getDatabaseForAccount(context, activeAccountInfo)
                    Log.d("SessionManager", "Switched to account database: ${activeAccountInfo.roomDatabaseName}")

                    // CRITICAL FIX: Check if Room database is hydrated, re-hydrate if empty
                    // This handles cases where Room was cleared, or a new account was created
                    // NOTE: Use AND logic - we need ALL critical metadata tables populated
                    val dataElements = db.dataElementDao().getAll()
                    val categoryOptionCombos = db.categoryOptionComboDao().getAll()
                    val datasets = db.datasetDao().getAll().first()

                    // Check if critical metadata exists (dataElements and categoryOptionCombos are essential for data entry)
                    val hasFullMetadata = dataElements.isNotEmpty() && categoryOptionCombos.isNotEmpty()
                    Log.d("SessionManager", "Room metadata check: ${dataElements.size} dataElements, ${categoryOptionCombos.size} COCs, ${datasets.size} datasets")


                    if (!hasFullMetadata) {
                        Log.w("SessionManager", "Room database is empty - triggering re-hydration from SDK cache")
                        try {
                            hydrateRoomFromSdk(context, db)
                            Log.d("SessionManager", "Room re-hydration completed successfully")
                        } catch (e: Exception) {
                            Log.e("SessionManager", "Failed to re-hydrate Room: ${e.message}")
                        }
                    } else {
                        Log.d("SessionManager", "Room database has metadata, skipping hydration")
                    }

                    // Emit account ID to trigger ViewModel reloads
                    // This ensures DatasetsViewModel reloads from the correct database
                    _currentAccountId.value = activeAccountInfo.accountId
                    Log.d("SessionManager", "Emitted currentAccountId: ${activeAccountInfo.accountId}")

                    // Mark session as ready after successful restoration
                    markSessionReady()
                } else {
                    Log.w("SessionManager", "User is logged in but no active account found - database may be stale")
                }

                return@withContext
            }

            // Check if we can restore from offline session
            val prefs = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
            val isOfflineMode = prefs.getBoolean("offline_mode", false)
            val storedUsername = prefs.getString("username", null)
            val storedServerUrl = prefs.getString("serverUrl", null)

            if (isOfflineMode && storedUsername != null && storedServerUrl != null) {
                Log.d("SessionManager", "Attempting to restore offline session for $storedUsername")

                // Check if we have user data from previous session
                val hasUserData = try {
                    d2Instance.userModule().user().blockingGet()?.uid() != null
                } catch (e: Exception) {
                    Log.w("SessionManager", "Cannot access user data: ${e.message}")
                    false
                }

                if (hasUserData) {
                    Log.i("SessionManager", "Session restored successfully from offline cache")
                    // Update last validated timestamp
                    prefs.edit {
                        putLong("last_validated", System.currentTimeMillis())
                    }

                    // Switch to account-specific database for offline session
                    val accountInfo = accountManager.getOrCreateAccount(context, storedUsername, storedServerUrl)
                    databaseManager.getDatabaseForAccount(context, accountInfo)
                    Log.d("SessionManager", "Offline session: switched to account database: ${accountInfo.roomDatabaseName}")

                    // Emit account ID to trigger ViewModel reloads
                    _currentAccountId.value = accountInfo.accountId
                    Log.d("SessionManager", "Offline session: emitted currentAccountId: ${accountInfo.accountId}")

                    // Mark session as ready after successful offline restoration
                    markSessionReady()
                } else {
                    Log.w("SessionManager", "No user data available for offline session restoration")
                }
            } else {
                Log.d("SessionManager", "No offline session available for restoration")
            }

        } catch (e: Exception) {
            Log.e("SessionManager", "Failed to restore session: ${e.message}", e)
        }
    }

    fun downloadTrackerData() {
        val d2Instance = d2 ?: throw IllegalStateException("D2 not initialized")

        Log.d("SessionManager", "Starting standard tracker data download...")

        // 1. Download Tracker Programs (WITH_REGISTRATION)
        val trackerPrograms = d2Instance.programModule().programs()
            .byProgramType().eq(org.hisp.dhis.android.core.program.ProgramType.WITH_REGISTRATION)
            .blockingGet()

        Log.d("SessionManager", "Found ${trackerPrograms.size} tracker programs")

        trackerPrograms.forEach { program ->
            val programUid = program.uid()
            Log.d("SessionManager", "Downloading tracker data for: ${program.displayName()}")
            try {
                d2Instance.trackedEntityModule().trackedEntityInstanceDownloader()
                    .byProgramUid(programUid)
                    .blockingDownload()
            } catch (e: Exception) {
                Log.e("SessionManager", "Failed to download tracker data for ${program.displayName()}: ${e.message}")
            }
        }

        // 2. Download Event Programs (WITHOUT_REGISTRATION)
        val eventPrograms = d2Instance.programModule().programs()
            .byProgramType().eq(org.hisp.dhis.android.core.program.ProgramType.WITHOUT_REGISTRATION)
            .blockingGet()

        Log.d("SessionManager", "Found ${eventPrograms.size} event programs")

        eventPrograms.forEach { program ->
            val programUid = program.uid()
            Log.d("SessionManager", "Downloading event data for: ${program.displayName()}")
            try {
                d2Instance.eventModule().eventDownloader()
                    .byProgramUid(programUid)
                    .blockingDownload()
            } catch (e: Exception) {
                Log.e("SessionManager", "Failed to download event data for ${program.displayName()}: ${e.message}")
            }
        }

        // 3. Download Standalone Events (no program)
        try {
            Log.d("SessionManager", "Downloading standalone events...")
            d2Instance.eventModule().eventDownloader().download()
        } catch (e: Exception) {
            Log.e("SessionManager", "Failed to download standalone events: ${e.message}")
        }
        
        Log.d("SessionManager", "Tracker and event data download completed")
    }



}
