package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nu.pattern.OpenCV
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * JVM implementation of [analyzeScoreboardImage] using OpenCV.
 *
 * Stage-1, zone-based pipeline (no LLM):
 *  1. Load natives once, decode bytes into a BGR [Mat].
 *  2. Detect vertical columns: binarize (Otsu, minority-polarity foreground), sum foreground
 *     pixels per column (vertical projection), group dense runs into content blocks separated
 *     by empty vertical gaps. Map blocks left→right to zones: first = names, last = current
 *     game, second-to-last = current set, everything between = previous sets. If fewer than 4
 *     blocks are found, fall back to equal quarters.
 *  3. Per zone run K-Means (k = 3 for names / previous sets, k = 2 for current set / game) and
 *     take the **mode** (most frequent quantized color) of each cluster as its representative —
 *     this returns flat fills (e.g. a solid background) bit-accurately instead of the mean.
 *  4. Assign clusters to roles by size per zone rules, print a `[ThemeRecognizer]` report.
 *
 * All blocking work runs on [Dispatchers.Default] so it never stalls the UI.
 */
internal actual suspend fun analyzeScoreboardImage(image: ImageFile): ScoreboardZones =
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

/** A column counts as "content" if its foreground sum exceeds this fraction of the busiest column. */
private const val COLUMN_THRESHOLD_FRACTION = 0.08f

/** Gaps narrower than this (px) don't separate zones — they're intra-column whitespace. */
private const val MIN_GAP_PX = 10

/** Content blocks narrower than this (px) are dropped as noise. Also drops the narrow local-contrast
 *  "hump" the [LOCAL_MEAN_BLOCK] blur spreads across a band edge on multi-color scoreboards, so a
 *  color boundary doesn't register as a spurious content column. */
private const val MIN_BLOCK_WIDTH = 12

/** Quantization step (per channel) for computing a cluster's mode color. */
private const val MODE_QUANT = 8

/** Number of quantization levels per channel = 256 / [MODE_QUANT]. */
private const val MODE_LEVELS = 32

private const val KMEANS_ATTEMPTS = 3

/** Neighborhood size (px) for the local-mean blur used to detect text by local contrast. Kept small
 *  so the band-edge hump stays narrower than [MIN_BLOCK_WIDTH] and gets filtered out, while still
 *  being larger than text stroke width. */
private const val LOCAL_MEAN_BLOCK = 9

/** A pixel is "text/edge" when it differs from its local mean by more than this (0..255). Flat
 *  fills of any color stay below it, so multi-color bands no longer read as one content block. */
private const val LOCAL_CONTRAST_THRESHOLD = 18

/** Per-channel tolerance for matching the surrounding margin color when trimming the background. */
private const val CROP_MARGIN_TOLERANCE = 30.0

/** A detected scoreboard blob must cover at least this fraction of the image to count as a real crop target. */
private const val CROP_MIN_AREA_FRACTION = 0.15

/** If the blob covers more than this fraction, there's no margin to trim — analyze the whole image. */
private const val CROP_MAX_AREA_FRACTION = 0.99

@Suppress("unused")
private val openCvLoaded: Unit by lazy { OpenCV.loadLocally() }

internal fun ensureOpenCvLoaded() {
    openCvLoaded
}

internal fun decodeToMat(bytes: ByteArray): Mat {
    val mat = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
    if (mat.empty()) error("Failed to decode image (unsupported format or corrupt bytes)")
    return mat
}

private fun analyzeMat(color: Mat): ScoreboardZones {
    val fullWidth = color.width()
    val fullHeight = color.height()

    // 1. Trim the surrounding background so the zone analysis runs on just the scoreboard.
    val crop = detectScoreboardCrop(color)
    val cropped = crop.x != 0 || crop.y != 0 ||
        crop.width != fullWidth || crop.height != fullHeight
    val work: Mat = if (cropped) color.submat(crop) else color
    try {
        val width = work.width()
        val height = work.height()

        // 2. Detect vertical columns within the (cropped) scoreboard.
        val blocks = detectContentColumns(work)
        val detectedZoneRois = mapBlocksToZones(blocks, height)
        val usedFallback = detectedZoneRois.isEmpty()
        val zoneRois: Map<ScoreboardZoneKind, RoiRect> =
            if (usedFallback) equalQuarters(width, height) else detectedZoneRois

        val zones = ScoreboardZoneKind.entries.mapNotNull { kind ->
            val roi = zoneRois[kind] ?: return@mapNotNull null
            val sub = work.submat(Rect(roi.x, roi.y, roi.width, roi.height))
            try {
                analyzeZone(kind, roi, sub)
            } finally {
                sub.release()
            }
        }

        val croppedTo = if (cropped) RoiRect(crop.x, crop.y, crop.width, crop.height) else null
        printReport(fullWidth, fullHeight, croppedTo, width, height, blocks, zoneRois, usedFallback, zones)

        return ScoreboardZones(
            imageWidth = width,
            imageHeight = height,
            detectedBlocks = blocks,
            zones = zones,
            usedFallback = usedFallback,
            croppedTo = croppedTo,
        )
    } finally {
        if (cropped) work.release()
    }
}

