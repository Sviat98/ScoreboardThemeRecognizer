package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile

/**
 * The two verification artifacts produced by the noise-cleaning pre-step:
 *  - [markupPng]: the original image with every detected element outlined — KEEP=green, FLAG=red,
 *    SEED=blue, COUNTRY_CODE=magenta — so it is visible at a glance what is removed vs kept.
 *  - [cleanedPng]: the image with the noise rects painted over in the local band background, i.e.
 *    the cleaned input that would be fed to the role-labeling step.
 */
data class NoiseCleaningArtifacts(
    val markupPng: ByteArray,
    val cleanedPng: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NoiseCleaningArtifacts) return false
        return markupPng.contentEquals(other.markupPng) && cleanedPng.contentEquals(other.cleanedPng)
    }

    override fun hashCode(): Int = markupPng.contentHashCode() * 31 + cleanedPng.contentHashCode()
}

/**
 * Renders the cleaning artifacts for [elements] under [noise]. `expect`/`actual` (OpenCV is
 * jvmMain-only). Pure rendering — does not mutate the input image bytes.
 */
internal expect fun renderNoiseCleaning(
    image: ImageFile,
    elements: ScoreboardElements,
    noise: NoiseClassification,
): NoiseCleaningArtifacts
