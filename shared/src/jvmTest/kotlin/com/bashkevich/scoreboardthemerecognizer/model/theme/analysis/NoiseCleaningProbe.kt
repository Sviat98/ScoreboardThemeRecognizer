package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import kotlinx.coroutines.runBlocking
import nu.pattern.OpenCV
import kotlin.test.Test

/**
 * Verification probe for the OpenCV noise-cleaning pre-step (no LLM, no API key — runs in a normal
 * CI build). For each `image_tests/sample_N` fixture: detect elements → classify noise (flags /
 * seeds / country-codes) → render the markup (KEEP/FLAG/SEED/COUNTRY_CODE) and the cleaned image →
 * write both PNGs to the PROJECT ROOT so the result can be inspected by eye:
 *  - `noise-markup-sample_N.png` — every detected element outlined by classification.
 *  - `cleaned-sample_N.png` — noise rects painted over with the local band background.
 *
 * `sample_1` has no flags/seeds/codes (COBOLLI/ZVEREV) — it is the false-positive sanity check:
 * the cleaned image should be essentially identical to the input.
 *
 * Run: `./gradlew :shared:jvmTest --tests "*NoiseCleaningProbe*"`
 */
class NoiseCleaningProbe {

    @Test
    fun probe() {
        OpenCV.loadLocally()
        for (sampleDir in listOf("sample_1", "sample_2", "sample_3")) {
            val base = projectRoot().resolve("image_tests").resolve(sampleDir)
            if (!base.exists()) {
                println("NOISE-PROBE $sampleDir: fixtures not found, skip")
                continue
            }
            val imageFile = base.listFiles()!!.first { it.name.startsWith("image_") }
            val image = ImageFile(imageFile.name, imageFile.readBytes())

            val elements = runBlocking { detectScoreboardElements(image) }
            val noise = runBlocking { classifyNoiseElements(image, elements) }
            val artifacts = renderNoiseCleaning(image, elements, noise)

            val markupFile = projectRoot().resolve("noise-markup-$sampleDir.png")
            val cleanedFile = projectRoot().resolve("cleaned-$sampleDir.png")
            markupFile.writeBytes(artifacts.markupPng)
            cleanedFile.writeBytes(artifacts.cleanedPng)

            // Ground-truth diagnostics (do NOT trust reading thin outline colors off the PNG).
            val typeByIndex = HashMap<Int, NoiseType>()
            for (n in noise.elements) for (idx in n.elementIndices) typeByIndex.putIfAbsent(idx, n.type)
            println("NOISE-PROBE $sampleDir ── elements ──")
            for (e in elements.elements) {
                val r = e.rect
                val label = typeByIndex[e.index]?.name ?: "KEEP"
                println(
                    "  #${e.index.toString().padStart(2)} ${label.padEnd(12)} " +
                        "x=${r.x.toString().padStart(4)} y=${r.y.toString().padStart(3)} " +
                        "${r.width.toString().padStart(3)}x${r.height.toString().padStart(3)}"
                )
            }
            println("NOISE-PROBE $sampleDir ── noise rects ──")
            for (n in noise.elements) {
                val r = n.rect
                println(
                    "  ${n.type.name.padEnd(12)} x=${r.x.toString().padStart(4)} y=${r.y.toString().padStart(3)} " +
                        "${r.width.toString().padStart(3)}x${r.height.toString().padStart(3)} " +
                        "covers=${n.elementIndices}"
                )
            }

            val byType = noise.byType()
            val keepCount = elements.elements.size - noise.noiseIndices().size
            println(
                "NOISE-PROBE $sampleDir: ${elements.elements.size} elements, keep=$keepCount, " +
                    "noise=${noise.elements.size} " +
                    byType.entries.joinToString(", ") { (t, v) -> "${t.name}=${v.size}" } +
                    "  →  ${markupFile.name} , ${cleanedFile.name}"
            )
        }
    }
}
