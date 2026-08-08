package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nu.pattern.OpenCV
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.TermCriteria
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * JVM implementation of [analyzeScoreboardImage] using OpenCV.
 *
 * Stage 1 pipeline (no LLM):
 *  1. Load natives once, decode bytes into a BGR [Mat].
 *  2. Detect the scoreboard ROI: Otsu-threshold the grayscale image, pick the polarity whose
 *     foreground is the minority (text glyphs nearly always cover less area than background),
 *     find contours, and take the union bounding box of the sizeable ones. Expand + clamp it.
 *  3. If no text contours are found, fall back to the whole image (per product decision).
 *  4. K-Means (k=2) on the ROI → background (largest cluster) + text (other cluster).
 *  5. K-Means (k=5) on the ROI → accent candidates, filtered to colors distinct from bg/text.
 *  6. Print a diagnostic report tagged `[ThemeRecognizer]` and return the palette.
 *
 * Everything runs on [Dispatchers.Default] so the blocking OpenCV work never stalls the UI.
 */
internal actual suspend fun analyzeScoreboardImage(image: ImageFile): ScoreboardPalette =
    withContext(Dispatchers.Default) {
        ensureOpenCvLoaded()
        if (image.content.isEmpty()) error("Cannot analyze an empty image")

        val color = decodeToMat(image.content)
        try {
            analyzeMat(color)
        } finally {
            color.release()
        }
    }

private const val TAG = "[ThemeRecognizer]"

/** Min contour area (px) to count as a real glyph, not noise. */
private const val MIN_CONTOUR_AREA = 16.0

/** ROI expansion around the detected text bbox, as a fraction of bbox size. */
private const val ROI_EXPAND_FRACTION = 0.15

/** Two colors within this Euclidean distance are treated as the same. */
private const val COLOR_DEDUP_EPSILON = 25.0

/** Min distance from both background and text for an accent cluster to count as an accent. */
private const val ACCENT_MIN_DISTANCE = 40.0

private const val KMEANS_ATTEMPTS = 3

/** Loads the bundled OpenCV natives exactly once (thread-safe via `lazy`). */
private val openCvLoaded: Unit by lazy { OpenCV.loadLocally() }

private fun ensureOpenCvLoaded() {
    openCvLoaded
}

private fun decodeToMat(bytes: ByteArray): Mat {
    val mat = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
    if (mat.empty()) error("Failed to decode image (unsupported format or corrupt bytes)")
    return mat
}

private fun analyzeMat(color: Mat): ScoreboardPalette {
    val width = color.width()
    val height = color.height()

    val detected = detectScoreboardRoi(color)
    val fallback = detected == null
    val scoreboardRect: Rect = detected?.scoreboard ?: Rect(0, 0, width, height)
    val textRect: Rect = detected?.text ?: Rect(0, 0, width, height)

    val roi = color.submat(scoreboardRect)
    val bgTextClusters: List<ClusterInfo>
    val accentClusters: List<ClusterInfo>
    try {
        bgTextClusters = dominantClusters(roi, k = 2)
        accentClusters = dominantClusters(roi, k = 5)
    } finally {
        roi.release()
    }

    require(bgTextClusters.size >= 2) { "Color analysis produced no usable clusters" }
    val backgroundColor = bgTextClusters[0].centroid   // largest cluster = background
    val textColor = bgTextClusters[1].centroid          // smaller cluster = text

    val accents = accentClusters
        .map { it.centroid }
        .filter {
            it.distanceTo(backgroundColor) > ACCENT_MIN_DISTANCE &&
                it.distanceTo(textColor) > ACCENT_MIN_DISTANCE
        }
        .dedupByColor(COLOR_DEDUP_EPSILON)

    printReport(
        width = width,
        height = height,
        textRect = textRect,
        scoreboardRect = scoreboardRect,
        fallback = fallback,
        bgTextClusters = bgTextClusters,
        accentClusters = accentClusters,
        backgroundColor = backgroundColor,
        textColor = textColor,
        accents = accents,
    )

    return ScoreboardPalette(
        backgroundColor = backgroundColor,
        textColor = textColor,
        accents = accents,
        scoreboardRoi = scoreboardRect.toRoiRect(),
        detectedTextRoi = textRect.toRoiRect(),
        imageWidth = width,
        imageHeight = height,
        bgTextClusters = bgTextClusters,
        accentClusters = accentClusters,
        usedWholeImageFallback = fallback,
    )
}

/**
 * Finds the scoreboard region. Returns the union bbox of sizable contours (the text glyphs)
 * plus an expanded+clamped ROI around it, or null when no text-like contours are present.
 */
