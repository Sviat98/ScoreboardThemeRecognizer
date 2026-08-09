package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import org.opencv.core.MatOfByte
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

// Distinct BGR colors, one per element index (cycles after 12).
private val DEBUG_PALETTE = listOf(
    Scalar(0.0, 0.0, 255.0), // red
    Scalar(0.0, 255.0, 0.0), // green
    Scalar(255.0, 0.0, 0.0), // blue
    Scalar(0.0, 255.0, 255.0), // yellow
    Scalar(255.0, 255.0, 0.0), // cyan
    Scalar(255.0, 0.0, 255.0), // magenta
    Scalar(0.0, 128.0, 255.0), // orange
    Scalar(255.0, 128.0, 0.0), // teal
    Scalar(128.0, 0.0, 255.0), // pink
    Scalar(0.0, 255.0, 128.0), // lime
    Scalar(128.0, 128.0, 0.0), // olive
    Scalar(200.0, 200.0, 200.0), // gray
)

private const val DEBUG_RECT_THICKNESS = 3

internal actual fun renderElementsDebugOverlay(image: ImageFile, elements: ScoreboardElements): ByteArray {
    ensureOpenCvLoaded()
    val color = decodeToMat(image.content)
    try {
        for (e in elements.elements) {
            val r = e.rect
            val c = DEBUG_PALETTE[e.index % DEBUG_PALETTE.size]
            Imgproc.rectangle(
                color,
                Point(r.x.toDouble(), r.y.toDouble()),
                Point((r.x + r.width).toDouble(), (r.y + r.height).toDouble()),
                c,
                DEBUG_RECT_THICKNESS,
            )
        }
        val buffer = MatOfByte()
        Imgcodecs.imencode(".png", color, buffer)
        return buffer.toArray().also { buffer.release() }
    } finally {
        color.release()
    }
}
