package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile

/**
 * Phase 1 of the connected-components path (deterministic, OpenCV — `expect` because OpenCV is
 * jvmMain-only). Trims the surrounding background, builds the text-foreground mask, runs connected
 * components (NO morphology close — close merges dense text), filters out noise / giant blobs /
 * thin lines, and returns the surviving elements numbered in reading order, in ORIGINAL image
 * pixel coordinates.
 */
internal expect suspend fun detectScoreboardElements(image: ImageFile): ScoreboardElements
