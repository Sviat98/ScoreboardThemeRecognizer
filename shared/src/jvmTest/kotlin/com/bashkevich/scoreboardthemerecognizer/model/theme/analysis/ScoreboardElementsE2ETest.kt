package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeColor
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import nu.pattern.OpenCV
import kotlin.test.Test

/**
 * End-to-end test of the connected-components path WITH the real LLM: detect elements → render the
 * numbered overlay → ElementThemeAgent (GPT-4o) assigns roles → measure → compare to result_N.
 *
 * Gated on `OPENAI_API_KEY`: skipped (not failed) when the key is absent, so this never runs in a
 * normal keyless CI build. Run manually:
 * `./gradlew :shared:jvmTest --tests "*ScoreboardElementsE2ETest"` (with the key in the env).
 */
class ScoreboardElementsE2ETest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val agent = ElementThemeAgent()
    private val tolerance = 90.0

    @Test
    fun sample_1() = e2e("sample_1")

    @Test
    fun sample_2() = e2e("sample_2")

    @Test
    fun sample_3() = e2e("sample_3")

    private fun e2e(sampleDir: String) {
        if (loadOpenAIApiKey().isNullOrBlank()) {
            println("SKIP E2E $sampleDir: OPENAI_API_KEY not set (env or openai.key)")
            return
        }
        val base = projectRoot().resolve("image_tests").resolve(sampleDir)
        if (!base.exists()) {
            println("SKIP E2E $sampleDir: fixtures not found")
            return
        }
        val imageFile = base.listFiles()!!.first { it.name.startsWith("image_") }
        val resultFile = base.listFiles()!!.first { it.name.startsWith("result_") }

        OpenCV.loadLocally()
        val image = ImageFile(imageFile.name, imageFile.readBytes())
        val elements = runBlocking { detectScoreboardElements(image) }
        println("E2E $sampleDir: ${elements.elements.size} elements detected")

        val numberedPng = renderNumberedElementsOverlay(image, elements)
        val roles = runBlocking { agent.localizeRoles(numberedPng) }
        println(
            "E2E $sampleDir LLM roles: isScoreboard=${roles.isScoreboard} " +
                "main=${roles.mainTextElement} serve=${roles.serveElement} " +
                "win=${roles.prevSetWinElement} lose=${roles.prevSetLoseElement} " +
                "cset=${roles.currentSetElement} cgame=${roles.currentGameElement}"
        )
        if (!roles.isScoreboard) {
            println("E2E $sampleDir: LLM said NOT a scoreboard (${roles.reason})")
            return
        }

        val measured = runBlocking { measureScoreboardElements(image, elements, roles).toThemeContent() }
        val expected = json.decodeFromString<ThemeContent>(resultFile.readText())

        val failures = mutableListOf<String>()
        for ((name, m, e) in colorTriples(measured, expected)) {
            val d = parseHex(m.color).distanceTo(parseHex(e.color))
            val mark = if (d > tolerance) " ✗" else ""
            println("E2E $sampleDir / $name: measured=${m.color} expected=${e.color} distance=$d$mark")
            if (d > tolerance) failures += "$sampleDir/$name measured=${m.color} expected=${e.color} d=$d"
        }
        check(failures.isEmpty()) {
            "E2E theme mismatch for $sampleDir (LLM-selected elements):\n" + failures.joinToString("\n")
        }
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
        return RgbColor(v.substring(0, 2).toInt(16), v.substring(2, 4).toInt(16), v.substring(4, 6).toInt(16))
    }
}
