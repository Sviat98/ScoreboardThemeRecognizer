package com.bashkevich.scoreboardthemerecognizer.screens.settings.generatetheme

import androidx.compose.runtime.Immutable
import com.bashkevich.scoreboardthemerecognizer.mvi.UiAction
import com.bashkevich.scoreboardthemerecognizer.mvi.UiEvent
import com.bashkevich.scoreboardthemerecognizer.mvi.UiState
import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import com.bashkevich.scoreboardthemerecognizer.model.theme.domain.ScoreboardTheme

@Immutable
data class GenerateThemeState(
    val selectedImageName: String = "",
    val generatedTheme: ScoreboardTheme? = null,
    val isGenerating: Boolean = false,
    val isSaving: Boolean = false,
    val action: GenerateThemeAction? = null
) : UiState {
    companion object {
        fun initial() = GenerateThemeState(
            selectedImageName = "",
            generatedTheme = null,
            isGenerating = false,
            isSaving = false,
            action = null
        )
    }
}

@Immutable
sealed class GenerateThemeUiEvent : UiEvent {
    data class SelectImage(val image: ImageFile) : GenerateThemeUiEvent()
    data object ClearImage : GenerateThemeUiEvent()
    data object Generate : GenerateThemeUiEvent()
    data object AddTheme : GenerateThemeUiEvent()
}

@Immutable
sealed class GenerateThemeAction : UiAction {
    data class ShowError(val message: String) : GenerateThemeAction()
    data object ShowUnauthorizedActionError : GenerateThemeAction()
    data object ThemeSaved : GenerateThemeAction()
}