/**
 * Trims the surrounding background. Estimates the margin color from the border ring, masks out
 * everything close to it, and takes the largest remaining connected component as the scoreboard.
 * Returns the full-image rect when there is no clear margin (the upload is already tightly
 * cropped), so the step is a safe no-op in that case.
 */
private fun detectScoreboardCrop(color: Mat): Rect {
    val width = color.width()
    val height = color.height()
    val marginColor = estimateMarginColor(color) ?: return Rect(0, 0, width, height)
    val (mb, mg, mr) = marginColor

    val closeToMargin = Mat()
    Core.inRange(
        color,
        Scalar(mb - CROP_MARGIN_TOLERANCE, mg - CROP_MARGIN_TOLERANCE, mr - CROP_MARGIN_TOLERANCE),
        Scalar(mb + CROP_MARGIN_TOLERANCE, mg + CROP_MARGIN_TOLERANCE, mr + CROP_MARGIN_TOLERANCE),
        closeToMargin,
    )
    val nonMargin = Mat()
    Core.bitwise_not(closeToMargin, nonMargin)
    closeToMargin.release()

    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
    Imgproc.morphologyEx(nonMargin, nonMargin, Imgproc.MORPH_CLOSE, kernel)
    kernel.release()

    val labels = Mat()
    val stats = Mat()
    val centroids = Mat()
    val componentCount = Imgproc.connectedComponentsWithStats(nonMargin, labels, stats, centroids, 8)
    labels.release()
    centroids.release()
    nonMargin.release()

    var bestIndex = -1
    var bestArea = 0.0
    for (i in 1 until componentCount) {
        val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
        if (area > bestArea) {
            bestArea = area
            bestIndex = i
        }
    }
    if (bestIndex < 0) {
        stats.release()
        return Rect(0, 0, width, height)
    }

    val left = stats.get(bestIndex, Imgproc.CC_STAT_LEFT)[0].toInt()
    val top = stats.get(bestIndex, Imgproc.CC_STAT_TOP)[0].toInt()
    val componentWidth = stats.get(bestIndex, Imgproc.CC_STAT_WIDTH)[0].toInt()
    val componentHeight = stats.get(bestIndex, Imgproc.CC_STAT_HEIGHT)[0].toInt()
    stats.release()

    val fraction = (componentWidth * componentHeight).toDouble() / (width * height)
    return if (fraction in CROP_MIN_AREA_FRACTION..CROP_MAX_AREA_FRACTION) {
        Rect(left, top, componentWidth, componentHeight)
    } else {
        Rect(0, 0, width, height)
    }
}

/** Estimates the surrounding margin color as the most frequent quantized color on the border ring. */
private fun estimateMarginColor(color: Mat): Triple<Double, Double, Double>? {
    val width = color.width()
    val height = color.height()
    val insetX = (width * 0.02).toInt().coerceIn(1, width - 1)
    val insetY = (height * 0.02).toInt().coerceIn(1, height - 1)

    val counts = HashMap<Int, IntArray>() // key -> [count, sumB, sumG, sumR]
    fun sample(x: Int, y: Int) {
        val px = color.get(y, x)
        val key = packQuantized(px[0].toFloat(), px[1].toFloat(), px[2].toFloat())
        val bin = counts.getOrPut(key) { IntArray(4) }
        bin[0]++
        bin[1] += px[0].toInt()
        bin[2] += px[1].toInt()
        bin[3] += px[2].toInt()
    }

    var x = 0
    while (x < width) {
        sample(x, insetY)
        sample(x, height - 1 - insetY)
        x += 2
    }
    var y = 0
    while (y < height) {
        sample(insetX, y)
        sample(width - 1 - insetX, y)
        y += 2
    }

    val best = counts.maxByOrNull { it.value[0] } ?: return null
    val bin = best.value
    if (bin[0] == 0) return null
    return Triple(bin[1] / bin[0].toDouble(), bin[2] / bin[0].toDouble(), bin[3] / bin[0].toDouble())
}

/**
 * Finds vertical content columns via a foreground-density projection. Each returned [RoiRect]
 * spans the full image height and the x-range of one content block.
 */
