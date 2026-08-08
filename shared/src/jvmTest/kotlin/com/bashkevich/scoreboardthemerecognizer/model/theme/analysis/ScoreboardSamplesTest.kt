package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import nu.pattern.OpenCV
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Golden-master tests against the real samples under `image_tests/sample_N/`: each sample has an
 * `image_N`, a `boxes_N.json` (pixel-coord markup), and a `result_N.json` (the expected theme). The
 * test runs [measureComponentsColors] on the image + boxes and asserts every measured color is close
 * to the expected one. This catches the real failure modes the synthetic tests can't — e.g. a tight
 * box on a digit inverting background/text.
 *
 * Boxes in `boxes_N.json` are in PIXELS, so they are normalized by the decoded image size first, the
 * same way the debug screen does it.
 */
class ScoreboardSamplesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Tolerance is loose on purpose: broadcast screenshots carry JPEG compression + anti-aliasing, so
    // the measured solid color and a hand-picked reference pixel can differ by ~50-85 while still
    // being the same color. The test's job is to catch inversions / wrong hues (distance > 100), not
    // to pin exact shades.
    private val tolerance = 90.0

    @Test
    fun sample_1() = checkSample("sample_1")

    @Test
    fun sample_2() = checkSample("sample_2")

    @Test
    fun sample_3() = checkSample("sample_3")

    private fun checkSample(sampleDir: String) {
        val base = projectRoot().resolve("image_tests").resolve(sampleDir)
        if (!base.exists()) {
            println("SKIP $sampleDir: fixtures not found at $base")
            return
        }
        val imageFile = base.listFiles()!!.first { it.name.startsWith("image_") }
        val boxesFile = base.listFiles()!!.first { it.name.startsWith("boxes_") }
        val resultFile = base.listFiles()!!.first { it.name.startsWith("result_") }

        OpenCV.loadLocally()
        val bytes = imageFile.readBytes()
        val mat = decodeToMat(bytes)
        val width = mat.width()
        val height = mat.height()
        mat.release()

        val pxLayout = json.decodeFromString<AiComponentLayout>(boxesFile.readText())
        val normalized = normalizeByImage(pxLayout, width, height)
        val measured = runBlocking {
            measureComponentsColors(ImageFile(imageFile.name, bytes), normalized).toThemeContent()
        }
        val expected = json.decodeFromString<ThemeContent>(resultFile.readText())

        val failures = mutableListOf<String>()
        for ((name, m, e) in colorTriples(measured, expected)) {
            val d = parseHex(m.color).distanceTo(parseHex(e.color))
            if (d > tolerance) {
                failures += "$sampleDir / $name: measured=${m.color} expected=${e.color} distance=$d"
            }
        }
        assertTrue(failures.isEmpty(), "Theme mismatch:\n" + failures.joinToString("\n"))
    }

    private fun normalizeByImage(layout: AiComponentLayout, width: Int, height: Int): AiComponentLayout {
        val w = width.toDouble()
        val h = height.toDouble()
        return layout.copy(
            components = layout.components.map { ab ->
                ab.copy(
                    x = (ab.x / w).coerceIn(0.0, 1.0),
                    y = (ab.y / h).coerceIn(0.0, 1.0),
                    w = (ab.w / w).coerceIn(0.0, 1.0),
                    h = (ab.h / h).coerceIn(0.0, 1.0),
                )
            },
        )
    }

    private fun colorTriples(m: ThemeContent, e: ThemeContent): List<Triple<String, ThemeColorLike, ThemeColorLike>> =
        listOf(
            Triple("main_background_color", m.mainBackgroundColor, e.mainBackgroundColor),
            Triple("main_text_color", m.mainTextColor, e.mainTextColor),
            Triple("serve_color", m.serveColor, e.serveColor),
            Triple("previous_set_win_text_color", m.previousSetWinTextColor, e.previousSetWinTextColor),
            Triple("previous_set_lose_text_color", m.previousSetLoseTextColor, e.previousSetLoseTextColor),
            Triple("current_set_background_color", m.currentSetBackgroundColor, e.currentSetBackgroundColor),
            Triple("current_set_text_color", m.currentSetTextColor, e.currentSetTextColor),
            Triple("current_game_background_color", m.currentGameBackgroundColor, e.currentGameBackgroundColor),
            Triple("current_game_text_color", m.currentGameTextColor, e.currentGameTextColor),
        )

    private fun parseHex(hex: String): RgbColor {
        val v = hex.removePrefix("#")
        return RgbColor(
            r = v.substring(0, 2).toInt(16),
            g = v.substring(2, 4).toInt(16),
            b = v.substring(4, 6).toInt(16),
        )
    }
}

/** Local alias so the comparison list reads uniformly; it's just [com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeColor]. */
private typealias ThemeColorLike = com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeColor
