package com.bashkevich.scoreboardthemerecognizer.screens.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bashkevich.scoreboardthemerecognizer.components.FileSelectionRow
import com.bashkevich.scoreboardthemerecognizer.components.icons.IconGroup
import com.bashkevich.scoreboardthemerecognizer.components.icons.default_icons.ArrowBack
import com.bashkevich.scoreboardthemerecognizer.components.scoreboard.match_details.MatchDetailsScoreboardView
import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import com.bashkevich.scoreboardthemerecognizer.model.match.domain.DOUBLES_SAMPLE_MATCH
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.AiComponentLayout
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.measureComponentsColors
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
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Image

private const val BOXES_FILE = "boxes.json"
private val BOX_OVERLAY_COLOR = Color.Red
private val LABEL_STYLE = TextStyle(color = Color.White, fontSize = 12.sp)
private val BOXES_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Debug screen that bypasses the LLM: you pick a scoreboard image, the app reads [BOXES_FILE]
 * (`boxes.json` in the project root) for the six component boxes, and runs the OpenCV
 * [measureComponentsColors] directly. The boxes are drawn over the image so you can confirm the
 * coordinates land on the right elements, and the measured theme is shown in the live scoreboard
 * preview.
 *
 * Coordinate format in [BOXES_FILE]: **pixels of the loaded image** (x,y = top-left corner; w,h =
 * size) — NOT normalized 0..1. Read them off an image editor and adjust to your image's resolution.
 * The screen normalizes them by the decoded bitmap dimensions before calling the measurer (the LLM
 * path uses normalized 0..1; this debug path converts pixels → normalized for you). Set env
 * `SCOREBOARD_DEBUG_OVERLAY=1` to additionally write the OpenCV-rendered overlay PNG (with refined
 * boxes + measured colors) to the project root.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugMeasureScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalPlatformContext.current
    val textMeasurer = rememberTextMeasurer()

    var imageFile by remember { mutableStateOf<ImageFile?>(null) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var pxBoxes by remember { mutableStateOf<AiComponentLayout?>(null) } // box values in PIXELS
    var boxesStatus by remember { mutableStateOf("Loading $BOXES_FILE…") }
    var theme by remember { mutableStateOf<ScoreboardTheme?>(null) }
    var measuring by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reloadBoxes() {
        val text = readProjectRootText(BOXES_FILE)
        if (text == null) {
            pxBoxes = null
            boxesStatus = "✗ $BOXES_FILE not found in project root"
            return
        }
        val parsed = runCatching { BOXES_JSON.decodeFromString<AiComponentLayout>(text) }
        pxBoxes = parsed.getOrNull()
        boxesStatus = parsed.fold(
            onSuccess = { "✓ ${it.components.size} box(es) loaded (pixel coords)" },
            onFailure = { "✗ parse error: ${it.message}" },
        )
    }

    LaunchedEffect(Unit) { reloadBoxes() }

    val imagePicker = rememberFilePickerLauncher(
        type = FilePickerFileType.Image,
        selectionMode = FilePickerSelectionMode.Single,
        onResult = { files ->
            scope.launch {
                files.firstOrNull()?.let { f ->
                    val bytes = f.readByteArray(context)
                    imageFile = ImageFile(name = f.getName(context) ?: "image.png", content = bytes)
                    bitmap = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
                    theme = null
                    error = if (bitmap == null) "Failed to decode image" else null
                }
            }
        },
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Debug: measure boxes (no LLM)") },
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
                onFileStorageOpen = { imagePicker.launch() },
                onClearFile = {
                    imageFile = null
                    bitmap = null
                    theme = null
                    error = null
                },
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            )

            Text(boxesStatus, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { reloadBoxes() }) { Text("Reload $BOXES_FILE") }

            val canMeasure = imageFile != null && bitmap != null &&
                pxBoxes?.components?.isNotEmpty() == true && !measuring
            Button(
                onClick = {
                    val img = imageFile ?: return@Button
                    val bmp = bitmap ?: return@Button
                    val px = pxBoxes ?: return@Button
                    measuring = true
                    error = null
                    scope.launch {
                        try {
                            val normalized = normalizeByImage(px, bmp.width, bmp.height)
                            val components = measureComponentsColors(img, normalized)
                            theme = components.toThemeContent().toScoreboardTheme()
                        } catch (e: Exception) {
                            error = "Measure failed: ${e.message}"
                        }
                        measuring = false
                    }
                },
                enabled = canMeasure,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            ) {
                Text(if (measuring) "Measuring…" else "Measure (OpenCV)")
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            val bmp = bitmap
            val px = pxBoxes
            if (bmp != null && px != null) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                        .border(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "scoreboard",
                        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Pixel coords → display coords: scale by (displayed size / image size).
                        val sx = size.width / bmp.width
                        val sy = size.height / bmp.height
                        for (ab in px.components) {
                            val x = (ab.x * sx).toFloat()
                            val y = (ab.y * sy).toFloat()
                            val w = (ab.w * sx).toFloat()
                            val h = (ab.h * sy).toFloat()
                            drawRect(
                                color = BOX_OVERLAY_COLOR,
                                topLeft = Offset(x, y),
                                size = Size(w, h),
                                style = Stroke(width = 3f),
                            )
                            val label = textMeasurer.measure(AnnotatedString(ab.role), style = LABEL_STYLE)
                            val labelY = (y - label.size.height).coerceAtLeast(0f)
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(x, labelY),
                                size = Size(label.size.width.toFloat(), label.size.height.toFloat()),
                            )
                            drawText(label, topLeft = Offset(x, labelY))
                        }
                    }
                }
            }

            if (measuring) {
                CircularProgressIndicator()
            } else if (theme != null) {
                Text("Result theme", style = MaterialTheme.typography.titleMedium)
                MatchDetailsScoreboardView(match = DOUBLES_SAMPLE_MATCH, theme = theme ?: ScoreboardTheme.DEFAULT)
            }
        }
    }
}

/** Converts pixel-valued boxes to normalized (0..1) by the decoded image dimensions. */
private fun normalizeByImage(layout: AiComponentLayout, width: Int, height: Int): AiComponentLayout {
    val w = width.toDouble().coerceAtLeast(1.0)
    val h = height.toDouble().coerceAtLeast(1.0)
    return layout.copy(
        components = layout.components.map { ab ->
            ab.copy(
                x = (ab.x / w).coerceIn(0.0, 1.0),
                y = (ab.y / h).coerceIn(0.0, 1.0),
                w = (ab.w / w).coerceIn(0.0, 1.0),
                h = (ab.h / h).coerceIn(0.0, 1.0),
            )
        },
    )
}