/**
 * Finds vertical content columns via two complementary signals:
 *  - text density per column (local-contrast foreground projection) — separates same-background
 *    columns (e.g. names from previous-set scores) by the empty gap between their text;
 *  - per-column mean-color change — forces a split at band boundaries, so multi-color scoreboards
 *    (dark/light green, blue bands) don't merge into one block.
 * A column is a separator when it has no text OR sits on a color boundary; maximal runs of the
 * remaining columns are the content blocks.
 */
private fun detectContentColumns(color: Mat): List<RoiRect> {
    val width = color.width()
    val height = color.height()

    val gray = Mat()
    Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY)
    val mask = textForegroundMask(gray)
    gray.release()

    val maskF = Mat()
    val colSumsMat = Mat()
    try {
        mask.convertTo(maskF, CvType.CV_32F)
        Core.reduce(maskF, colSumsMat, 0, Core.REDUCE_SUM) // 1×width: foreground sum per column
    } finally {
        mask.release()
        maskF.release()
    }
    val textDensity = FloatArray(width)
    colSumsMat.get(0, 0, textDensity)
    colSumsMat.release()

    val maxDensity = (textDensity.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val densityThreshold = maxDensity * COLUMN_THRESHOLD_FRACTION

    val rawBlocks = mutableListOf<RoiRect>()
    var start = -1
    for (x in 0 until width) {
        val isContent = textDensity[x] > densityThreshold
        if (isContent && start < 0) start = x
        if (!isContent && start >= 0) {
            rawBlocks.add(RoiRect(start, 0, x - start, height))
            start = -1
        }
    }
    if (start >= 0) rawBlocks.add(RoiRect(start, 0, width - start, height))

    return mergeAndFilterBlocks(rawBlocks)
}

/**
 * Foreground mask of text/edges, robust to multi-color backgrounds. A pixel counts as foreground
 * when it differs from its local neighborhood (local contrast) — flat fills of *any* color (dark
 * green, light green, blue bands) contribute ~0 contrast and stay background, so a colored band
 * no longer reads as one giant content block (the failure mode of a single global Otsu threshold,
 * which classifies bright band fills as text and merges every column). Pololarity-agnostic because
 * the absolute difference is used.
 */
internal fun textForegroundMask(gray: Mat): Mat {
    val localMean = Mat()
    Imgproc.blur(gray, localMean, Size(LOCAL_MEAN_BLOCK.toDouble(), LOCAL_MEAN_BLOCK.toDouble()))
    val contrast = Mat()
    Core.absdiff(gray, localMean, contrast)
    localMean.release()

    val mask = Mat()
    Imgproc.threshold(contrast, mask, LOCAL_CONTRAST_THRESHOLD.toDouble(), 255.0, Imgproc.THRESH_BINARY)
    contrast.release()
    return mask
}

/** Merge blocks separated by narrow gaps and drop noise-narrow ones. */
private fun mergeAndFilterBlocks(blocks: List<RoiRect>): List<RoiRect> {
    if (blocks.isEmpty()) return emptyList()
    val sorted = blocks.sortedBy { it.x }
    val merged = mutableListOf<RoiRect>()
    var current = sorted[0]
    for (i in 1 until sorted.size) {
        val next = sorted[i]
        val gap = next.x - (current.x + current.width)
        if (gap < MIN_GAP_PX) {
            current = RoiRect(current.x, 0, (next.x + next.width) - current.x, current.height)
        } else {
            merged.add(current)
            current = next
        }
    }
    merged.add(current)
    return merged.filter { it.width >= MIN_BLOCK_WIDTH }
}

/**
 * Maps detected content blocks to zones, **anchored from the right**.
 *
 * The names column is the wide left region; score columns are narrow and sit on the right. So:
 *  - Names = a leading run of wide blocks from the left (the leftmost block is always names, and
 *    any immediately-following wide blocks are folded in — survives a surname split into fragments).
 *  - The narrow blocks to the right are score columns, anchored from the right end: last =
 *    current game, second-to-last = current set (always one column), the rest = previous sets.
 *
 * Returns a partial map when fewer columns were detected (e.g. a match with no completed sets) —
 * the caller only falls back to equal quarters when this is completely empty (no blocks at all),
 * so the "four equal parts" case is reserved for true detection failure.
 */
