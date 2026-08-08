package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile

/**
 * On-device, zone-based scoreboard color analysis.
 *
 * Declared `expect` here because the only implementation uses OpenCV, a `jvmMain`-only
 * dependency (`org.openpnp:opencv` in shared/build.gradle.kts). The single `jvm()` target
 * resolves `actual` to the OpenCV implementation in
 * `shared/src/jvmMain/.../model/theme/analysis/ScoreboardImageAnalyzer.jvm.kt`.
 *
 * Stage-1 pipeline (no LLM): decode bytes → auto-detect the four vertical columns (names,
 * previous sets, current set, current game) via a foreground-density projection → run K-Means
 * per zone and take the mode (most frequent color) of each cluster → print a `[ThemeRecognizer]`
 * report. The caller maps the result to a theme via [toThemeContent]. Throws on unrecoverable
 * failure (empty image, decode error); the caller wraps that into a
 * [com.bashkevich.scoreboardthemerecognizer.core.remote.LoadResult].
 */
internal expect suspend fun analyzeScoreboardImage(image: ImageFile): ScoreboardZones
