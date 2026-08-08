package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import kotlinx.coroutines.runBlocking
import nu.pattern.OpenCV
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the LLM→OpenCV measurement path against synthetic scoreboards (no LLM, no network): the
 * [AiComponentLayout] is built by hand with normalized boxes that carry deliberate drift, to verify
 * the snap + histogram-mode + background-distance logic. Mirrors `ScoreboardColorExtractorTest` from
 * TennisScoreKeeperBackend, including the "navy is not averaged into black" invariant.
 */
class ScoreboardColorMeasurerTest {

    private val backgroundBgr = Scalar(87.0, 46.0, 34.0) // #222E57
    private val expectedBackground = RgbColor(0x22, 0x2E, 0x57)
    private val whiteBgr = Scalar(255.0, 255.0, 255.0)
    private val grayBgr = Scalar(170.0, 170.0, 170.0)
    private val yellowBgr = Scalar(0.0, 255.0, 255.0) // RGB yellow = BGR(0,255,255)

    private val white = RgbColor(255, 255, 255)
    private val gray = RgbColor(170, 170, 170)
    private val yellow = RgbColor(255, 255, 0)

    @Test
    fun measuresColorsFromApproximateBoxes() {
        OpenCV.loadLocally()
        val mat = Mat(160, 640, CvType.CV_8UC3, backgroundBgr)
        drawNarrowGlyphs(mat)
        val png = encodePng(mat)
        val layout = AiComponentLayout(
            isScoreboard = true,
            components = listOf(
                box("main_text", 18, 42, 60, 40),
                box("serve", 116, 42, 40, 36),
                box("prev_set_win", 188, 42, 50, 40),
                box("prev_set_lose", 268, 42, 50, 40),
                box("current_set", 348, 42, 50, 40),
                box("current_game", 428, 42, 50, 40),
            ),
        )
        val result = runBlocking { measureComponentsColors(ImageFile("synthetic.png", png), layout) }
        println("TEST measure: " + result.components.joinToString(" ") { "${it.role}=${it.background?.toHex()}/${it.text?.toHex()}" })

        assertColorClose("main bg", result.component(ComponentRole.MAIN_TEXT)?.background, expectedBackground)
        assertColorClose("main text", result.component(ComponentRole.MAIN_TEXT)?.text, white)
        assertColorClose("serve", result.component(ComponentRole.SERVE)?.text, yellow)
        assertColorClose("prev win", result.component(ComponentRole.PREV_SET_WIN)?.text, white)
        assertColorClose("prev lose", result.component(ComponentRole.PREV_SET_LOSE)?.text, gray)
        assertColorClose("current set bg", result.component(ComponentRole.CURRENT_SET)?.background, expectedBackground)
        assertColorClose("current set text", result.component(ComponentRole.CURRENT_SET)?.text, white)
        assertColorClose("current game bg", result.component(ComponentRole.CURRENT_GAME)?.background, expectedBackground)
        assertColorClose("current game text", result.component(ComponentRole.CURRENT_GAME)?.text, white)

        val theme = result.toThemeContent()
        assertTrue(theme.mainBackgroundColor.color == expectedBackground.toHex(), "theme main bg should map exactly")
        assertTrue(theme.mainTextColor.color == white.toHex(), "theme main text should map exactly")
    }

    @Test
    fun snapsBoxDriftToGlyph() {
        OpenCV.loadLocally()
        val mat = Mat(160, 640, CvType.CV_8UC3, backgroundBgr)
        drawNarrowGlyphs(mat)
        // Deliberately oversized and shifted boxes; the snap + background-distance must still recover
        // the navy background and the white main glyph.
        val png = encodePng(mat)
        val layout = AiComponentLayout(
            isScoreboard = true,
            components = listOf(
                box("main_text", 0, 30, 110, 60),
                box("serve", 100, 38, 60, 50),
                box("prev_set_win", 170, 30, 90, 60),
                box("prev_set_lose", 250, 30, 90, 60),
                box("current_set", 330, 30, 90, 60),
                box("current_game", 410, 30, 90, 60),
            ),
        )
        val result = runBlocking { measureComponentsColors(ImageFile("drift.png", png), layout) }
        assertColorClose("main bg (drift)", result.component(ComponentRole.MAIN_TEXT)?.background, expectedBackground)
        assertColorClose("main text (drift)", result.component(ComponentRole.MAIN_TEXT)?.text, white)
    }