private fun mapBlocksToZones(blocks: List<RoiRect>, height: Int): Map<ScoreboardZoneKind, RoiRect> {
    val sorted = blocks.sortedBy { it.x }
    if (sorted.isEmpty()) return emptyMap()

    val widths = sorted.map { it.width }
    val median = medianWidth(widths)
    val wideThreshold = (median * 1.5).coerceAtLeast(median + 1)

    var namesEnd = 0
    while (namesEnd < sorted.size && sorted[namesEnd].width >= wideThreshold) namesEnd++
    if (namesEnd == 0) namesEnd = 1 // the leftmost block is always the names region
    val namesRect = unionRect(sorted.subList(0, namesEnd), height)

    val scores = sorted.subList(namesEnd, sorted.size)
    val result = LinkedHashMap<ScoreboardZoneKind, RoiRect>()
    result[ScoreboardZoneKind.NAMES_AND_SERVE] = namesRect
    if (scores.isNotEmpty()) {
        result[ScoreboardZoneKind.CURRENT_GAME] = scores.last()
        if (scores.size >= 2) {
            result[ScoreboardZoneKind.CURRENT_SET] = scores[scores.size - 2]
        }
        if (scores.size >= 3) {
            result[ScoreboardZoneKind.PREVIOUS_SETS] = unionRect(scores.subList(0, scores.size - 2), height)
        }
    }
    return result
}

