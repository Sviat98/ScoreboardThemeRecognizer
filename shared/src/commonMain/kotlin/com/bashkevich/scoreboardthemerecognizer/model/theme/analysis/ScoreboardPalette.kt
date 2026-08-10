package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile

/**
 * Palette extraction result for the debug screen. [palette] is the image's representative colors
 * with their pixel frequency ([ClusterInfo.share]), sorted most-frequent first; [analyzedImagePng]
 * is the user-provided image (re-encoded as PNG) — what the palette was computed from AND the image
 * the LLM classifier sees — so the screen can render it and forward it to
 * [ElementThemeAgent.classifyPalette].
 *
 * No background trimming is performed: the user is expected to supply a screenshot already cropped
 * to the scoreboard.
 *
 * Pure-KMP (commonMain) so the debug screen can consume it without the jvmMain-only Color Thief
 * bindings.
 */
internal data class PaletteResult(
    val palette: List<ClusterInfo>,
    val analyzedImagePng: ByteArray,
)

/**
 * Color-Thief palette (representative colors via MMCQ median-cut) + per-color frequency, computed
 * from the user-provided image AS-IS — no background trimming, the caller supplies a clean
 * scoreboard crop. Color Thief is `jvmMain`-only (`java.awt.image.BufferedImage`), so this is
 * `expect`/`actual` exactly like [analyzeScoreboardImage]. Used only by the debug screen for now —
 * not wired into the production pipeline.
 */
internal expect suspend fun extractColorPalette(image: ImageFile): PaletteResult

/** Parses a `#RRGGBB` / `RRGGBB` hex string into an [RgbColor], or null if malformed. */
internal fun parseHexColor(hex: String?): RgbColor? {
    if (hex == null) return null
    val s = hex.removePrefix("#").trim()
    if (s.length != 6) return null
    val r = s.substring(0, 2).toIntOrNull(16) ?: return null
    val g = s.substring(2, 4).toIntOrNull(16) ?: return null
    val b = s.substring(4, 6).toIntOrNull(16) ?: return null
    return runCatching { RgbColor(r, g, b) }.getOrNull()
}