    @Test
    fun navyBackgroundIsNotAveragedIntoBlack() {
        OpenCV.loadLocally()
        val navyBgr = Scalar(60.0, 26.0, 10.0) // RGB #0A1A3C -> BGR(60,26,10)
        val navy = RgbColor(0x0A, 0x1A, 0x3C)
        val mat = Mat(160, 640, CvType.CV_8UC3, navyBgr)
        drawNarrowGlyphs(mat)
        val png = encodePng(mat)
        val layout = AiComponentLayout(
            isScoreboard = true,
            components = listOf(
                box("main_text", 18, 42, 60, 40),
                box("serve", 116, 42, 40, 36),
                box("prev_set_win", 188, 42, 50, 40),
                box("prev_set_lose", 268, 42, 50, 40),
                box("current_set", 348, 42, 50, 40),
                box("current_game", 428, 42, 50, 40),
            ),
        )
        val result = runBlocking { measureComponentsColors(ImageFile("navy.png", png), layout) }
        val bg = result.component(ComponentRole.MAIN_TEXT)?.background
        assertNotNull(bg, "main background should be detected")
        assertTrue(
            bg.distanceTo(navy) < 30.0,
            "background ${bg.toHex()} should be navy ${navy.toHex()} (distance=${bg.distanceTo(navy)})"
        )
        assertTrue(
            bg.distanceTo(RgbColor.BLACK) > 30.0,
            "background ${bg.toHex()} must NOT collapse to black (distance-to-black=${bg.distanceTo(RgbColor.BLACK)})"
        )
    }

    @Test
    fun emptyLayoutFallsBackToDefaults() {
        OpenCV.loadLocally()
        val mat = Mat(160, 640, CvType.CV_8UC3, backgroundBgr)
        val png = encodePng(mat)
        val layout = AiComponentLayout(isScoreboard = true, components = emptyList())
        val result = runBlocking { measureComponentsColors(ImageFile("empty.png", png), layout) }
        val theme = result.toThemeContent()
        assertTrue(theme.mainBackgroundColor.color == RgbColor.BLACK.toHex(), "no components → default black background")
        assertTrue(theme.mainTextColor.color == RgbColor.WHITE.toHex(), "no components → default white text")
    }

    // Pixel rect (640×160 canvas) → normalized box.
    private fun box(role: String, x: Int, y: Int, w: Int, h: Int): AiBox =
        AiBox(role = role, x = x / 640.0, y = y / 160.0, w = w / 640.0, h = h / 160.0)

    /** Six narrow single-symbol glyphs (the "one symbol + background" contract); the Mat is pre-filled. */
    private fun drawNarrowGlyphs(mat: Mat) {
        drawRect(mat, 40, 50, 16, 24, whiteBgr) // main letter
        drawRect(mat, 130, 52, 14, 14, yellowBgr) // serve dot
        drawRect(mat, 210, 50, 16, 24, whiteBgr) // prev set win
        drawRect(mat, 290, 50, 16, 24, grayBgr) // prev set lose
        drawRect(mat, 370, 50, 16, 24, whiteBgr) // current set
        drawRect(mat, 450, 50, 16, 24, whiteBgr) // current game
    }

    private fun drawRect(mat: Mat, x: Int, y: Int, w: Int, h: Int, color: Scalar) {
        Imgproc.rectangle(
            mat,
            Point(x.toDouble(), y.toDouble()),
            Point((x + w).toDouble(), (y + h).toDouble()),
            color,
            -1,
        )
    }

    private fun encodePng(mat: Mat): ByteArray {
        val buffer = MatOfByte()
        Imgcodecs.imencode(".png", mat, buffer)
        mat.release()
        return buffer.toArray().also { buffer.release() }
    }

    private fun assertColorClose(label: String, actual: RgbColor?, expected: RgbColor, tolerance: Double = 30.0) {
        assertNotNull(actual, "$label was not detected")
        val distance = actual.distanceTo(expected)
        assertTrue(distance < tolerance, "$label ${actual.toHex()} should be close to ${expected.toHex()} (distance=$distance)")
    }
}