private fun medianWidth(widths: List<Int>): Double {
    if (widths.isEmpty()) return 0.0
    val sorted = widths.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[mid].toDouble()
    } else {
        (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}

/** Merges a list of full-height rects (by x-range) into one spanning rect. */
private fun unionRect(rects: List<RoiRect>, height: Int): RoiRect {
    if (rects.isEmpty()) return RoiRect(0, 0, 0, height)
    val minX = rects.minOf { it.x }
    val maxEnd = rects.maxOf { it.x + it.width }
    return RoiRect(minX, 0, maxEnd - minX, height)
}

private fun equalQuarters(width: Int, height: Int): Map<ScoreboardZoneKind, RoiRect> {
    val q = width / 4
    return linkedMapOf(
        ScoreboardZoneKind.NAMES_AND_SERVE to RoiRect(0, 0, q, height),
        ScoreboardZoneKind.PREVIOUS_SETS to RoiRect(q, 0, q, height),
        ScoreboardZoneKind.CURRENT_SET to RoiRect(q * 2, 0, q, height),
        ScoreboardZoneKind.CURRENT_GAME to RoiRect(q * 3, 0, width - q * 3, height),
    )
}

/** K-Means on one zone, with the cluster count and role assignment driven by [kind]. */
private fun analyzeZone(kind: ScoreboardZoneKind, roi: RoiRect, sub: Mat): ZoneAnalysis {
    val k = when (kind) {
        ScoreboardZoneKind.NAMES_AND_SERVE, ScoreboardZoneKind.PREVIOUS_SETS -> 3
        ScoreboardZoneKind.CURRENT_SET, ScoreboardZoneKind.CURRENT_GAME -> 2
    }
    val clusters = dominantClusters(sub, k)

    return when (kind) {
        ScoreboardZoneKind.NAMES_AND_SERVE -> ZoneAnalysis(
            kind = kind,
            roi = roi,
            clusters = clusters,
            background = clusters.getOrNull(0)?.centroid,  // largest
            primaryText = clusters.getOrNull(1)?.centroid, // medium
            serve = clusters.getOrNull(2)?.centroid,       // smallest
        )

        ScoreboardZoneKind.PREVIOUS_SETS -> ZoneAnalysis(
            kind = kind,
            roi = roi,
            clusters = clusters,
            // Largest cluster is the zone background — the two text colors are the rest.
            winText = clusters.getOrNull(1)?.centroid,
            loseText = clusters.getOrNull(2)?.centroid,
        )

        ScoreboardZoneKind.CURRENT_SET, ScoreboardZoneKind.CURRENT_GAME -> ZoneAnalysis(
            kind = kind,
            roi = roi,
            clusters = clusters,
            background = clusters.getOrNull(0)?.centroid,  // largest
            primaryText = clusters.getOrNull(1)?.centroid, // smaller
        )
    }
}

/**
 * K-Means on the pixels of [roi]. Each cluster's representative color is the **mode** (most
 * frequent quantized color among its pixels) rather than the mean, with the mean as a fallback.
 * Returns clusters sorted by pixel count, largest first.
 */
private fun dominantClusters(roi: Mat, k: Int): List<ClusterInfo> {
    val total = roi.rows() * roi.cols()
    if (total <= 0) return emptyList()

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

    val labelValues = IntArray(total)
    labels.get(0, 0, labelValues)
    labels.release()

    val sampleValues = FloatArray(total * 3)
    samples.get(0, 0, sampleValues)
    samples.release()

    val counts = IntArray(k)
    // Per-cluster histograms: key = packed quantized color, value = [count, sumB, sumG, sumR].
    val histograms = Array(k) { HashMap<Int, IntArray>() }
    for (i in 0 until total) {
        val cluster = labelValues[i]
        if (cluster !in 0 until k) continue
        counts[cluster]++
        val b = sampleValues[i * 3]
        val g = sampleValues[i * 3 + 1]
        val r = sampleValues[i * 3 + 2]
        val key = packQuantized(b, g, r)
        val bin = histograms[cluster].getOrPut(key) { IntArray(4) }
        bin[0]++
        bin[1] += b.toInt()
        bin[2] += g.toInt()
        bin[3] += r.toInt()
    }

    val centerValues = FloatArray(k * 3)
    centers.get(0, 0, centerValues)
    centers.release()

    return (0 until k).map { idx ->
        val color = modeColor(histograms[idx])
            ?: RgbColor(
                r = centerValues[idx * 3 + 2].toInt().coerceIn(0, 255),
                g = centerValues[idx * 3 + 1].toInt().coerceIn(0, 255),
                b = centerValues[idx * 3].toInt().coerceIn(0, 255),
            )
        ClusterInfo(
            centroid = color,
            pixelCount = counts[idx],
            share = counts[idx].toDouble() / total,
        )
    }.sortedByDescending { it.pixelCount }
}

private fun packQuantized(b: Float, g: Float, r: Float): Int {
    val bq = (b.toInt() / MODE_QUANT).coerceIn(0, MODE_LEVELS - 1)
    val gq = (g.toInt() / MODE_QUANT).coerceIn(0, MODE_LEVELS - 1)
    val rq = (r.toInt() / MODE_QUANT).coerceIn(0, MODE_LEVELS - 1)
    return (bq * MODE_LEVELS + gq) * MODE_LEVELS + rq
}

/** Returns the most frequent quantized color of a cluster as the exact RGB of that bin. */
private fun modeColor(histogram: HashMap<Int, IntArray>): RgbColor? {
    val best = histogram.maxByOrNull { it.value[0] } ?: return null
    val bin = best.value
    val count = bin[0]
    if (count == 0) return null
    // centers store BGR order; bin sums follow the same B, G, R order.
    return RgbColor(
        r = (bin[3] / count).coerceIn(0, 255),
        g = (bin[2] / count).coerceIn(0, 255),
        b = (bin[1] / count).coerceIn(0, 255),
    )
}

private fun printReport(
    fullWidth: Int,
    fullHeight: Int,
    croppedTo: RoiRect?,
    width: Int,
    height: Int,
    blocks: List<RoiRect>,
    zoneRois: Map<ScoreboardZoneKind, RoiRect>,
    usedFallback: Boolean,
    zones: List<ZoneAnalysis>,
) {
    val lines = buildList {
        add("$TAG ── zone analysis ──")
        add("  image size         : ${fullWidth}x${fullHeight}")
        if (croppedTo != null) {
            add(
                "  background trimmed : x=${croppedTo.x}..${croppedTo.x + croppedTo.width} " +
                    "y=${croppedTo.y}..${croppedTo.y + croppedTo.height} → ${width}x${height}"
            )
        } else {
            add("  background trimmed : (none — no surrounding margin detected)")
        }
        add("  detected columns   : ${blocks.size}  " + blocks.joinToString("  ") { "[${it.x}..${it.x + it.width}]" })
        add("  fallback quarters  : $usedFallback")
        for (kind in ScoreboardZoneKind.entries) {
            val roi = zoneRois[kind]
            val zone = zones.firstOrNull { it.kind == kind }
            if (roi == null || zone == null) {
                add("  ${kind.name.padEnd(16)}: (not analyzed)")
                continue
            }
            add("  ${kind.name.padEnd(16)}: x=${roi.x}..${roi.x + roi.width}")
            zone.clusters.forEach {
                add("    cluster ${it.centroid.toHex()}  share=%.2f  pixels=%d".format(it.share, it.pixelCount))
            }
            val roles = zoneRolesString(zone)
            add("    ─ roles: $roles")
        }
        add("$TAG ── end ──")
    }
    println(lines.joinToString("\n"))
}

private fun zoneRolesString(zone: ZoneAnalysis): String = buildList {
    zone.background?.let { add("bg=${it.toHex()}") }
    zone.primaryText?.let { add("text=${it.toHex()}") }
    zone.serve?.let { add("serve=${it.toHex()}") }
    zone.winText?.let { add("win=${it.toHex()}") }
    zone.loseText?.let { add("lose=${it.toHex()}") }
}.joinToString("  ").ifEmpty { "(none)" }
