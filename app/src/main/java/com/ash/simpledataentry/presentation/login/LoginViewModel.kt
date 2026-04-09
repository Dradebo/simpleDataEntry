package com.ash.simpledataentry.presentation.login

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ash.simpledataentry.domain.useCase.LoginUseCase
import com.ash.simpledataentry.data.local.CachedUrlEntity
import com.ash.simpledataentry.data.repositoryImpl.LoginUrlCacheRepository
import com.ash.simpledataentry.data.repositoryImpl.SavedAccountRepository
import com.ash.simpledataentry.data.repositoryImpl.AuthRepositoryImpl
import com.ash.simpledataentry.presentation.core.NavigationProgress
import com.ash.simpledataentry.presentation.core.UiState
import com.ash.simpledataentry.presentation.core.LoadingOperation
import com.ash.simpledataentry.presentation.core.BackgroundOperation
import com.ash.simpledataentry.util.toUiError
import com.ash.simpledataentry.domain.model.SavedAccount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Pure data model representing login-related business data.
 * Excludes UI state concerns (isLoading, error) which are handled by [UiState].
 *
 * @property isLoggedIn Whether user is currently logged in
 * @property showSplash Whether splash screen should be shown
 * @property cachedUrls List of previously used server URLs
 * @property urlSuggestions Current URL suggestions for autocomplete
 * @property savedAccounts List of saved user accounts for quick login
 * @property showAccountSelection Whether account selection dialog is visible
 * @property saveAccountOffered Whether user has been offered to save current credentials
 * @property pendingCredentials Temporarily stored credentials (serverUrl, username, password) for account save flow
 */
data class LoginData(
    val isLoggedIn: Boolean = false,
    val showSplash: Boolean = false,
    val cachedUrls: List<CachedUrlEntity> = emptyList(),
    val urlSuggestions: List<CachedUrlEntity> = emptyList(),
    val savedAccounts: List<SavedAccount> = emptyList(),
    val showAccountSelection: Boolean = false,
    val saveAccountOffered: Boolean = false,
    val pendingCredentials: Triple<String, String, String>? = null
)


