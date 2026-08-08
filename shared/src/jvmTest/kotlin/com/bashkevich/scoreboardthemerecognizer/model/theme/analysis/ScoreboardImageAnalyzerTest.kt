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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the zone-based pipeline at runtime against synthetic scoreboards built with OpenCV
 * (no binary fixture needed). Layout left→right: names+serve · previous sets · current set ·
 * current game, separated by empty vertical gaps.
 */
class ScoreboardImageAnalyzerTest {

    private val backgroundBgr = Scalar(87.0, 46.0, 34.0) // #222E57
    private val expectedBackground = RgbColor(0x22, 0x2E, 0x57)
    private val whiteBgr = Scalar(255.0, 255.0, 255.0)
    private val grayBgr = Scalar(170.0, 170.0, 170.0)
    private val yellowBgr = Scalar(0.0, 255.0, 255.0) // RGB yellow = BGR(0,255,255)

    private val white = RgbColor(255, 255, 255)
    private val gray = RgbColor(170, 170, 170)
    private val yellow = RgbColor(255, 255, 0)

    @Test
    fun extractsColorsPerZoneFromSyntheticScoreboard() {
        OpenCV.loadLocally()
        val pngBytes = encodePng(newScoreboardCanvas(640, 160, fillMargin = null))
        val result = runBlocking { analyzeScoreboardImage(ImageFile("synthetic.png", pngBytes)) }

        println("TEST no-margin: blocks=${result.detectedBlocks.size} cropped=${result.croppedTo}")
        assertNull(result.croppedTo, "No surrounding margin → should not crop")
        assertZonesExtractedCorrectly(result)
    }

    @Test
    fun trimsSurroundingBackgroundThenAnalyzesZones() {
        OpenCV.loadLocally()
        // 800×300 canvas filled with a green margin; scoreboard (640×160) placed at (80, 60).
        val canvas = Mat(300, 800, CvType.CV_8UC3, Scalar(40.0, 200.0, 80.0)) // green margin
        Imgproc.rectangle(canvas, Point(80.0, 60.0), Point(720.0, 220.0), backgroundBgr, -1)
        drawScoreboardContent(canvas, offsetX = 80, offsetY = 60)
        val pngBytes = encodePng(canvas)

        val result = runBlocking { analyzeScoreboardImage(ImageFile("with_margin.png", pngBytes)) }

        println("TEST with-margin: cropped=${result.croppedTo} blocks=${result.detectedBlocks.size}")

        val crop = result.croppedTo
        assertNotNull(crop, "Surrounding margin present → background should be trimmed")
        assertEquals(80, crop.x, 12, "crop x=${crop.x}")
        assertEquals(60, crop.y, 12, "crop y=${crop.y}")
        assertEquals(640, crop.width, 12, "crop width=${crop.width}")
        assertEquals(160, crop.height, 12, "crop height=${crop.height}")

        assertZonesExtractedCorrectly(result)
    }

    @Test
    fun mapsFromRightWithoutPreviousSetsColumn() {
        OpenCV.loadLocally()
        // Early match: only names+serve, current set, current game (no completed sets).
        val mat = Mat(160, 520, CvType.CV_8UC3, backgroundBgr)
        drawRect(mat, 30, 40, 120, 20, whiteBgr)
        drawRect(mat, 30, 100, 120, 20, whiteBgr)
        drawRect(mat, 156, 44, 14, 14, yellowBgr)
        drawRect(mat, 260, 40, 40, 20, whiteBgr)
        drawRect(mat, 260, 100, 40, 20, whiteBgr)
        drawRect(mat, 420, 40, 40, 20, whiteBgr)
        drawRect(mat, 420, 100, 40, 20, whiteBgr)
        val pngBytes = encodePng(mat)

        val result = runBlocking { analyzeScoreboardImage(ImageFile("no_prev_sets.png", pngBytes)) }
        println("TEST no-prev-sets: blocks=${result.detectedBlocks.size} zones=${result.zones.map { it.kind.name }}")

        assertFalse(result.usedFallback, "Three columns should map right-anchored, not fall back to equal quarters")
        assertNull(result.zone(ScoreboardZoneKind.PREVIOUS_SETS), "No completed sets → previous-sets zone should be absent")
        assertColorClose("names.background", result.zone(ScoreboardZoneKind.NAMES_AND_SERVE)?.background, expectedBackground)
        assertColorClose("names.serve", result.zone(ScoreboardZoneKind.NAMES_AND_SERVE)?.serve, yellow)
        assertColorClose("currentSet.background", result.zone(ScoreboardZoneKind.CURRENT_SET)?.background, expectedBackground)
        assertColorClose("currentGame.background", result.zone(ScoreboardZoneKind.CURRENT_GAME)?.background, expectedBackground)
    }

