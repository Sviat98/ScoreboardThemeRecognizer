package com.bashkevich.scoreboardthemerecognizer.screens.debug

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.bashkevich.scoreboardthemerecognizer.components.FileSelectionRow
import com.bashkevich.scoreboardthemerecognizer.components.icons.IconGroup
import com.bashkevich.scoreboardthemerecognizer.components.icons.default_icons.ArrowBack
import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.classifyNoiseElements
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.detectScoreboardElements
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.renderNoiseCleaning
import com.mohamedrejeb.calf.core.LocalPlatformContext
import com.mohamedrejeb.calf.io.getName
import com.mohamedrejeb.calf.io.readByteArray
import com.mohamedrejeb.calf.picker.FilePickerFileType
import com.mohamedrejeb.calf.picker.FilePickerSelectionMode
import com.mohamedrejeb.calf.picker.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image

private enum class NoiseView { CLEANED, MARKUP, ORIGINAL }

/**
 * Debug screen for the OpenCV noise-cleaning pre-step (no LLM): pick an image, run detection +
 * classification, then view the result three ways — the CLEANED image (flags / seeds / country-codes
 * painted over with the local background → what would be fed to the role-labeling step), the MARKUP
 * (every element outlined by classification: green=keep, red=flag, blue=seed, magenta=code), or the
 * ORIGINAL. The default view is CLEANED.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoiseCleaningDebugScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalPlatformContext.current

    var imageFile by remember { mutableStateOf<ImageFile?>(null) }
    var originalBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var cleanedBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var markupBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var view by remember { mutableStateOf(NoiseView.CLEANED) }
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
                    originalBitmap = decode(bytes)
                    cleanedBitmap = null
                    markupBitmap = null
                    view = NoiseView.CLEANED
                    status = if (originalBitmap != null) "Loaded ${imageFile?.name}" else "Failed to decode image"
                }
            }
        },
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Debug: noise cleaning (no LLM)") },
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
                    originalBitmap = null
                    cleanedBitmap = null
                    markupBitmap = null
                    status = "Pick an image"
                },
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            )

            Button(
                onClick = {
                    val img = imageFile ?: return@Button
                    processing = true
                    status = "Cleaning…"
                    scope.launch {
                        try {
                            val elements = detectScoreboardElements(img)
                            val noise = classifyNoiseElements(img, elements)
                            val artifacts = renderNoiseCleaning(img, elements, noise)
                            cleanedBitmap = decode(artifacts.cleanedPng)
                            markupBitmap = decode(artifacts.markupPng)
                            view = NoiseView.CLEANED
                            val removed = noise.noiseIndices().size
                            status = buildString {
                                append("${elements.elements.size} elements, removed $removed: ")
                                append(
                                    noise.byType().entries.joinToString(", ") { (t, v) -> "${t.name.lowercase()}=${v.size}" }
                                        .ifEmpty { "none" }
                                )
                            }
                        } catch (e: Exception) {
                            status = "Failed: ${e.message}"
                        }
                        processing = false
                    }
                },
                enabled = imageFile != null && !processing,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            ) {
                Text(if (processing) "Cleaning…" else "Clean noise (detect + classify + render)")
            }

            val shown = when (view) {
                NoiseView.CLEANED -> cleanedBitmap
                NoiseView.MARKUP -> markupBitmap
                NoiseView.ORIGINAL -> originalBitmap
            }
            if (cleanedBitmap != null || markupBitmap != null) {
                Row(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = view == NoiseView.CLEANED,
                        onClick = { view = NoiseView.CLEANED },
                        label = { Text("Cleaned") },
                    )
                    FilterChip(
                        selected = view == NoiseView.MARKUP,
                        onClick = { view = NoiseView.MARKUP },
                        label = { Text("Markup") },
                    )
                    FilterChip(
                        selected = view == NoiseView.ORIGINAL,
                        onClick = { view = NoiseView.ORIGINAL },
                        label = { Text("Original") },
                    )
                }
            }

            Text(status, style = MaterialTheme.typography.bodySmall)

            if (processing) {
                CircularProgressIndicator()
            }

            shown?.let { bmp ->
                Box(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                        .border(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "scoreboard ${view.name.lowercase()}",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
