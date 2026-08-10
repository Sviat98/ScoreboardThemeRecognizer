package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile

/**
 * Phase-0 pre-cleaning of the connected-components path (deterministic, OpenCV — `expect` because
 * OpenCV is jvmMain-only). Given the already-detected [ScoreboardElements], classifies which ones
 * are noise — flags (multi-color regions), seed numbers, and country/association codes — without
 * an LLM. The returned [NoiseClassification] drives [renderNoiseCleaning], which paints the noise
 * rects over with the local background, producing a cleaned image that contains only the essential
 * scoreboard data (names, scores, serve).
 *
 * Flags are detected reliably by color richness; seeds/codes are identified per player row as the
 * identity-zone elements narrower than the (widest) surname element (see thresholds in the jvm impl).
 */
internal expect suspend fun classifyNoiseElements(
    image: ImageFile,
    elements: ScoreboardElements,
): NoiseClassification
