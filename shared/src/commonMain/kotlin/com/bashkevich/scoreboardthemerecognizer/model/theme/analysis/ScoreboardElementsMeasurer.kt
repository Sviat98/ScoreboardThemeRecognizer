package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile

/**
 * Phase 3 of the connected-components path (deterministic, OpenCV — `expect` because OpenCV is
 * jvmMain-only). For each role, looks up the element rect by the index the LLM chose, then measures
 * the background fill and/or glyph color in that rect (outer-band bg + hybrid glyph). Returns the
 * six-component result → `toThemeContent()`.
 */
internal expect suspend fun measureScoreboardElements(
    image: ImageFile,
    elements: ScoreboardElements,
    roles: ElementRoles,
): ScoreboardComponents
