package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile

/**
 * On-device scoreboard color analysis.
 *
 * Declared `expect` here because the only implementation uses OpenCV, which is wired as a
 * `jvmMain`-only dependency (`org.openpnp:opencv` in shared/build.gradle.kts). The single
 * `jvm()` target resolves `actual` to the OpenCV implementation in
 * `shared/src/jvmMain/.../model/theme/analysis/ScoreboardImageAnalyzer.jvm.kt`.
 *
 * Pipeline (Stage 1, no LLM): decode bytes → detect the scoreboard ROI via Otsu threshold +
 * contours → run K-Means on the ROI to split background/text and discover accent colors →
 * print a diagnostic report to stdout. The caller maps the returned palette to a theme via
 * [toThemeContent]. Throws on unrecoverable failure (empty image, decode error); the caller
 * wraps that into a [com.bashkevich.scoreboardthemerecognizer.core.remote.LoadResult].
 */
internal expect suspend fun analyzeScoreboardImage(image: ImageFile): ScoreboardPalette
