package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun cleanScoreboardImage(image: ImageFile): ImageFile =
    withContext(Dispatchers.Default) {
        ensureOpenCvLoaded()
        if (image.content.isEmpty()) return@withContext image
        val elements = detectScoreboardElements(image)
        val noise = classifyNoiseElements(image, elements)
        if (noise.elements.isEmpty()) return@withContext image // nothing to clean → keep original bytes
        val cleaned = renderNoiseCleaning(image, elements, noise).cleanedPng
        println(
            "[ThemeRecognizer] cleaned noise: " +
                noise.byType().entries.joinToString(", ") { (t, v) -> "${t.name.lowercase()}=${v.size}" }
        )
        ImageFile(image.name, cleaned)
    }