private fun detectScoreboardRoi(color: Mat): DetectedRoi? {
    val gray = Mat()
    val binInv = Mat()
    val bin = Mat()
    try {
        Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.threshold(gray, binInv, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        Imgproc.threshold(gray, bin, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

        // Pick the polarity whose foreground is the minority — text covers less area than bg.
        val mask = if (Core.countNonZero(binInv) < Core.countNonZero(bin)) binInv else bin

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            mask,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )
        hierarchy.release()

        val valid = contours.filter { Imgproc.contourArea(it) > MIN_CONTOUR_AREA }
        if (valid.isEmpty()) {
            contours.forEach { it.release() }
            return null
        }

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (contour in valid) {
            val r = Imgproc.boundingRect(contour)
            if (r.x < minX) minX = r.x
            if (r.y < minY) minY = r.y
            if (r.x + r.width > maxX) maxX = r.x + r.width
            if (r.y + r.height > maxY) maxY = r.y + r.height
        }
        contours.forEach { it.release() }
        if (maxX <= minX || maxY <= minY) return null

        val textBbox = Rect(minX, minY, maxX - minX, maxY - minY)
        val scoreboard = expandAndClamp(textBbox, color.width(), color.height())
        return DetectedRoi(text = textBbox, scoreboard = scoreboard)
    } finally {
        gray.release()
        binInv.release()
        bin.release()
    }
}

private data class DetectedRoi(val text: Rect, val scoreboard: Rect)

/** K-Means on the pixels of [roi], returns clusters sorted by size (largest first). */
private fun dominantClusters(roi: Mat, k: Int): List<ClusterInfo> {
    val total = roi.rows() * roi.cols()
    if (total <= 0) return emptyList()

    // Clone so the data is continuous, then reshape to (total, 3) CV_32F — one row per pixel,
    // columns are B/G/R (OpenCV order). K-Means treats rows as samples.
    val samples = Mat()
    val continuous = roi.clone()
    try {
        continuous.reshape(1, total).convertTo(samples, CvType.CV_32F)
    } finally {
        continuous.release()
    }

    val labels = Mat()
    val centers = Mat()
    val criteria = TermCriteria(TermCriteria.MAX_ITER + TermCriteria.EPS, 10, 1.0)
    Core.kmeans(samples, k, labels, criteria, KMEANS_ATTEMPTS, Core.KMEANS_PP_CENTERS, centers)
    samples.release()

    val labelValues = IntArray(total)
    labels.get(0, 0, labelValues)
    labels.release()

    val counts = IntArray(k)
    for (label in labelValues) if (label in 0 until k) counts[label]++

    val centerValues = FloatArray(k * 3)
    centers.get(0, 0, centerValues)
    centers.release()

    val clusters = (0 until k).map { idx ->
        // centers row idx = [B, G, R] → convert to RGB.
        val b = centerValues[idx * 3].toInt().coerceIn(0, 255)
        val g = centerValues[idx * 3 + 1].toInt().coerceIn(0, 255)
        val r = centerValues[idx * 3 + 2].toInt().coerceIn(0, 255)
        ClusterInfo(
            centroid = RgbColor(r, g, b),
            pixelCount = counts[idx],
            share = if (total > 0) counts[idx].toDouble() / total else 0.0,
        )
    }.sortedByDescending { it.pixelCount }

    return clusters
}

private fun expandAndClamp(rect: Rect, imageWidth: Int, imageHeight: Int): Rect {
    val dx = (rect.width * ROI_EXPAND_FRACTION).toInt()
    val dy = (rect.height * ROI_EXPAND_FRACTION).toInt()
    val x = (rect.x - dx).coerceAtLeast(0)
    val y = (rect.y - dy).coerceAtLeast(0)
    val width = (rect.width + 2 * dx).coerceAtMost(imageWidth - x)
    val height = (rect.height + 2 * dy).coerceAtMost(imageHeight - y)
    return Rect(x, y, width, height)
}

private fun List<RgbColor>.dedupByColor(epsilon: Double): List<RgbColor> {
    val out = mutableListOf<RgbColor>()
    for (color in this) {
        if (out.none { it.distanceTo(color) < epsilon }) out += color
    }
    return out
}

private fun Rect.toRoiRect() = RoiRect(x, y, width, height)

private fun printReport(
    width: Int,
    height: Int,
    textRect: Rect,
    scoreboardRect: Rect,
    fallback: Boolean,
    bgTextClusters: List<ClusterInfo>,
    accentClusters: List<ClusterInfo>,
    backgroundColor: RgbColor,
    textColor: RgbColor,
    accents: List<RgbColor>,
) {
    val lines = buildList {
        add("$TAG ── OpenCV color analysis ──")
        add("  image size        : ${width}x${height}")
        add("  detected text bbox: x=${textRect.x} y=${textRect.y} w=${textRect.width} h=${textRect.height}")
        add("  scoreboard ROI    : x=${scoreboardRect.x} y=${scoreboardRect.y} w=${scoreboardRect.width} h=${scoreboardRect.height}")
        add("  whole-image fallbk: $fallback")
        add("  k=2 clusters (bg/text):")
        bgTextClusters.forEach {
            add("    ${it.centroid.toHex()}  share=%.2f  pixels=%d".format(it.share, it.pixelCount))
        }
        add("  k=5 clusters (palette):")
        accentClusters.forEach {
            add("    ${it.centroid.toHex()}  share=%.2f  pixels=%d".format(it.share, it.pixelCount))
        }
        add("  ── chosen theme colors ──")
        add("    background: ${backgroundColor.toHex()}")
        add("    text      : ${textColor.toHex()}")
        add("    accents   : ${accents.joinToString("  ") { it.toHex() }.ifEmpty { "(none)" }}")
        add("$TAG ── end ──")
    }
    println(lines.joinToString("\n"))
}
