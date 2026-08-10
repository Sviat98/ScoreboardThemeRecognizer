package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Offline verification probe for the palette path (no LLM, no API key — runs in a normal build, and
 * since palette extraction no longer uses OpenCV, no native load either).
 *
 * For each `image_tests/sample_N` fixture: run Color-Thief palette + own frequency pass on the image
 * AS-IS (no background trimming) and print every color with its %. NOTE: these fixtures may still
 * carry surrounding background, so expect the margin color to dominate — the point here is only that
 * the pipeline runs and produces a sensible frequency-sorted palette.
 *
 * Run: `./gradlew :shared:jvmTest --tests "*PaletteProbe*"`
 */
class PaletteProbe {

    @Test
    fun probe() {
        for (sampleDir in listOf("sample_1", "sample_2", "sample_3")) {
            val base = projectRoot().resolve("image_tests").resolve(sampleDir)
            if (!base.exists()) {
                println("PALETTE-PROBE $sampleDir: fixtures not found, skip")
                continue
            }
            val imageFile = base.listFiles()!!.first { it.name.startsWith("image_") }
            val image = ImageFile(imageFile.name, imageFile.readBytes())

            val result = runBlocking { extractColorPalette(image) }

            println("PALETTE-PROBE $sampleDir ── ${result.palette.size} colors ──")
            for (c in result.palette) {
                println("  ${c.centroid.toHex()}  %.2f%%  (pixels=${c.pixelCount})".format(c.share * 100))
            }
        }
    }
}
