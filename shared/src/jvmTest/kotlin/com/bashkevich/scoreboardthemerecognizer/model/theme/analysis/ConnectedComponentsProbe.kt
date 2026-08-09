package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import nu.pattern.OpenCV
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.test.Test

/**
 * Prototype probe (v2): sweep the morphology-close size and report, per sample, how many clean
 * "element-like" components (digit/ball/letter sized, not lines, not giant blobs) the
 * connected-components approach yields. Goal: confirm elements isolate cleanly and pick a close size
 * that doesn't merge dense text (the sample_1 failure mode).
 */
class ConnectedComponentsProbe {

    @Test
    fun probe() {
        OpenCV.loadLocally()
        for (sampleDir in listOf("sample_1", "sample_2", "sample_3")) {
            val base = projectRoot().resolve("image_tests").resolve(sampleDir)
            if (!base.exists()) {
                println("CC-PROBE $sampleDir: fixtures not found, skip")
                continue
            }
            val bytes = base.listFiles()!!.first { it.name.startsWith("image_") }.readBytes()
            for (close in listOf(0, 3, 5)) {
                val color = decodeToMat(bytes)
                try {
                    val summary = sweep(color, close)
                    println("CC-PROBE $sampleDir close=$close → ${summary.first}")
                } finally {
                    color.release()
                }
            }
        }
    }

    private data class Comp(val left: Int, val top: Int, val w: Int, val h: Int, val area: Int)

    private fun sweep(color: Mat, close: Int): Pair<String, List<Comp>> {
        val width = color.width()
        val height = color.height()
        val totalArea = width * height

        val gray = Mat()
        Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY)
        val mask = textForegroundMask(gray)
        gray.release()
        if (close > 0) {
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(close.toDouble(), close.toDouble()))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k)
            k.release()
        }

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8)
        labels.release()
        centroids.release()

        val raw = (1 until n).map { i ->
            Comp(
                left = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt(),
                top = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt(),
                w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt(),
                h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt(),
                area = stats.get(i, Imgproc.CC_STAT_AREA)[0].toInt(),
            )
        }
        stats.release()
        mask.release()

        // "element-like": not noise, not a giant blob, not a thin line.
        fun Comp.isElement(): Boolean {
            if (area < 25 || area > totalArea * 0.20) return false
            if (w > 4 * h || h > 4 * w) return false // thin lines / separators
            return w in 6..60 && h in 6..40
        }
        fun Comp.isGiant() = area > totalArea * 0.30
        val elements = raw.filter { it.isElement() }
        val giants = raw.count { it.isGiant() }

        // number the elements in reading order on the image (for the PNG / LLM)
        val ordered = elements.sortedWith(compareBy({ it.top }, { it.left }))
        ordered.forEachIndexed { idx, c -> println("    [$idx] box=[${c.left},${c.top} ${c.w}x${c.h}] area=${c.area}") }

        return ("${elements.size} elements, ${raw.size} raw, $giants giant blob(s)") to ordered
    }
}
