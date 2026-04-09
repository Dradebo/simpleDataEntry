package com.ash.simpledataentry.presentation.login

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import com.ash.simpledataentry.R
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.*
import com.ash.simpledataentry.presentation.core.rememberImeState
import com.ash.simpledataentry.presentation.core.AppDesignSystem
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ash.simpledataentry.navigation.Screen.DatasetsScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.ash.simpledataentry.data.local.AppDatabase
import androidx.room.Room
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import com.ash.simpledataentry.presentation.core.Dhis2PulsingLoader


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LoginScreen(
    navController: NavController,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // IME and focus management for enhanced UX
    val usernameBringIntoViewRequester = remember { BringIntoViewRequester() }
    val passwordBringIntoViewRequester = remember { BringIntoViewRequester() }
    var usernameFocused by remember { mutableStateOf(false) }
    var passwordFocused by remember { mutableStateOf(false) }

    LaunchedEffect(state.isLoggedIn, state.saveAccountOffered) {
        if (state.isLoggedIn && !state.saveAccountOffered) {
            navController.navigate("datasets") {
                popUpTo("login") { inclusive = true }
            }
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { error ->
            coroutineScope.launch {
                snackbarHostState.showSnackbar(error)
                viewModel.clearError()
            }
        }
    }

    if (state.showSplash) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // DHIS2-style pulsing loading animation
                Dhis2PulsingLoader()

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Loading your data...",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    } else {
        // Local state for input fields, preserved across configuration changes
        var serverUrl by rememberSaveable { mutableStateOf("https://") }
        var username by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        var showUrlDropdown by remember { mutableStateOf(false) }
        var showAccountDropdown by remember { mutableStateOf(false) }
        var passwordVisible by remember { mutableStateOf(false) }

        val context = LocalContext.current

        // Enhanced keyboard handling
        val keyboardController = LocalSoftwareKeyboardController.current
        val imeState = rememberImeState()
        val scrollState = rememberScrollState()

        // Auto-scroll when keyboard appears
        LaunchedEffect(imeState.isOpen) {
            if (imeState.isOpen) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(AppDesignSystem.Spacing.medium)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppDesignSystem.Spacing.medium)
        ) {
            // Logo - responsive to keyboard state
            Icon(
                painter = painterResource(id = R.drawable.dhis2_official_logo),
                contentDescription = "DHIS2 Logo",
                modifier = Modifier
                    .height(if (imeState.isOpen) 120.dp else 200.dp)
                    .animateContentSize(),
                tint = Color.Unspecified
            )

            // Flexible spacer that shrinks when keyboard is open
            if (!imeState.isOpen) {
                Spacer(modifier = Modifier.height(AppDesignSystem.Spacing.large))
            }

            // Account Selection (simplified for now)
            if (state.savedAccounts.isNotEmpty()) {
                // TODO: Implement enhanced account dropdown
            }

            // Server URL Field
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server URL") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Server URL",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .bringIntoViewRequester(usernameBringIntoViewRequester)
                    .onFocusChanged { focusState ->
                        usernameFocused = focusState.isFocused
                    },
                enabled = !state.isLoading,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Username",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .bringIntoViewRequester(passwordBringIntoViewRequester)
                    .onFocusChanged { focusState ->
                        passwordFocused = focusState.isFocused
                    },
                enabled = !state.isLoading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Password",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible }
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                        )
                    }
                }
            )

            // Forgot Password Link
            TextButton(
                onClick = { /* TODO: Implement forgot password functionality */ },
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(
                    text = "Forgot Password",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // Login Button
            Button(
                onClick = {
                    val db = Room.databaseBuilder(
                        context,
                        AppDatabase::class.java,
                        "simple_data_entry_db"
                    )
                        // TODO: Replace fallbackToDestructiveMigration() with a real migration before production release!
                        .fallbackToDestructiveMigration()
                        .build()
                    viewModel.login(serverUrl, username, password, context, db)
                },
                enabled = !state.isLoading &&
                        serverUrl.isNotBlank() &&
                        username.isNotBlank() &&
                        password.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = "Log in",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }


        // Show error message as a Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .padding(16.dp),
            snackbar = { data ->
                Snackbar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = Color.White
                ) {
                    Text(
                        data.visuals.message,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        )

        // Save Account Dialog (moved outside main login form)
        if (state.saveAccountOffered && state.pendingCredentials != null) {
            android.util.Log.d("LoginDebug", "About to show save account dialog")

            var displayName by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = {
                    android.util.Log.d("LoginDebug", "Dialog dismissed")
                    viewModel.dismissSaveAccountOffer()
                },
                title = { Text("Save Account") },
                text = {
                    Column {
                        Text("Would you like to save these login credentials for quick access?")
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text("Display Name") },
                            placeholder = { Text("e.g., Work Account, Personal") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (displayName.isNotBlank()) {
                                val (serverUrl, username, password) = state.pendingCredentials!!
                                android.util.Log.d("LoginDebug", "Saving account: $displayName")
                                viewModel.saveAccount(displayName, serverUrl, username, password)
                            }
                        },
                        enabled = displayName.isNotBlank()
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            android.util.Log.d("LoginDebug", "Skip clicked")
                            viewModel.dismissSaveAccountOffer()
                        }
                    ) {
                        Text("Skip")
                    }
                }
            )
        }
    }


    @Composable
    fun Dhis2PulsingLoader() {
        // Three pulsing dots animation inspired by DHIS2 Android Capture App
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { index ->
                val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 0.8f,
                    targetValue = 1.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = 800,
                            delayMillis = index * 200, // Stagger the animation
                            easing = EaseInOut
                        ),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "scaleAnimation"
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
                    label = "alphaAnimation"
                )

                Surface(
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary
                ) {}
            }
        }
    }

    // Helper function for date formatting
    fun formatLastUsed(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 24 * 60 * 60 * 1000 -> "Today"
            diff < 7 * 24 * 60 * 60 * 1000 -> "${diff / (24 * 60 * 60 * 1000)} days ago"
            else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
        }
    }

    // Enhanced components for better UX
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun EnhancedAccountDropdown(
        accounts: List<com.ash.simpledataentry.domain.model.SavedAccount>,
        onAccountSelected: (com.ash.simpledataentry.domain.model.SavedAccount) -> Unit,
        enabled: Boolean = true
    ) {
        var showDropdown by remember { mutableStateOf(false) }

        Column {
            OutlinedTextField(
                value = "",
                onValueChange = { },
                label = { Text("Select Saved Account") },
                placeholder = { Text("Choose an account or enter manually") },
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                trailingIcon = {
                    IconButton(onClick = { showDropdown = !showDropdown }) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Show saved accounts"
                        )
                    }
                }
            )

            DropdownMenu(
                expanded = showDropdown,
                onDismissRequest = { showDropdown = false },
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                accounts.forEach { account ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = account.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${account.username}@${account.serverUrl}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        onClick = {
                            onAccountSelected(account)
                            showDropdown = false
                        }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun EnhancedServerUrlField(
        value: String,
        onValueChange: (String) -> Unit,
        cachedUrls: List<com.ash.simpledataentry.data.local.CachedUrlEntity>,
        onUrlSelected: (String) -> Unit,
        onUrlRemoved: (String) -> Unit,
        enabled: Boolean = true
    ) {
        var showDropdown by remember { mutableStateOf(false) }

        Column {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Server URL") },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Server URL",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (cachedUrls.isNotEmpty()) {
                        IconButton(onClick = { showDropdown = !showDropdown }) {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Show cached URLs"
                            )
                        }
                    }
                }
            )

            // Enhanced dropdown matching saved accounts style
            DropdownMenu(
                expanded = showDropdown,
                onDismissRequest = { showDropdown = false },
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                cachedUrls.forEach { cachedUrl ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cachedUrl.url,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Last used: ${formatLastUsed(cachedUrl.lastUsed)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        onClick = {
                            onUrlSelected(cachedUrl.url)
                            showDropdown = false
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    onUrlRemoved(cachedUrl.url)
                                    showDropdown = false
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Remove URL",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(AppDesignSystem.IconSize.small)
                                )
                            }
                        }
                    )
                }
            }
        }
    }

}