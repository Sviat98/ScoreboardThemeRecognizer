package com.bashkevich.scoreboardthemerecognizer

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.bashkevich.scoreboardthemerecognizer.di.initKoin

fun main() {
    initKoin()

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "ScoreboardThemeRecognizer",
        ) {
            App()
        }
    }
}