    @Test
    fun separatesColumnsAcrossMultiColorBackgroundBands() {
        OpenCV.loadLocally()
        // Scoreboard whose columns have different background colors: dark-green names/prev-sets,
        // a light-green current-set band, a blue current-game band. Surrounded by a gray margin.
        val offsetX = 80
        val offsetY = 60
        val canvas = Mat(300, 800, CvType.CV_8UC3, Scalar(200.0, 200.0, 200.0)) // gray margin
        Imgproc.rectangle(canvas, Point(offsetX.toDouble(), offsetY.toDouble()), Point((offsetX + 640).toDouble(), (offsetY + 160).toDouble()), backgroundBgr, -1)
        // light-green band (BGR) over the current-set region
        Imgproc.rectangle(canvas, Point((offsetX + 380).toDouble(), offsetY.toDouble()), Point((offsetX + 500).toDouble(), (offsetY + 160).toDouble()), Scalar(140.0, 200.0, 120.0), -1)
        // blue band (BGR) over the current-game region
        Imgproc.rectangle(canvas, Point((offsetX + 520).toDouble(), offsetY.toDouble()), Point((offsetX + 640).toDouble(), (offsetY + 160).toDouble()), Scalar(180.0, 80.0, 40.0), -1)
        // names + serve
        drawRect(canvas, offsetX + 30, offsetY + 40, 120, 20, whiteBgr)
        drawRect(canvas, offsetX + 30, offsetY + 100, 120, 20, whiteBgr)
        drawRect(canvas, offsetX + 156, offsetY + 44, 14, 14, yellowBgr)
        // previous sets (white won / gray lost), still on dark green
        drawRect(canvas, offsetX + 250, offsetY + 40, 30, 20, whiteBgr)
        drawRect(canvas, offsetX + 250, offsetY + 100, 30, 20, whiteBgr)
        drawRect(canvas, offsetX + 310, offsetY + 40, 30, 20, grayBgr)
        drawRect(canvas, offsetX + 310, offsetY + 100, 30, 20, grayBgr)
        // current set text on the light-green band
        drawRect(canvas, offsetX + 410, offsetY + 40, 40, 20, whiteBgr)
        drawRect(canvas, offsetX + 410, offsetY + 100, 40, 20, whiteBgr)
        // current game text on the blue band
        drawRect(canvas, offsetX + 555, offsetY + 40, 30, 20, whiteBgr)
        drawRect(canvas, offsetX + 555, offsetY + 100, 30, 20, whiteBgr)
        val pngBytes = encodePng(canvas)

        val result = runBlocking { analyzeScoreboardImage(ImageFile("multicolor.png", pngBytes)) }
        println(
            "TEST multicolor: cropped=${result.croppedTo} blocks=${result.detectedBlocks.size} " +
                "zones=${result.zones.map { it.kind.name }}"
        )

        assertTrue(result.detectedBlocks.size >= 4, "Multi-color bands must not merge; got ${result.detectedBlocks.size} blocks")
        assertFalse(result.usedFallback)

        val lightGreen = RgbColor(120, 200, 140)
        val blue = RgbColor(40, 80, 180)

        assertColorClose("names.background", result.zone(ScoreboardZoneKind.NAMES_AND_SERVE)?.background, expectedBackground)
        assertColorClose("names.text", result.zone(ScoreboardZoneKind.NAMES_AND_SERVE)?.primaryText, white)
        assertColorClose("names.serve", result.zone(ScoreboardZoneKind.NAMES_AND_SERVE)?.serve, yellow)
        assertColorClose("currentSet.background", result.zone(ScoreboardZoneKind.CURRENT_SET)?.background, lightGreen)
        assertColorClose("currentGame.background", result.zone(ScoreboardZoneKind.CURRENT_GAME)?.background, blue)
    }

