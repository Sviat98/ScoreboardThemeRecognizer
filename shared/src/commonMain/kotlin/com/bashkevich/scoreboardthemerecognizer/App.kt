package com.bashkevich.scoreboardthemerecognizer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.bashkevich.scoreboardthemerecognizer.screens.settings.generatetheme.GenerateThemeScreen
import com.bashkevich.scoreboardthemerecognizer.screens.settings.generatetheme.GenerateThemeViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Back-navigation handle for screens. The original app used a NavController
 * (androidx.navigation); this desktop-only port has a single screen, so the
 * handle defaults to a no-op and is here for API parity / future wiring.
 */
val LocalOnBack = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
@Preview
fun App() {
    MaterialTheme {
        GenerateThemeScreen(
            viewModel = koinViewModel<GenerateThemeViewModel>(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
