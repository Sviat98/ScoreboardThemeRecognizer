package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import kotlinx.coroutines.runBlocking
import nu.pattern.OpenCV
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the full Stage-1 OpenCV pipeline at runtime (native load → decode → contour ROI
 * detection → K-Means → palette → [ThemeRecognizer] console report) against a synthetic
 * scoreboard image built with OpenCV itself, so no binary test fixture is needed.
 */
class ScoreboardImageAnalyzerTest {

    @Test
    fun extractsBackgroundAndTextColorsFromSyntheticScoreboard() {
        OpenCV.loadLocally()

        // #142C6C as BGR, plus white "digits".
        val pngBytes = buildSyntheticScoreboardPng(
            backgroundBgr = Scalar(108.0, 44.0, 20.0),
            textBgr = Scalar(255.0, 255.0, 255.0),
        )

        val palette = runBlocking { analyzeScoreboardImage(ImageFile("synthetic.png", pngBytes)) }

        println(
            "TEST result: bg=${palette.backgroundColor.toHex()} text=${palette.textColor.toHex()} " +
                "fallback=${palette.usedWholeImageFallback} accents=${palette.accents.map { it.toHex() }}"
        )

        assertFalse(palette.usedWholeImageFallback, "Expected contour-based ROI detection, not the whole-image fallback")

        val expectedBackground = RgbColor(20, 44, 108) // #142C6C
        val expectedText = RgbColor(255, 255, 255)
        assertTrue(
            palette.backgroundColor.distanceTo(expectedBackground) < 30.0,
            "Background ${palette.backgroundColor.toHex()} should be close to ${expectedBackground.toHex()}",
        )
        assertTrue(
            palette.textColor.distanceTo(expectedText) < 30.0,
            "Text ${palette.textColor.toHex()} should be close to white",
        )

        val theme = palette.toThemeContent()
        assertEquals(palette.backgroundColor.toHex(), theme.mainBackgroundColor.color)
        assertEquals(palette.textColor.toHex(), theme.mainTextColor.color)
    }

    /** Paints a solid background and a small cluster of filled rectangles ("score digits"). */
    private fun buildSyntheticScoreboardPng(backgroundBgr: Scalar, textBgr: Scalar): ByteArray {
        val mat = Mat(400, 600, CvType.CV_8UC3, backgroundBgr)
        val digits = listOf(
            Rect(250, 150, 25, 40),
            Rect(285, 150, 25, 40),
            Rect(320, 150, 18, 40),
            Rect(250, 210, 25, 40),
            Rect(285, 210, 25, 40),
        )
        for (r in digits) {
            Imgproc.rectangle(
                mat,
                Point(r.x.toDouble(), r.y.toDouble()),
                Point((r.x + r.width).toDouble(), (r.y + r.height).toDouble()),
                textBgr,
                -1,
            )
        }
        val buffer = MatOfByte()
        Imgcodecs.imencode(".png", mat, buffer)
        mat.release()
        return buffer.toArray().also { buffer.release() }
    }
}