    /** Asserts all four zones were detected with the expected colors. */
    private fun assertZonesExtractedCorrectly(result: ScoreboardZones) {
        assertTrue(result.detectedBlocks.size >= 4, "Expected ≥4 content columns, got ${result.detectedBlocks.size}")
        assertFalse(result.usedFallback, "Expected column auto-detection, not the equal-quarters fallback")

        val names = result.zone(ScoreboardZoneKind.NAMES_AND_SERVE)
        val previousSets = result.zone(ScoreboardZoneKind.PREVIOUS_SETS)
        val currentSet = result.zone(ScoreboardZoneKind.CURRENT_SET)
        val currentGame = result.zone(ScoreboardZoneKind.CURRENT_GAME)

        // Zone 1: background / text / serve.
        assertColorClose("names.background", names?.background, expectedBackground)
        assertColorClose("names.text", names?.primaryText, white)
        assertColorClose("names.serve", names?.serve, yellow)

        // Zone 2: two text colors (white + gray), order irrelevant.
        val previousTextColors = listOfNotNull(previousSets?.winText, previousSets?.loseText)
        assertTrue(previousTextColors.any { it.distanceTo(white) < 30 }, "Previous-sets should contain white text, got $previousTextColors")
        assertTrue(previousTextColors.any { it.distanceTo(gray) < 30 }, "Previous-sets should contain gray text, got $previousTextColors")

        // Zones 3 & 4: background + text.
        assertColorClose("currentSet.background", currentSet?.background, expectedBackground)
        assertColorClose("currentSet.text", currentSet?.primaryText, white)
        assertColorClose("currentGame.background", currentGame?.background, expectedBackground)
        assertColorClose("currentGame.text", currentGame?.primaryText, white)

        val theme = result.toThemeContent()
        assertEquals(names!!.background!!.toHex(), theme.mainBackgroundColor.color)
        assertEquals(names.primaryText!!.toHex(), theme.mainTextColor.color)
        assertEquals(names.serve!!.toHex(), theme.serveColor.color)
    }

    private fun assertColorClose(label: String, actual: RgbColor?, expected: RgbColor, tolerance: Double = 30.0) {
        assertNotNull(actual, "$label was not detected")
        val distance = actual.distanceTo(expected)
        assertTrue(distance < tolerance, "$label ${actual.toHex()} should be close to ${expected.toHex()} (distance=$distance)")
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int, message: String) {
        assertTrue(abs(expected - actual) <= tolerance, "$message differs from expected $expected by more than $tolerance")
    }

    private fun ScoreboardZones.zone(kind: ScoreboardZoneKind): ZoneAnalysis? = zones.firstOrNull { it.kind == kind }

    private fun newScoreboardCanvas(width: Int, height: Int, fillMargin: Scalar?): Mat {
        val mat = if (fillMargin != null) Mat(height, width, CvType.CV_8UC3, fillMargin) else Mat(height, width, CvType.CV_8UC3, backgroundBgr)
        drawScoreboardContent(mat, offsetX = 0, offsetY = 0)
        return mat
    }

    /** Draws the four-zone scoreboard content (names+serve, prev sets, current set, current game) at the offset. */
    private fun drawScoreboardContent(mat: Mat, offsetX: Int, offsetY: Int) {
        // Zone 1 (x ~30..170): player names (white) + a small serve dot (yellow).
        drawRect(mat, offsetX + 30, offsetY + 40, 120, 20, whiteBgr)
        drawRect(mat, offsetX + 30, offsetY + 100, 120, 20, whiteBgr)
        drawRect(mat, offsetX + 156, offsetY + 44, 14, 14, yellowBgr)

        // Zone 2 (x ~250..340): previous-set scores, white (won) + gray (lost).
        drawRect(mat, offsetX + 250, offsetY + 40, 30, 20, whiteBgr)
        drawRect(mat, offsetX + 250, offsetY + 100, 30, 20, whiteBgr)
        drawRect(mat, offsetX + 310, offsetY + 40, 30, 20, grayBgr)
        drawRect(mat, offsetX + 310, offsetY + 100, 30, 20, grayBgr)

        // Zone 3 (x ~430..470): current set, white on background.
        drawRect(mat, offsetX + 430, offsetY + 40, 40, 20, whiteBgr)
        drawRect(mat, offsetX + 430, offsetY + 100, 40, 20, whiteBgr)

        // Zone 4 (x ~590..620): current game, white on background.
        drawRect(mat, offsetX + 590, offsetY + 40, 30, 20, whiteBgr)
        drawRect(mat, offsetX + 590, offsetY + 100, 30, 20, whiteBgr)
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
}
