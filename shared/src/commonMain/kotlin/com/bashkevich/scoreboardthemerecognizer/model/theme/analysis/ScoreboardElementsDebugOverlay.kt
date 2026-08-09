package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile

/**
 * Debug overlay for the OpenCV element detection (no LLM): draws a DISTINCT-COLORED rectangle around
 * each detected element (one color per element index), with NO text labels, so the image stays
 * clean. Returns PNG bytes. `expect`/`actual` (OpenCV is jvmMain-only).
 */
internal expect fun renderElementsDebugOverlay(image: ImageFile, elements: ScoreboardElements): ByteArray
