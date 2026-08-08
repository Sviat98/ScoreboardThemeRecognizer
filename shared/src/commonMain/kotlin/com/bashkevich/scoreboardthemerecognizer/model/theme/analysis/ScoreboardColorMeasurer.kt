package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile

/**
 * Phase 2 of the theme agent: deterministic color measurement from the six boxes the LLM returned
 * in [AiComponentLayout]. Declared `expect`, like [analyzeScoreboardImage], because the only
 * implementation uses OpenCV (a `jvmMain`-only dependency).
 *
 * For each box it: converts the normalized box to pixels on the full-resolution image, "snaps" it
 * to the single strongest foreground glyph (to tolerate LLM coordinate drift), and measures the
 * background fill and/or the glyph color via a histogram mode with background-distance separation
 * (port of `ScoreboardColorExtractor` from TennisScoreKeeperBackend, on OpenCV Mats). The LLM never
 * names colors — so a flat navy fill comes out bit-accurate instead of collapsing to black.
 */
internal expect suspend fun measureComponentsColors(
    image: ImageFile,
    layout: AiComponentLayout,
): ScoreboardComponents
