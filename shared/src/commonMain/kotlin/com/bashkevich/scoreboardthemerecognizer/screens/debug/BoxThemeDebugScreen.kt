package com.bashkevich.scoreboardthemerecognizer.screens.debug

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bashkevich.scoreboardthemerecognizer.components.FileSelectionRow
import com.bashkevich.scoreboardthemerecognizer.components.icons.IconGroup
import com.bashkevich.scoreboardthemerecognizer.components.icons.default_icons.ArrowBack
import com.bashkevich.scoreboardthemerecognizer.components.scoreboard.match_details.MatchDetailsScoreboardView
import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import com.bashkevich.scoreboardthemerecognizer.model.match.domain.DOUBLES_SAMPLE_MATCH
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.ElementRoles
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.RoiRect
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.ScoreboardElement
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.ScoreboardElements
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.measureScoreboardElements
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.readProjectRootText
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.toThemeContent
import com.bashkevich.scoreboardthemerecognizer.model.theme.domain.ScoreboardTheme
import com.bashkevich.scoreboardthemerecognizer.model.theme.domain.toScoreboardTheme
import com.mohamedrejeb.calf.core.LocalPlatformContext
import com.mohamedrejeb.calf.io.getName
import com.mohamedrejeb.calf.io.readByteArray
import com.mohamedrejeb.calf.picker.FilePickerFileType
import com.mohamedrejeb.calf.picker.FilePickerSelectionMode
import com.mohamedrejeb.calf.picker.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val BOXES_FILE = "boxes.json"
private val BOXES_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

// Role → fixed element index (the boxes.json components feed the measurer as if they were detected
// elements, in this stable order).
private val ROLE_ORDER = listOf("main_text", "serve", "prev_set_win", "prev_set_lose", "current_set", "current_game")

@Serializable private data class BoxEntry(val role: String, val x: Double, val y: Double, val w: Double, val h: Double)
@Serializable private data class BoxFile(val components: List<BoxEntry> = emptyList())

/**
 * Debug screen "theme from a boxes matrix" (no LLM): pick an image, read `boxes.json` (6 pixel-coord
 * boxes) from the project root, and run the SAME color measurement the real pipeline uses (each box
 * is fed to the measurer as a pre-labelled element). The resulting theme is shown in the live
 * scoreboard preview and printed as JSON to the console.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxThemeDebugScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalPlatformContext.current

    var imageFile by remember { mutableStateOf<ImageFile?>(null) }
    var theme by remember { mutableStateOf<ScoreboardTheme?>(null) }
    var status by remember { mutableStateOf("Pick an image, then measure from $BOXES_FILE") }
    var processing by remember { mutableStateOf(false) }

    val picker = rememberFilePickerLauncher(
        type = FilePickerFileType.Image,
        selectionMode = FilePickerSelectionMode.Single,
        onResult = { files ->
            scope.launch {
                files.firstOrNull()?.let { f ->
                    imageFile = ImageFile(name = f.getName(context) ?: "image.png", content = f.readByteArray(context))
                    theme = null
                    status = "Loaded ${imageFile?.name}"
                }
            }
        },
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Debug: theme from boxes (no LLM)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = IconGroup.Default.ArrowBack, contentDescription = "back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FileSelectionRow(
                fileName = imageFile?.name ?: "",
                placeholder = "Select scoreboard image",
                onFileStorageOpen = { picker.launch() },
                onClearFile = {
                    imageFile = null
                    theme = null
                    status = "Pick an image"
                },
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            )

            Button(
                onClick = {
                    val img = imageFile
                    if (img == null) {
                        status = "Pick an image first"
                        return@Button
                    }
                    val text = readProjectRootText(BOXES_FILE)
                    if (text == null) {
                        status = "$BOXES_FILE not found in project root"
                        return@Button
                    }
                    processing = true
                    status = "Measuring…"
                    scope.launch {
                        try {
                            val boxes = BOXES_JSON.decodeFromString<BoxFile>(text)
                            val byRole = boxes.components.associateBy { it.role }
                            val elements = ScoreboardElements(
                                imageWidth = 0,
                                imageHeight = 0,
                                elements = ROLE_ORDER.mapIndexed { i, role ->
                                    val e = byRole[role]
                                    val rect = if (e != null) RoiRect(e.x.toInt(), e.y.toInt(), e.w.toInt(), e.h.toInt()) else RoiRect(0, 0, 0, 0)
                                    ScoreboardElement(i, rect)
                                },
                            )
                            val roles = ElementRoles(
                                isScoreboard = true,
                                mainTextElement = if (byRole.containsKey("main_text")) 0 else null,
                                serveElement = if (byRole.containsKey("serve")) 1 else null,
                                prevSetWinElement = if (byRole.containsKey("prev_set_win")) 2 else null,
                                prevSetLoseElement = if (byRole.containsKey("prev_set_lose")) 3 else null,
                                currentSetElement = if (byRole.containsKey("current_set")) 4 else null,
                                currentGameElement = if (byRole.containsKey("current_game")) 5 else null,
                            )
                            val measured = measureScoreboardElements(img, elements, roles).toThemeContent()
                            theme = measured.toScoreboardTheme()
                            status = "Measured (${boxes.components.size} boxes)"
                        } catch (e: Exception) {
                            status = "Failed: ${e.message}"
                        }
                        processing = false
                    }
                },
                enabled = imageFile != null && !processing,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            ) {
                Text(if (processing) "Measuring…" else "Measure theme from $BOXES_FILE")
            }

            Text(status, style = MaterialTheme.typography.bodySmall)

            if (processing) {
                CircularProgressIndicator()
            } else {
                theme?.let {
                    Text("Result theme", style = MaterialTheme.typography.titleMedium)
                    MatchDetailsScoreboardView(match = DOUBLES_SAMPLE_MATCH, theme = it)
                }
            }
        }
    }
}
