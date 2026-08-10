package com.bashkevich.scoreboardthemerecognizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bashkevich.scoreboardthemerecognizer.screens.debug.BoxThemeDebugScreen
import com.bashkevich.scoreboardthemerecognizer.screens.debug.ElementsDebugScreen
import com.bashkevich.scoreboardthemerecognizer.screens.debug.NoiseCleaningDebugScreen
import com.bashkevich.scoreboardthemerecognizer.screens.settings.generatetheme.GenerateThemeScreen
import com.bashkevich.scoreboardthemerecognizer.screens.settings.generatetheme.GenerateThemeViewModel
import org.koin.compose.viewmodel.koinViewModel

private enum class DebugScreen { ELEMENTS, BOXES, NOISE }

/**
 * Back-navigation handle for screens. The original app used a NavController
 * (androidx.navigation); this desktop-only port has a single screen, so the
 * handle defaults to a no-op and is here for API parity / future wiring.
 */
val LocalOnBack = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
@Preview
fun App() {
    var debug by remember { mutableStateOf<DebugScreen?>(null) }
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            when (val s = debug) {
                null -> GenerateThemeScreen(
                    viewModel = koinViewModel<GenerateThemeViewModel>(),
                    modifier = Modifier.fillMaxSize(),
                )
                DebugScreen.ELEMENTS -> ElementsDebugScreen(
                    onBack = { debug = null },
                    modifier = Modifier.fillMaxSize(),
                )
                DebugScreen.BOXES -> BoxThemeDebugScreen(
                    onBack = { debug = null },
                    modifier = Modifier.fillMaxSize(),
                )
                DebugScreen.NOISE -> NoiseCleaningDebugScreen(
                    onBack = { debug = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (debug == null) {
                Column(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { debug = DebugScreen.ELEMENTS }) { Text("debug: opencv elements") }
                    TextButton(onClick = { debug = DebugScreen.BOXES }) { Text("debug: theme from boxes") }
                    TextButton(onClick = { debug = DebugScreen.NOISE }) { Text("debug: noise cleaning") }
                }
            }
        }
    }
}
