package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeColor
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nu.pattern.OpenCV
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Golden-master tests for the connected-components path's deterministic half. On each
 * `image_tests/sample_N`: `detectScoreboardElements` must find the elements, and
 * `measureScoreboardElements` must measure every color close to `result_N.json`. Since the LLM can't
 * run in a unit test, role→element selection is simulated by matching each expected box from
 * `boxes_N.json` to its nearest detected element (exactly what the LLM does semantically).
 */
class ScoreboardElementsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val tolerance = 90.0

    @Test
    fun sample_1() = checkSample("sample_1")

    @Test
    fun sample_2() = checkSample("sample_2")

    @Test
    fun sample_3() = checkSample("sample_3")

    @Serializable private data class BoxEntry(val role: String, val x: Double, val y: Double, val w: Double, val h: Double)
    @Serializable private data class BoxFile(val components: List<BoxEntry> = emptyList())

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
        val elements = runBlocking { detectScoreboardElements(ImageFile(imageFile.name, bytes)) }
        println("TEST $sampleDir: ${elements.elements.size} elements")
        assertTrue(elements.elements.isNotEmpty(), "$sampleDir: no elements detected")

        val boxFile = json.decodeFromString<BoxFile>(boxesFile.readText())
        val roles = matchRoles(elements.elements, boxFile)
        fun rb(idx: Int?): String = idx?.let { elements.elements.getOrNull(it)?.rect?.let { r -> "[$idx]=[${r.x},${r.y} ${r.width}x${r.height}]" } } ?: "[$idx]=null"
        println(
            "  matched main_text=${rb(roles.mainTextElement)} serve=${rb(roles.serveElement)} " +
                "win=${rb(roles.prevSetWinElement)} lose=${rb(roles.prevSetLoseElement)} " +
                "cset=${rb(roles.currentSetElement)} cgame=${rb(roles.currentGameElement)}"
        )
        val measured = runBlocking {
            measureScoreboardElements(ImageFile(imageFile.name, bytes), elements, roles).toThemeContent()
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

    /** Simulates the LLM: each role's expected box → the nearest detected element's index. */
    private fun matchRoles(elements: List<ScoreboardElement>, boxFile: BoxFile): ElementRoles {
        fun nearest(role: String): Int? {
            val entry = boxFile.components.firstOrNull { it.role == role } ?: return null
            val cx = entry.x + entry.w / 2.0
            val cy = entry.y + entry.h / 2.0
            return elements.minByOrNull { e ->
                val ex = e.rect.x + e.rect.width / 2.0
                val ey = e.rect.y + e.rect.height / 2.0
                (ex - cx) * (ex - cx) + (ey - cy) * (ey - cy)
            }?.index
        }
        return ElementRoles(
            mainTextElement = nearest("main_text"),
            serveElement = nearest("serve"),
            prevSetWinElement = nearest("prev_set_win"),
            prevSetLoseElement = nearest("prev_set_lose"),
            currentSetElement = nearest("current_set"),
            currentGameElement = nearest("current_game"),
        )
    }

    private fun colorTriples(m: ThemeContent, e: ThemeContent): List<Triple<String, ThemeColor, ThemeColor>> =
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
