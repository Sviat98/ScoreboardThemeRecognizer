package com.bashkevich.scoreboardthemerecognizer.screens.settings.generatetheme

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.viewModelScope
import com.bashkevich.scoreboardthemerecognizer.core.remote.NetworkException
import com.bashkevich.scoreboardthemerecognizer.core.remote.UnauthorizedActionException
import com.bashkevich.scoreboardthemerecognizer.core.remote.doOnError
import com.bashkevich.scoreboardthemerecognizer.core.remote.doOnSuccess
import com.bashkevich.scoreboardthemerecognizer.model.file.domain.EMPTY_IMAGE_FILE
import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import com.bashkevich.scoreboardthemerecognizer.model.theme.domain.toScoreboardTheme
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeBody
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeContent
import com.bashkevich.scoreboardthemerecognizer.model.theme.repository.ThemeRepository
import com.bashkevich.scoreboardthemerecognizer.mvi.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import scoreboardthemerecognizer.shared.generated.resources.Res
import scoreboardthemerecognizer.shared.generated.resources.check_internet_connection
import scoreboardthemerecognizer.shared.generated.resources.theme_generate_error
import scoreboardthemerecognizer.shared.generated.resources.theme_image_format_error
import scoreboardthemerecognizer.shared.generated.resources.theme_save_error

class GenerateThemeViewModel(
    private val themeRepository: ThemeRepository,
) : BaseViewModel<GenerateThemeState, GenerateThemeUiEvent, GenerateThemeAction>() {

    val themeNameState = TextFieldState()

    private val _selectedImage = MutableStateFlow(EMPTY_IMAGE_FILE)
    private val _generatedContent = MutableStateFlow<ThemeContent?>(null)
    private val _isGenerating = MutableStateFlow(false)
    private val _isSaving = MutableStateFlow(false)

    override val state: StateFlow<GenerateThemeState> = combine(
        _selectedImage,
        _generatedContent,
        _isGenerating,
        _isSaving,
        _action
    ) { image, content, isGenerating, isSaving, action ->
        GenerateThemeState(
            selectedImageName = image.name,
            generatedTheme = content?.toScoreboardTheme(),
            isGenerating = isGenerating,
            isSaving = isSaving,
            action = action
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        GenerateThemeState.initial()
    )

    fun onEvent(uiEvent: GenerateThemeUiEvent) {
        when (uiEvent) {
            is GenerateThemeUiEvent.SelectImage -> {
                if (!isSupportedImage(uiEvent.image.name)) {
                    viewModelScope.launch {
                        sendAction(
                            GenerateThemeAction.ShowError(getString(Res.string.theme_image_format_error))
                        )
                    }
                    return
                }
                _selectedImage.value = uiEvent.image
                _generatedContent.value = null
            }

            is GenerateThemeUiEvent.ClearImage -> {
                _selectedImage.value = EMPTY_IMAGE_FILE
                _generatedContent.value = null
            }

            is GenerateThemeUiEvent.Generate -> generate()

            is GenerateThemeUiEvent.AddTheme -> addTheme()
        }
    }

    private fun generate() {
        val image = _selectedImage.value
        if (image.name.isBlank()) return
        viewModelScope.launch {
            _isGenerating.value = true
            themeRepository.generateThemeFromImage(image)
                .doOnSuccess { content ->
                    _generatedContent.value = content
                    _isGenerating.value = false
                }
                .doOnError {
                    _isGenerating.value = false
                    handleError(it, getString(Res.string.theme_generate_error))
                }
        }
    }

    private fun addTheme() {
        val content = _generatedContent.value ?: return
        val name = themeNameState.text.trim().toString()
        if (name.isBlank()) return
        viewModelScope.launch {
            _isSaving.value = true
            themeRepository.createTheme(ThemeBody(name = name, content = content))
                .doOnSuccess {
                    _isSaving.value = false
                    sendAction(GenerateThemeAction.ThemeSaved)
                }
                .doOnError {
                    _isSaving.value = false
                    handleError(it, getString(Res.string.theme_save_error))
                }
        }
    }

    private fun isSupportedImage(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
    }

    private suspend fun handleError(e: Throwable, defaultMessage: String) {
        when (e) {
            is NetworkException ->
                sendAction(GenerateThemeAction.ShowError(getString(Res.string.check_internet_connection)))

            is UnauthorizedActionException ->
                sendAction(GenerateThemeAction.ShowUnauthorizedActionError)

            else ->
                sendAction(GenerateThemeAction.ShowError(e.message ?: defaultMessage))
        }
    }
}
