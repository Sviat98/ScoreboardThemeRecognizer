package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile

/**
 * Pre-cleans a scoreboard image: detects its elements, classifies the noise ones (flags / seed
 * numbers / country codes — pure OpenCV, no LLM), and returns a NEW image with the noise rects
 * painted over in the local band background. Returns the original image unchanged when no noise is
 * found. Best-effort — callers wrap it so a cleaning failure falls back to the original image.
 *
 * Wired into [com.bashkevich.scoreboardthemerecognizer.model.theme.repository.ThemeRepositoryImpl]
 * so both the LLM and the Stage-1 heuristic paths run on the cleaned image.
 */
internal expect suspend fun cleanScoreboardImage(image: ImageFile): ImageFile
