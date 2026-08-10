package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

/**
 * "Noise" elements on the scoreboard that are NOT essential for theme measurement: flags, seed
 * numbers (e.g. `(1)`), and country/association codes. The detector (OpenCV, no LLM) classifies
 * each detected element as one of these types (or leaves it as KEEP); the cleaner then paints the
 * noise rects over with the local band background so the image fed to the role-labeling step
 * contains only the essential data (names, scores, serve indicator).
 *
 * [rect] is the region to paint over (in ORIGINAL image pixel coords). For flags it is the
 * saturation-blob bbox (covers the whole flag); for seed/code it is the (merged) element bbox.
 */
enum class NoiseType { FLAG, SEED, COUNTRY_CODE }

data class NoiseElement(
    val type: NoiseType,
    val rect: RoiRect,
    /** Detected element indices (reading order) covered by this noise rect, for diagnostics. */
    val elementIndices: List<Int> = emptyList(),
)

data class NoiseClassification(val elements: List<NoiseElement>) {
    /** All detected-element indices that are classified as noise. */
    fun noiseIndices(): Set<Int> = elements.flatMapTo(mutableSetOf()) { it.elementIndices }

    fun byType(): Map<NoiseType, List<NoiseElement>> = elements.groupBy { it.type }

    fun rects(): List<RoiRect> = elements.map { it.rect }
}
