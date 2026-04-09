package com.ash.simpledataentry.presentation.core

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Utility for tracking keyboard (IME) state in Compose
 */
@Composable
fun rememberImeState(): ImeState {
    val imeState = remember { mutableStateOf(ImeState(false)) }
    val view = LocalView.current

    DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val isKeyboardOpen = ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
            imeState.value = ImeState(isKeyboardOpen)
        }

        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose {
            view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    return imeState.value
}

/**
 * Data class representing the current IME (keyboard) state
 */
data class ImeState(val isOpen: Boolean)