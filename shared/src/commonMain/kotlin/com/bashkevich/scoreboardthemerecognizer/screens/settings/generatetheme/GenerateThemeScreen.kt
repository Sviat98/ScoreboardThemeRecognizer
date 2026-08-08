package com.bashkevich.scoreboardthemerecognizer.screens.settings.generatetheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bashkevich.scoreboardthemerecognizer.LocalOnBack
import com.bashkevich.scoreboardthemerecognizer.components.FileSelectionRow
import com.bashkevich.scoreboardthemerecognizer.components.icons.IconGroup
import com.bashkevich.scoreboardthemerecognizer.components.icons.default_icons.ArrowBack
import com.bashkevich.scoreboardthemerecognizer.components.scoreboard.match_details.MatchDetailsScoreboardView
import com.bashkevich.scoreboardthemerecognizer.components.theme.ThemeNameField
import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import com.bashkevich.scoreboardthemerecognizer.model.match.domain.DOUBLES_SAMPLE_MATCH
import com.bashkevich.scoreboardthemerecognizer.model.theme.domain.ScoreboardTheme
import com.bashkevich.scoreboardthemerecognizer.mvi.LaunchedUiEffectHandler
import com.mohamedrejeb.calf.core.LocalPlatformContext
import com.mohamedrejeb.calf.io.getName
import com.mohamedrejeb.calf.io.readByteArray
import com.mohamedrejeb.calf.picker.FilePickerFileType
import com.mohamedrejeb.calf.picker.FilePickerSelectionMode
import com.mohamedrejeb.calf.picker.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import scoreboardthemerecognizer.shared.generated.resources.Res
import scoreboardthemerecognizer.shared.generated.resources.add
import scoreboardthemerecognizer.shared.generated.resources.generate
import scoreboardthemerecognizer.shared.generated.resources.generate_theme_by_image
import scoreboardthemerecognizer.shared.generated.resources.navigate_back
import scoreboardthemerecognizer.shared.generated.resources.select_image_for_upload
import scoreboardthemerecognizer.shared.generated.resources.you_need_to_log_in

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateThemeScreen(
    modifier: Modifier = Modifier,
    viewModel: GenerateThemeViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onBack = LocalOnBack.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalPlatformContext.current

    val imagePickerLauncher = rememberFilePickerLauncher(
        type = FilePickerFileType.Image,
        selectionMode = FilePickerSelectionMode.Single,
        onResult = { files ->
            scope.launch {
                files.firstOrNull()?.let { file ->
                    val image = ImageFile(
                        name = file.getName(context) ?: "image.png",
                        content = file.readByteArray(context)
                    )
                    viewModel.onEvent(GenerateThemeUiEvent.SelectImage(image))
                }
            }
        }
    )

    LaunchedUiEffectHandler(
        effect = state.action,
        onDismissSnackbar = { snackbarHostState.currentSnackbarData?.dismiss() },
        onConsume = { viewModel.consumeAction() }
    ) { currentAction ->
        when (currentAction) {
            is GenerateThemeAction.ThemeSaved -> onBack()
            is GenerateThemeAction.ShowUnauthorizedActionError ->
                snackbarHostState.showSnackbar(message = getString(Res.string.you_need_to_log_in))
            is GenerateThemeAction.ShowError ->
                snackbarHostState.showSnackbar(message = currentAction.message)
        }
    }

    val hasImage = state.selectedImageName.isNotBlank()
    val hasName = viewModel.themeNameState.text.trim().toString().isNotBlank()
    val canGenerate = hasImage && !state.isGenerating && !state.isSaving
    val canAdd = state.generatedTheme != null && hasName && !state.isSaving

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.generate_theme_by_image)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = IconGroup.Default.ArrowBack,
                            contentDescription = stringResource(Res.string.navigate_back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                FileSelectionRow(
                    fileName = state.selectedImageName,
                    placeholder = stringResource(Res.string.select_image_for_upload),
                    onFileStorageOpen = { imagePickerLauncher.launch() },
                    onClearFile = { viewModel.onEvent(GenerateThemeUiEvent.ClearImage) },
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                )

                Button(
                    onClick = { viewModel.onEvent(GenerateThemeUiEvent.Generate) },
                    enabled = canGenerate,
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.generate))
                }

                Box(
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isGenerating) {
                        CircularProgressIndicator()
                    } else {
                        MatchDetailsScoreboardView(
                            match = DOUBLES_SAMPLE_MATCH,
                            theme = state.generatedTheme ?: ScoreboardTheme.DEFAULT
                        )
                    }
                }

                ThemeNameField(
                    themeNameState = viewModel.themeNameState,
                    oldName = ScoreboardTheme.DEFAULT.name,
                    showOldValue = false,
                )

                Button(
                    onClick = { viewModel.onEvent(GenerateThemeUiEvent.AddTheme) },
                    enabled = canAdd,
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.add))
                }
            }

            if (state.isSaving) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