/**
 * ViewModel managing login functionality with UiState pattern for consistent loading/error handling.
 *
 * ## State Management
 * - Uses [uiState] with [UiState] pattern for type-safe state management
 * - Loading states use [LoadingOperation.Navigation] with [NavigationProgress] for detailed progress tracking
 *
 * ## Key Features
 * - Online/offline login with progress tracking
 * - Saved account management with encrypted password storage
 * - URL caching and suggestions
 * - Multi-step account save flow with confirmation
 *
 * ## Navigation Progress
 * Login operations show detailed progress phases:
 * - "Authenticating..." - Server authentication
 * - "Loading metadata..." - DHIS2 metadata download
 * - "Syncing data..." - Initial data synchronization
 *
 * @property loginUseCase Use case handling core login logic
 * @property urlCacheRepository Repository for cached server URLs
 * @property savedAccountRepository Repository for saved accounts
 * @property authRepository Repository for authentication operations with progress callbacks
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginUseCase: LoginUseCase,
    private val urlCacheRepository: LoginUrlCacheRepository,
    private val savedAccountRepository: SavedAccountRepository,
    private val authRepository: AuthRepositoryImpl,
    private val sessionManager: com.ash.simpledataentry.data.SessionManager
) : ViewModel() {

    /**
     * Primary state flow using UiState pattern.
     * - [UiState.Success] - Contains [LoginData] with current business state
     * - [UiState.Loading] - Uses [LoadingOperation.Navigation] with [NavigationProgress] for detailed login phases
     * - [UiState.Error] - Contains error with previous data preserved
     */
    private val _uiState = MutableStateFlow<UiState<LoginData>>(
        UiState.Success(LoginData())
    )
    val uiState: StateFlow<UiState<LoginData>> = _uiState.asStateFlow()

    /**
     * Helper to extract current [LoginData] from any [UiState].
     * Used to preserve data across state transitions.
     */
    private fun getCurrentData(): LoginData {
        return when (val current = _uiState.value) {
            is UiState.Success -> current.data
            is UiState.Error -> current.previousData ?: LoginData()
            is UiState.Loading -> LoginData()
        }
    }

    init {
        loadCachedUrls()
        loadSavedAccounts()
        checkExistingSession()
    }

    /**
     * Check if there's an existing active session and auto-redirect if so.
     * This handles the case where the app restarts with an active D2 session.
     */
    private fun checkExistingSession() {
        viewModelScope.launch {
            // Wait for D2 to initialize (MainActivity initializes it in onCreate)
            // D2 initialization typically takes 2-4 seconds
            kotlinx.coroutines.delay(4000)

            // Check if D2 has an active session
            val hasActiveSession = try {
                sessionManager.isSessionActive()
            } catch (e: Exception) {
                android.util.Log.w("LoginViewModel", "Error checking session: ${e.message}")
                false
            }

            if (hasActiveSession) {
                android.util.Log.d("LoginViewModel", "Active session detected - auto-navigating to datasets")
                // Set isLoggedIn to trigger navigation to datasets
                val currentData = getCurrentData()
                _uiState.value = UiState.Success(currentData.copy(isLoggedIn = true))
            }
        }
    }

    // ===== LOGIN OPERATIONS =====
    /**
     * Performs basic login without detailed progress tracking.
     * Shows [LoadingOperation.Initial] state during authentication.
     * Offers to save account after successful login if not already saved.
     *
     * @param serverUrl DHIS2 server URL
     * @param username User's username
     * @param password User's password
     * @param context Android context for login operations
     */
    fun login(serverUrl: String, username: String, password: String, context: Context) {
        viewModelScope.launch {
            try {
                // Show splash screen immediately with loading state
                val currentData = getCurrentData()
                val loadingData = currentData.copy(showSplash = true)
                _uiState.value = UiState.Loading(LoadingOperation.Initial)

                val loginResult = loginUseCase(username, password, serverUrl, context)
                if (loginResult) {
                    // Cache the successful URL
                    urlCacheRepository.addOrUpdateUrl(serverUrl)

                    // Check if we should offer to save the account
                    val existingAccount = savedAccountRepository.getAccountByCredentials(serverUrl, username)
                    android.util.Log.d("LoginDebug", "Existing account found: ${existingAccount != null}, saveAccountOffered will be: ${existingAccount == null}")

                    if (existingAccount != null) {
                        // Update last used timestamp for existing account
                        savedAccountRepository.updateLastUsed(existingAccount.id)
                        savedAccountRepository.switchToAccount(existingAccount.id)
                    }

                    val successData = loadingData.copy(
                        isLoggedIn = true,
                        showSplash = true,
                        saveAccountOffered = existingAccount == null,
                        pendingCredentials = if (existingAccount == null) Triple(serverUrl, username, password) else null
                    )
                    _uiState.value = UiState.Success(successData)
                } else {
                    val failureData = loadingData.copy(showSplash = false)
                    val uiError = Exception("Invalid credentials or server error").toUiError()
                    _uiState.value = UiState.Error(uiError, failureData)
                }
            } catch (e: Exception) {
                val failureData = getCurrentData().copy(showSplash = false)
                val uiError = e.toUiError()
                _uiState.value = UiState.Error(uiError, failureData)
            }
        }
    }

    /**
     * Performs login with detailed progress tracking through [NavigationProgress].
     * Shows granular progress phases: "Authenticating...", "Loading metadata...", "Syncing data...".
     * Uses [LoadingOperation.Navigation] to track progress percentage and phase details.
     * Offers to save account after successful login if not already saved.
     *
     * @param serverUrl DHIS2 server URL
     * @param username User's username
     * @param password User's password
     * @param context Android context for login operations
     */
    fun loginWithProgress(serverUrl: String, username: String, password: String, context: Context) {
        viewModelScope.launch {
            try {
                // Show splash screen immediately with NavigationProgress
                val currentData = getCurrentData()
                val loadingData = currentData.copy(showSplash = true)
                val initialProgress = NavigationProgress.initial()
                _uiState.value = UiState.Loading(LoadingOperation.Navigation(initialProgress))

                // Check if offline login is possible first
                val canLoginOffline = authRepository.canLoginOffline(context, username, serverUrl)
                android.util.Log.d("LoginViewModel", "Can login offline: $canLoginOffline")

                val loginResult = if (canLoginOffline) {
                    // Try offline login first
                    android.util.Log.d("LoginViewModel", "Attempting offline login for $username")
                    val offlineProgress = NavigationProgress.initial().copy(
                        phaseTitle = "Offline Login",
                        phaseDetail = "Accessing saved session..."
                    )
                    _uiState.value = UiState.Loading(LoadingOperation.Navigation(offlineProgress))

                    val offlineResult = authRepository.attemptOfflineLogin(
                        username = username,
                        password = password,
                        serverUrl = serverUrl,
                        context = context
                    )

                    if (offlineResult) {
                        true
                    } else {
                        // Offline failed; fall back to online login
                        android.util.Log.d("LoginViewModel", "Offline login failed, attempting online login")
                        authRepository.loginWithProgress(
                            username = username,
                            password = password,
                            serverUrl = serverUrl,
                            context = context
                        ) { progress ->
                            _uiState.value = UiState.Loading(LoadingOperation.Navigation(progress))
                        }
                    }
                } else {
                    // Fall back to online login with progress tracking
                    android.util.Log.d("LoginViewModel", "Offline login not possible, attempting online login")
                    authRepository.loginWithProgress(
                        username = username,
                        password = password,
                        serverUrl = serverUrl,
                        context = context
                    ) { progress ->
                        _uiState.value = UiState.Loading(LoadingOperation.Navigation(progress))
                    }
                }

                if (loginResult) {
                    // Cache the successful URL
                    urlCacheRepository.addOrUpdateUrl(serverUrl)

                    // Check if we should offer to save the account
                    val existingAccount = savedAccountRepository.getAccountByCredentials(serverUrl, username)
                    android.util.Log.d("LoginDebug", "Enhanced login - Existing account found: ${existingAccount != null}")

                    if (existingAccount != null) {
                        // Update last used timestamp for existing account
                        savedAccountRepository.updateLastUsed(existingAccount.id)
                        savedAccountRepository.switchToAccount(existingAccount.id)
                    }

                    val successData = loadingData.copy(
                        isLoggedIn = true,
                        showSplash = true,
                        saveAccountOffered = existingAccount == null,
                        pendingCredentials = if (existingAccount == null) Triple(serverUrl, username, password) else null
                    )
                    _uiState.value = UiState.Success(successData)
                } else {
                    val failureData = loadingData.copy(showSplash = false)
                    val errorMessage = if (canLoginOffline) {
                        "Offline login failed and online login was unsuccessful"
                    } else {
                        "Login failed: Invalid credentials or server error"
                    }
                    val uiError = Exception(errorMessage).toUiError()
                    _uiState.value = UiState.Error(uiError, failureData)
                }
            } catch (e: Exception) {
                val failureData = getCurrentData().copy(showSplash = false)
                val uiError = e.toUiError()
                _uiState.value = UiState.Error(uiError, failureData)
            }
        }
    }

    fun hideSplash() {
        val currentData = getCurrentData()
        val newData = currentData.copy(showSplash = false)
        _uiState.value = UiState.Success(newData)
    }

    fun clearError() {
        val currentData = getCurrentData()
        _uiState.value = UiState.Success(currentData) // Clear error by returning to Success
    }

    private fun loadCachedUrls() {
        viewModelScope.launch {
            try {
                val cachedUrls = urlCacheRepository.getCachedUrls()
                val currentData = getCurrentData()
                val newData = currentData.copy(cachedUrls = cachedUrls)
                _uiState.value = UiState.Success(newData)
            } catch (e: Exception) {
                // Silently handle error - cached URLs are not critical for login
            }
        }
    }

    fun updateUrlSuggestions(query: String) {
        viewModelScope.launch {
            try {
                val suggestions = urlCacheRepository.getSuggestedUrls(query)
                val currentData = getCurrentData()
                val newData = currentData.copy(urlSuggestions = suggestions)
                _uiState.value = UiState.Success(newData)
            } catch (e: Exception) {
                // Silently handle error - suggestions are not critical
                val currentData = getCurrentData()
                val newData = currentData.copy(urlSuggestions = emptyList())
                _uiState.value = UiState.Success(newData)
            }
        }
    }

    fun clearUrlSuggestions() {
        val currentData = getCurrentData()
        val newData = currentData.copy(urlSuggestions = emptyList())
        _uiState.value = UiState.Success(newData)
    }

    fun removeUrl(url: String) {
        viewModelScope.launch {
            try {
                _uiState.value = UiState.Loading(LoadingOperation.Initial)

                urlCacheRepository.removeUrl(url)
                loadCachedUrls() // Refresh the cached URLs list - this will set Success state
            } catch (e: Exception) {
                val uiError = e.toUiError()
                _uiState.value = UiState.Error(uiError, getCurrentData())
            }
        }
    }

    private fun loadSavedAccounts() {
        viewModelScope.launch {
            try {
                val accounts = savedAccountRepository.getAllSavedAccounts()
                val currentData = getCurrentData()
                val newData = currentData.copy(
                    savedAccounts = accounts,
                    showAccountSelection = accounts.isNotEmpty()
                )
                _uiState.value = UiState.Success(newData)
            } catch (e: Exception) {
                // Silently handle error - saved accounts are not critical for login
            }
        }
    }

    fun saveAccount(displayName: String, serverUrl: String, username: String, password: String) {
        viewModelScope.launch {
            try {
                _uiState.value = UiState.Loading(LoadingOperation.Initial)

                val result = savedAccountRepository.saveAccount(displayName, serverUrl, username, password)
                if (result.isSuccess) {
                    loadSavedAccounts() // Refresh the accounts list - this will set Success state

                    // Update to clear the save offer
                    val currentData = getCurrentData()
                    val newData = currentData.copy(
                        saveAccountOffered = false,
                        pendingCredentials = null
                    )
                    _uiState.value = UiState.Success(newData)
                } else {
                    val errorMessage = "Failed to save account: ${result.exceptionOrNull()?.message}"
                    val currentData = getCurrentData()
                    val newData = currentData.copy(
                        saveAccountOffered = false,
                        pendingCredentials = null
                    )
                    val uiError = (result.exceptionOrNull() ?: Exception(errorMessage)).toUiError()
                    _uiState.value = UiState.Error(uiError, newData)
                }
            } catch (e: Exception) {
                val currentData = getCurrentData()
                val newData = currentData.copy(
                    saveAccountOffered = false,
                    pendingCredentials = null
                )
                val uiError = e.toUiError()
                _uiState.value = UiState.Error(uiError, newData)
            }
        }
    }

    /**
     * Logs in using a saved account with encrypted credentials.
     * Supports both offline login (using cached session) and online login with progress tracking.
     * Automatically decrypts stored password and attempts offline login first if possible.
     *
     * @param account Saved account with encrypted credentials
     * @param context Android context for login operations
     */
    fun loginWithSavedAccount(account: SavedAccount, context: Context) {
        viewModelScope.launch {
            try {
                val currentData = getCurrentData()
                val loadingData = currentData.copy(showSplash = true)
                _uiState.value = UiState.Loading(LoadingOperation.Initial)

                val decryptedPassword = savedAccountRepository.getDecryptedPassword(account.id)
                if (decryptedPassword == null) {
                    val failureData = loadingData.copy(showSplash = false)
                    val uiError = Exception("Failed to decrypt account password").toUiError()
                    _uiState.value = UiState.Error(uiError, failureData)
                    return@launch
                }

                // Check if offline login is possible first
                val canLoginOffline = authRepository.canLoginOffline(context, account.username, account.serverUrl)
                android.util.Log.d("LoginViewModel", "Can login offline: $canLoginOffline")

                val loginResult = if (canLoginOffline) {
                    // Try offline login first
                    android.util.Log.d("LoginViewModel", "Attempting offline login for ${account.username}")
                    val offlineProgress = NavigationProgress.initial().copy(
                        phaseTitle = "Offline Login",
                        phaseDetail = "Accessing saved session..."
                    )
                    _uiState.value = UiState.Loading(LoadingOperation.Navigation(offlineProgress))

                    val offlineResult = authRepository.attemptOfflineLogin(
                        username = account.username,
                        password = decryptedPassword,
                        serverUrl = account.serverUrl,
                        context = context
                    )

                    if (offlineResult) {
                        true
                    } else {
                        // Offline failed; fall back to online login
                        android.util.Log.d("LoginViewModel", "Offline login failed, attempting online login")
                        val initialProgress = NavigationProgress.initial()
                        _uiState.value = UiState.Loading(LoadingOperation.Navigation(initialProgress))

                        authRepository.loginWithProgress(
                            username = account.username,
                            password = decryptedPassword,
                            serverUrl = account.serverUrl,
                            context = context
                        ) { progress ->
                            _uiState.value = UiState.Loading(LoadingOperation.Navigation(progress))
                        }
                    }
                } else {
                    // Fall back to online login with progress tracking
                    android.util.Log.d("LoginViewModel", "Offline login not possible, attempting online login")
                    val initialProgress = NavigationProgress.initial()
                    _uiState.value = UiState.Loading(LoadingOperation.Navigation(initialProgress))

                    authRepository.loginWithProgress(
                        username = account.username,
                        password = decryptedPassword,
                        serverUrl = account.serverUrl,
                        context = context
                    ) { progress ->
                        _uiState.value = UiState.Loading(LoadingOperation.Navigation(progress))
                    }
                }

                if (loginResult) {
                    // Update last used and switch to this account
                    savedAccountRepository.switchToAccount(account.id)

                    val successData = loadingData.copy(
                        isLoggedIn = true,
                        showSplash = true
                    )
                    _uiState.value = UiState.Success(successData)
                } else {
                    val failureData = loadingData.copy(showSplash = false)
                    val errorMessage = if (canLoginOffline) {
                        "Offline login failed and online login was unsuccessful"
                    } else {
                        "Login failed: Invalid credentials or server error"
                    }
                    val uiError = Exception(errorMessage).toUiError()
                    _uiState.value = UiState.Error(uiError, failureData)
                }
            } catch (e: Exception) {
                val failureData = getCurrentData().copy(showSplash = false)
                val uiError = e.toUiError()
                _uiState.value = UiState.Error(uiError, failureData)
            }
        }
    }

    fun dismissSaveAccountOffer() {
        val currentData = getCurrentData()
        val newData = currentData.copy(
            saveAccountOffered = false,
            pendingCredentials = null
        )
        _uiState.value = UiState.Success(newData)
    }

    fun toggleAccountSelection() {
        val currentData = getCurrentData()
        val currentShow = currentData.showAccountSelection
        val newData = currentData.copy(showAccountSelection = !currentShow)
        _uiState.value = UiState.Success(newData)
    }

    fun selectAccount(account: SavedAccount): Triple<String, String, String> {
        // Return serverUrl, username, displayName for pre-filling form
        return Triple(account.serverUrl, account.username, account.displayName)
    }

    fun canSaveNewAccount(): Boolean {
        val currentData = getCurrentData()
        return currentData.savedAccounts.size < SavedAccountRepository.MAX_SAVED_ACCOUNTS
    }
}
