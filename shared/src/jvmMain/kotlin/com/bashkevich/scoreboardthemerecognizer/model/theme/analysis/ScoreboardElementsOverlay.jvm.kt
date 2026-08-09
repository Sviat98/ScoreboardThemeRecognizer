package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import org.opencv.core.MatOfByte
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

private val NUMBER_BOX = Scalar(0.0, 255.0, 255.0) // yellow outline
private val NUMBER_OUTLINE = Scalar(0.0, 0.0, 0.0) // black thick outline behind the white digit
private val NUMBER_FILL = Scalar(255.0, 255.0, 255.0) // white digit

internal actual fun renderNumberedElementsOverlay(image: ImageFile, elements: ScoreboardElements): ByteArray {
    ensureOpenCvLoaded()
    val color = decodeToMat(image.content)
    try {
        for (e in elements.elements) {
            val r = e.rect
            val x2 = r.x + r.width
            val y2 = r.y + r.height
            Imgproc.rectangle(color, Point(r.x.toDouble(), r.y.toDouble()), Point(x2.toDouble(), y2.toDouble()), NUMBER_BOX, 2)
            val labelPos = Point((r.x + 2).toDouble(), (r.y + 15.0).coerceAtMost((y2 - 2).toDouble().coerceAtLeast(r.y + 12.0)))
            // thick black then thin white so the number is legible on any background
            Imgproc.putText(color, e.index.toString(), labelPos, Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, NUMBER_OUTLINE, 3)
            Imgproc.putText(color, e.index.toString(), labelPos, Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, NUMBER_FILL, 1)
        }
        val buffer = MatOfByte()
        Imgcodecs.imencode(".png", color, buffer)
        return buffer.toArray().also { buffer.release() }
    } finally {
        color.release()
    }
}
