package com.bashkevich.scoreboardthemerecognizer.screens.debug

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bashkevich.scoreboardthemerecognizer.components.FileSelectionRow
import com.bashkevich.scoreboardthemerecognizer.components.icons.IconGroup
import com.bashkevich.scoreboardthemerecognizer.components.icons.default_icons.ArrowBack
import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.ClusterInfo
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.ElementThemeAgent
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.PaletteClassification
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.RgbColor
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.extractColorPalette
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.parseHexColor
import com.mohamedrejeb.calf.core.LocalPlatformContext
import com.mohamedrejeb.calf.io.getName
import com.mohamedrejeb.calf.io.readByteArray
import com.mohamedrejeb.calf.picker.FilePickerFileType
import com.mohamedrejeb.calf.picker.FilePickerSelectionMode
import com.mohamedrejeb.calf.picker.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image

private data class SlotRow(val label: String, val hex: String?)

private fun PaletteClassification.rows(): List<SlotRow> = listOf(
    SlotRow("main background", mainBackgroundColor),
    SlotRow("main text", mainTextColor),
    SlotRow("serve", serveColor),
    SlotRow("prev set win", previousSetWinTextColor),
    SlotRow("prev set lose", previousSetLoseTextColor),
    SlotRow("current set bg", currentSetBackgroundColor),
    SlotRow("current set text", currentSetTextColor),
    SlotRow("current game bg", currentGameBackgroundColor),
    SlotRow("current game text", currentGameTextColor),
)

private fun RgbColor.toComposeColor(): Color = Color(red = r / 255f, green = g / 255f, blue = b / 255f)

/**
 * Debug screen for the palette→LLM approach (no production wiring). Pick an image → background-trim
 * it → extract the Color-Thief palette with per-color frequency % → optionally ask GPT-4o to map
 * each of the nine theme slots to a palette color. Lets us eyeball whether a global palette beats
 * the current box-localize→measure pipeline, especially on `main_text`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteDebugScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalPlatformContext.current
    val agent = remember { ElementThemeAgent() }

    var imageFile by remember { mutableStateOf<ImageFile?>(null) }
    var analyzedBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var palette by remember { mutableStateOf<List<ClusterInfo>>(emptyList()) }
    var analyzedPng by remember { mutableStateOf<ByteArray?>(null) }
    var classification by remember { mutableStateOf<PaletteClassification?>(null) }
    var status by remember { mutableStateOf("Pick an image") }
    var processing by remember { mutableStateOf(false) }

    fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

    val picker = rememberFilePickerLauncher(
        type = FilePickerFileType.Image,
        selectionMode = FilePickerSelectionMode.Single,
        onResult = { files ->
            scope.launch {
                files.firstOrNull()?.let { f ->
                    val bytes = f.readByteArray(context)
                    imageFile = ImageFile(name = f.getName(context) ?: "image.png", content = bytes)
                    analyzedBitmap = null
                    palette = emptyList()
                    analyzedPng = null
                    classification = null
                    status = "Loaded ${imageFile?.name}"
                }
            }
        },
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Debug: color palette") },
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
                placeholder = "Select image",
                onFileStorageOpen = { picker.launch() },
                onClearFile = {
                    imageFile = null
                    analyzedBitmap = null
                    palette = emptyList()
                    analyzedPng = null
                    classification = null
                    status = "Pick an image"
                },
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            )

            Button(
                onClick = {
                    val img = imageFile ?: return@Button
                    processing = true
                    status = "Extracting palette…"
                    scope.launch {
                        try {
                            val result = extractColorPalette(img)
                            palette = result.palette
                            analyzedPng = result.analyzedImagePng
                            analyzedBitmap = decode(result.analyzedImagePng)
                            classification = null
                            status = if (result.palette.isEmpty()) "No palette extracted" else "${result.palette.size} colors"
                        } catch (e: Exception) {
                            status = "Failed: ${e.message}"
                        }
                        processing = false
                    }
                },
                enabled = imageFile != null && !processing,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            ) {
                Text("Extract palette (Color Thief + frequency)")
            }

            Button(
                onClick = {
                    val png = analyzedPng ?: return@Button
                    val pal = palette
                    processing = true
                    status = "Classifying via GPT-4o…"
                    scope.launch {
                        try {
                            classification = agent.classifyPalette(png, pal)
                            status = "Classified"
                        } catch (e: Exception) {
                            status = "Failed: ${e.message}"
                        }
                        processing = false
                    }
                },
                enabled = analyzedPng != null && !processing,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            ) {
                Text("Classify via GPT-4o")
            }

            Text(status, style = MaterialTheme.typography.bodySmall)

            if (processing) {
                CircularProgressIndicator()
            }

            analyzedBitmap?.let { bmp ->
                Text("Analyzed image:", style = MaterialTheme.typography.titleSmall)
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
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (palette.isNotEmpty()) {
                Column(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Palette (${palette.size} colors)", style = MaterialTheme.typography.titleSmall)
                    palette.forEach { c -> PaletteRow(c) }
                }
            }

            classification?.let { cls ->
                Column(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Classification (9 theme slots)", style = MaterialTheme.typography.titleSmall)
                    cls.rows().forEach { row -> ClassificationRow(row) }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(c: ClusterInfo) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Swatch(color = c.centroid.toComposeColor())
            Spacer(Modifier.width(8.dp))
            Text(c.centroid.toHex(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text("%.1f%%".format(c.share * 100), style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(
            progress = { c.share.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
        )
    }
}

@Composable
private fun ClassificationRow(row: SlotRow) {
    val swatchColor = parseHexColor(row.hex)?.toComposeColor()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Swatch(color = swatchColor, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(row.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = row.hex ?: "—",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.hex == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Swatch(color: Color?, size: Dp = 24.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(color ?: Color.Transparent)
            .border(1.dp, MaterialTheme.colorScheme.outline),
    )
}
