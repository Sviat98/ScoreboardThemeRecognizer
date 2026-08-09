package com.bashkevich.scoreboardthemerecognizer.screens.debug

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
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.detectScoreboardElements
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.renderElementsDebugOverlay
import com.mohamedrejeb.calf.core.LocalPlatformContext
import com.mohamedrejeb.calf.io.getName
import com.mohamedrejeb.calf.io.readByteArray
import com.mohamedrejeb.calf.picker.FilePickerFileType
import com.mohamedrejeb.calf.picker.FilePickerSelectionMode
import com.mohamedrejeb.calf.picker.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image

/**
 * Debug screen for the OpenCV element detection (no LLM): pick an image, press the button, get the
 * image back with each detected element drawn as a distinct-colored rectangle (no text labels, to
 * keep the image clean). Shows how OpenCV "sees" the scoreboard's individual elements.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementsDebugScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalPlatformContext.current

    var imageFile by remember { mutableStateOf<ImageFile?>(null) }
    var shownBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
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
                    shownBitmap = decode(bytes)
                    status = if (shownBitmap != null) "Loaded ${imageFile?.name}" else "Failed to decode image"
                }
            }
        },
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Debug: OpenCV element detection") },
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
                    shownBitmap = null
                    status = "Pick an image"
                },
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            )

            Button(
                onClick = {
                    val img = imageFile ?: return@Button
                    processing = true
                    status = "Detecting…"
                    scope.launch {
                        try {
                            val elements = detectScoreboardElements(img)
                            val overlay = renderElementsDebugOverlay(img, elements)
                            shownBitmap = decode(overlay)
                            status = "Detected ${elements.elements.size} elements"
                        } catch (e: Exception) {
                            status = "Failed: ${e.message}"
                        }
                        processing = false
                    }
                },
                enabled = imageFile != null && !processing,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            ) {
                Text(if (processing) "Detecting…" else "Show OpenCV markup (no LLM)")
            }

            Text(status, style = MaterialTheme.typography.bodySmall)

            if (processing) {
                CircularProgressIndicator()
            }

            shownBitmap?.let { bmp ->
                Box(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                        .border(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "scoreboard markup",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
