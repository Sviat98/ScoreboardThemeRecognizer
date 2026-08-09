package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

private const val ELEMENT_MIN_AREA = 25
private const val ELEMENT_AREA_FRACTION = 0.20
private const val ELEMENT_MIN_W = 6
private const val ELEMENT_MAX_W = 60
private const val ELEMENT_MIN_H = 6
private const val ELEMENT_MAX_H = 40
private const val ELEMENT_MAX_ASPECT = 4.0

internal actual suspend fun detectScoreboardElements(image: ImageFile): ScoreboardElements =
    withContext(Dispatchers.Default) {
        ensureOpenCvLoaded()
        if (image.content.isEmpty()) error("Cannot analyze an empty image")
        val color = decodeToMat(image.content)
        try {
            val fullWidth = color.width()
            val fullHeight = color.height()
            val crop = detectScoreboardCrop(color)
            val isCropped = crop.x != 0 || crop.y != 0 ||
                crop.width != fullWidth || crop.height != fullHeight
            val work = if (isCropped) color.submat(crop) else color
            try {
                val totalArea = work.width() * work.height()

                val gray = Mat()
                Imgproc.cvtColor(work, gray, Imgproc.COLOR_BGR2GRAY)
                val mask = textForegroundMask(gray)
                gray.release()
                // Deliberately NO morphology close: it merges dense text on small scoreboards into
                // one giant blob (validated on the samples). Connected components on the raw mask
                // isolate individual glyphs cleanly.

                val labels = Mat()
                val stats = Mat()
                val centroids = Mat()
                val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8)
                labels.release()
                centroids.release()

                data class Blob(val left: Int, val top: Int, val w: Int, val h: Int, val area: Int)
                val maxArea = (totalArea * ELEMENT_AREA_FRACTION).toInt()
                val blobs = (1 until n).map { i ->
                    Blob(
                        left = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt(),
                        top = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt(),
                        w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt(),
                        h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt(),
                        area = stats.get(i, Imgproc.CC_STAT_AREA)[0].toInt(),
                    )
                }.filter { b ->
                    b.area in ELEMENT_MIN_AREA..maxArea &&
                        b.w in ELEMENT_MIN_W..ELEMENT_MAX_W &&
                        b.h in ELEMENT_MIN_H..ELEMENT_MAX_H &&
                        b.w <= ELEMENT_MAX_ASPECT * b.h &&
                        b.h <= ELEMENT_MAX_ASPECT * b.w
                }.sortedWith(compareBy({ it.top }, { it.left }))
                stats.release()
                mask.release()

                val ox = if (isCropped) crop.x else 0
                val oy = if (isCropped) crop.y else 0
                val elements = blobs.mapIndexed { idx, b ->
                    ScoreboardElement(idx, RoiRect(b.left + ox, b.top + oy, b.w, b.h))
                }

                ScoreboardElements(
                    imageWidth = fullWidth,
                    imageHeight = fullHeight,
                    elements = elements,
                    croppedTo = if (isCropped) RoiRect(crop.x, crop.y, crop.width, crop.height) else null,
                )
            } finally {
                if (isCropped) work.release()
            }
        } finally {
            color.release()
        }
    }
