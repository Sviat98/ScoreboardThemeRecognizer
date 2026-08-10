package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

// ---- flags: the only multi-color regions on a scoreboard ----------------------

/** A pixel counts as "colored" when its HSV saturation exceeds this (0..255). Catches flag fills
 *  and colored regions; mono-hue text (white/grey ≈ 0 sat, yellow = high sat but ONE hue) is
 *  filtered out later by the hue-bin count, not by this threshold alone. */
private const val FLAG_SAT_THRESHOLD = 60

/** Saturation blob smaller than this (px) is noise, not a flag. */
private const val FLAG_MIN_AREA = 25

/** Saturation blob larger than this fraction of the (cropped) scoreboard is a band fill, not a flag. */
private const val FLAG_MAX_AREA_FRACTION = 0.06

/** Hue is quantized into this many bins (OpenCV hue range is 0..179). */
private const val FLAG_HUE_BINS = 12

/** A region is a flag when it spans at least this many "major" hue bins. */
private const val FLAG_MIN_HUE_BINS = 2

/** A hue bin counts as "major" when its pixel count is at least this fraction of the busiest bin.
 *  Kills the anti-aliasing edge of mono-hue text (a stray pixel of another hue is way below this). */
private const val FLAG_MAJOR_HUE_RATIO = 0.15

/** Flags are roughly compact (≈square-to-1.5:1). Reject wider/taller blobs (band fragments, colored
 *  cell edges) whose bbox would otherwise overlap score digits. */
private const val FLAG_MAX_ASPECT = 3.0

/** Flags always sit on the LEFT, next to the names — never in the score columns on the right. A
 *  blob whose center is past this fraction of the width is not a flag (kills right-side false
 *  positives when the identity zone is mis-detected as the full width). */
private const val FLAG_MAX_X_FRACTION = 0.5

// ---- seeds / country codes: per-element width inside the identity zone ----------

/**
 * Seeds and country codes are classified DIRECTLY from the detected element rects (no morphological
 * close): within one player row of the identity zone the surname is the WIDEST element, and narrower
 * elements are the seed / country code. Per-element rather than word-segmentation because a seed
 * often sits within ~2–4px of the surname — a close kernel large enough to bridge intra-letter gaps
 * also bridges that gap and glues the seed to the surname into one wide blob, which then reads as the
 * surname and is wrongly kept.
 */
private const val WORD_NOISE_WIDTH_RATIO = 0.6

/** An element shorter than this fraction of the tallest glyph is the SERVE indicator (tiny ball) →
 *  KEEP (not removed as noise). */
private const val WORD_SERVE_HEIGHT_RATIO = 0.45

/** A noise element at least this wide (in "letter units" = width ÷ glyph height) is a COUNTRY_CODE
 *  (≈3 letters); narrower is a SEED (digit / "(n)"). Label only — both are removed regardless. */
private const val COUNTRY_CODE_MIN_LETTERS = 2.5

/** Adjacent same-type noise rects closer than this (px, on the cropped width scale) are merged so
 *  the cleaner fills a solid block instead of speckling. */
private const val NOISE_MERGE_GAP_FRACTION = 0.02

/** Verbose per-candidate logging for tuning the heuristics against the probe output. */
private const val NOISE_DEBUG = true

internal actual suspend fun classifyNoiseElements(
    image: ImageFile,
    elements: ScoreboardElements,
): NoiseClassification = withContext(Dispatchers.Default) {
    ensureOpenCvLoaded()
    if (image.content.isEmpty()) error("Cannot classify noise on an empty image")
    val color = decodeToMat(image.content)
    try {
        classifyMat(color, elements)
    } finally {
        color.release()
    }
}

private fun classifyMat(color: Mat, elements: ScoreboardElements): NoiseClassification {
    val fullWidth = color.width()
    val fullHeight = color.height()
    val crop = detectScoreboardCrop(color)
    val ox = crop.x
    val oy = crop.y
    val work: Mat = if (crop.x != 0 || crop.y != 0 || crop.width != fullWidth || crop.height != fullHeight) {
        color.submat(crop)
    } else {
        color
    }
    try {
        val workWidth = work.width()

        // Identity zone (NAMES_AND_SERVE) first — flags AND seeds/codes only ever sit in it (next to
        // the names), so restricting both to this zone is what stops score digits (in the score
        // columns to the right) from being read as multi-color flag blobs.
        val identity = identityZoneRect(work, ox, oy)
        if (NOISE_DEBUG) {
            val ir = identity
            val z = if (ir != null) "x=${ir.x}..${ir.x + ir.width} (w=${ir.width})" else "(none → no flag/seed filtering)"
            println("[NoiseDebug] identity zone: $z  work=${work.width()}x${work.height()} ox=$ox oy=$oy")
        }

        val noise = mutableListOf<NoiseElement>()

        // 1. Flags: multi-color saturation blobs, restricted to the identity zone.
        noise += detectFlags(work, ox, oy, elements, identity)

        // 2. Seeds / country codes: word segmentation within the identity zone.
        val flagRects = noise.map { it.rect } // flags detected above — exclude their area from words
        noise += if (identity != null) {
            detectSeedOrCode(work, ox, elements, identity, flagRects)
        } else {
            emptyList()
        }

        // 3. Merge adjacent same-type noise rects for a solid fill.
        val merged = mergeNoise(noise, (workWidth * NOISE_MERGE_GAP_FRACTION).toInt().coerceAtLeast(2))
        val withIndices = attachElementIndices(merged, elements)
        return NoiseClassification(withIndices).also {
            println(
                "[NoiseDetector] ${elements.elements.size} elements → " +
                    "noise=${it.elements.size} " +
                    it.byType().entries.joinToString(", ") { (t, v) -> "${t.name}=${v.size}" }
            )
        }
    } finally {
        if (work !== color) work.release()
    }
}

// ---- flags --------------------------------------------------------------------

private fun detectFlags(
    work: Mat,
    ox: Int,
    oy: Int,
    elements: ScoreboardElements,
    identity: RoiRect?,
): List<NoiseElement> {
    val hsv = Mat()
    val channels = ArrayList<Mat>()
    val satMask = Mat()
    val labels = Mat()
    val stats = Mat()
    val centroids = Mat()
    try {
        Imgproc.cvtColor(work, hsv, Imgproc.COLOR_BGR2HSV)
        Core.split(hsv, channels)
        val hue = channels[0]
        val satChan = channels[1]
        Core.inRange(satChan, Scalar(FLAG_SAT_THRESHOLD.toDouble()), Scalar(255.0), satMask)

        val binSize = (180.0 / FLAG_HUE_BINS).toInt().coerceAtLeast(1)
        val result = mutableListOf<NoiseElement>()

        // (a) Saturation-blob based: catches flags the text detector fragmented or missed.
        val n = Imgproc.connectedComponentsWithStats(satMask, labels, stats, centroids, 8)
        val totalArea = work.width() * work.height()
        val maxArea = totalArea * FLAG_MAX_AREA_FRACTION
        for (i in 1 until n) {
            val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
            if (area < FLAG_MIN_AREA || area > maxArea) continue
            val left = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
            val top = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
            val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            if (w <= 0 || h <= 0) continue

            val rect = RoiRect(left + ox, top + oy, w, h)
            val inIdentity = identity == null || horizontalCenterInside(rect, identity)
            val inLeftHalf = (left + w / 2).toDouble() / work.width() <= FLAG_MAX_X_FRACTION
            val aspect = maxOf(w, h).toDouble() / minOf(w, h).coerceAtLeast(1)
            val majorBins = countMajorHueBinsInRect(hue, satMask, left, top, w, h, binSize)

            val accept = inIdentity && inLeftHalf && aspect <= FLAG_MAX_ASPECT && majorBins >= FLAG_MIN_HUE_BINS
            if (NOISE_DEBUG) {
                println(
                    "[NoiseDebug] flag cand(blob): x=${rect.x} y=${rect.y} ${w}x${h} area=${area.toInt()} " +
                        "aspect=${"%.1f".format(aspect)} bins=$majorBins inId=$inIdentity accept=$accept"
                )
            }
            if (accept) result += NoiseElement(NoiseType.FLAG, rect, elementIndicesOverlapping(elements, rect))
        }

        // (b) Element-rect based: catches flags detected as text elements whose saturation blob
        //     merged with neighbours into a giant blob (filtered out by area above). Analyzes the
        //     element's own pixels for multi-hue color — names/seed digits stay mono-hue (≤1 bin).
        if (identity != null) {
            val width = work.width()
            val height = work.height()
            for (e in elements.elements) {
                if (!horizontalCenterInside(e.rect, identity)) continue
                val cxLocal = e.rect.x + e.rect.width / 2 - ox
                if (cxLocal.toDouble() / width > FLAG_MAX_X_FRACTION) continue
                val left = (e.rect.x - ox).coerceIn(0, width - 1)
                val top = (e.rect.y - oy).coerceIn(0, height - 1)
                val w = e.rect.width.coerceAtMost(width - left)
                val h = e.rect.height.coerceAtMost(height - top)
                if (w <= 1 || h <= 1) continue
                val majorBins = countMajorHueBinsInRect(hue, satMask, left, top, w, h, binSize)
                val accept = majorBins >= FLAG_MIN_HUE_BINS
                if (NOISE_DEBUG) {
                    println("[NoiseDebug] flag cand(elem): #${e.index} x=${e.rect.x} ${w}x${h} bins=$majorBins accept=$accept")
                }
                if (accept) result += NoiseElement(NoiseType.FLAG, e.rect, listOf(e.index))
            }
        }

        return result
    } finally {
        hsv.release()
        channels.forEach { it.release() }
        satMask.release()
        labels.release()
        stats.release()
        centroids.release()
    }
}

/**
 * How many hue bins in the rect each hold at least [FLAG_MAJOR_HUE_RATIO] × the busiest bin, counted
 * over SATURATED pixels only. A flag has 2+; mono-hue text (white/dark glyphs, or single-color
 * anti-aliasing) has ≤1.
 */
private fun countMajorHueBinsInRect(
    hue: Mat,
    satMask: Mat,
    left: Int,
    top: Int,
    w: Int,
    h: Int,
    binSize: Int,
): Int {
    val binCounts = IntArray(FLAG_HUE_BINS)
    for (y in 0 until h) {
        for (x in 0 until w) {
            // no-arg get() returns a double[] and is compatible with every Mat type.
            if (satMask.get(top + y, left + x)[0] <= 0) continue
            val hueVal = hue.get(top + y, left + x)[0].toInt()
            val bin = (hueVal / binSize).coerceIn(0, FLAG_HUE_BINS - 1)
            binCounts[bin]++
        }
    }
    val maxBin = binCounts.maxOrNull() ?: 0
    if (maxBin == 0) return 0
    val threshold = maxBin * FLAG_MAJOR_HUE_RATIO
    return binCounts.count { it >= threshold }
}

// ---- seeds / country codes ----------------------------------------------------

/** Returns the NAMES_AND_SERVE zone rect in ORIGINAL image coords, or null when columns weren't found. */
private fun identityZoneRect(work: Mat, ox: Int, oy: Int): RoiRect? {
    val blocks = detectContentColumns(work)
    val zones = mapBlocksToZones(blocks, work.height())
    val local = zones[ScoreboardZoneKind.NAMES_AND_SERVE] ?: return null
    return RoiRect(local.x + ox, local.y + oy, local.width, local.height)
}

private fun detectSeedOrCode(
    work: Mat,
    ox: Int,
    elements: ScoreboardElements,
    identity: RoiRect,
    flagRects: List<RoiRect>,
): List<NoiseElement> {
    // Reference glyph height = tallest detected element (a score digit / the merged surname). Used
    // only to gate the serve indicator and to count "letters" — the surname is identified by WIDTH,
    // not height (a country code shares the surname's font height).
    val glyphH = elements.elements.maxOfOrNull { it.rect.height }?.coerceAtLeast(1) ?: return emptyList()
    val width = work.width()
    // Identity words (name / code / seed) are on the LEFT; scores are on the right. Restricting to
    // the left half keeps score digits out even when the identity zone is mis-detected as full width.
    val leftHalfMaxX = (ox + width * FLAG_MAX_X_FRACTION).toInt()
    val serveMaxH = glyphH * WORD_SERVE_HEIGHT_RATIO

    // Candidates = identity-zone, left-half, NON-flag elements that are tall enough to be text (i.e.
    // not the tiny serve ball). These are the surname / merged surname / seed / code glyphs.
    data class Cand(val index: Int, val rect: RoiRect)
    val candidates = elements.elements.mapNotNull { e ->
        if (!horizontalCenterInside(e.rect, identity)) return@mapNotNull null
        val cx = e.rect.x + e.rect.width / 2
        if (cx > leftHalfMaxX) return@mapNotNull null
        if (flagRects.any { rectsOverlap(it, e.rect) }) return@mapNotNull null
        if (e.rect.height <= serveMaxH) return@mapNotNull null // serve indicator → KEEP
        Cand(e.index, e.rect)
    }
    if (candidates.isEmpty()) return emptyList()

    fun rowCenterY(row: List<Cand>): Int = row.sumOf { it.rect.y + it.rect.height / 2 } / row.size

    // Group candidates into PLAYER ROWS (two rows for a match). Within a row the surname is the
    // WIDEST element; narrower ones are the seed / code. Comparing per-row (not globally) keeps a
    // short surname safe when the opponent's surname is much longer.
    val rowGap = (glyphH * 0.5).toInt().coerceAtLeast(2)
    val rows = mutableListOf<MutableList<Cand>>()
    for (c in candidates.sortedBy { it.rect.y + it.rect.height / 2 }) {
        val cy = c.rect.y + c.rect.height / 2
        val current = rows.lastOrNull()
        if (current != null && abs(cy - rowCenterY(current)) <= rowGap) {
            current.add(c)
        } else {
            rows.add(mutableListOf(c))
        }
    }

    val noise = mutableListOf<NoiseElement>()
    for (row in rows) {
        val maxWidth = row.maxOf { it.rect.width }
        if (NOISE_DEBUG) {
            println(
                "[NoiseDebug] row cy=${rowCenterY(row)} n=${row.size} maxW=$maxWidth " +
                    "glyphH=$glyphH serveMaxH=${"%.1f".format(serveMaxH)}"
            )
        }
        for (c in row) {
            val isName = c.rect.width >= maxWidth * WORD_NOISE_WIDTH_RATIO
            if (NOISE_DEBUG) {
                println("[NoiseDebug] elem #${c.index} x=${c.rect.x} ${c.rect.width}x${c.rect.height} → name=$isName")
            }
            if (isName) continue
            val letters = c.rect.width.toDouble() / glyphH
            val type = if (letters >= COUNTRY_CODE_MIN_LETTERS) NoiseType.COUNTRY_CODE else NoiseType.SEED
            noise += NoiseElement(type, c.rect, listOf(c.index))
        }
    }
    return noise
}

private fun horizontalCenterInside(rect: RoiRect, zone: RoiRect): Boolean {
    val cx = rect.x + rect.width / 2
    return cx in zone.x..(zone.x + zone.width)
}

private fun unionRects(rects: List<RoiRect>): RoiRect {
    val minX = rects.minOf { it.x }
    val minY = rects.minOf { it.y }
    val maxEndX = rects.maxOf { it.x + it.width }
    val maxEndY = rects.maxOf { it.y + it.height }
    return RoiRect(minX, minY, maxEndX - minX, maxEndY - minY)
}

// ---- merge + diagnostics ------------------------------------------------------

private fun mergeNoise(noise: List<NoiseElement>, mergeGap: Int): List<NoiseElement> {
    val merged = mutableListOf<NoiseElement>()
    for (byType in noise.groupBy { it.type }) {
        val sorted = byType.value.sortedBy { it.rect.x }
        var current = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val horizontallyClose = next.rect.x - (current.rect.x + current.rect.width) in 0..mergeGap
            val verticallyClose = current.rect.y - next.rect.y in -mergeGap..mergeGap
            if (horizontallyClose && verticallyClose) {
                current = NoiseElement(current.type, unionRects(listOf(current.rect, next.rect)), current.elementIndices + next.elementIndices)
            } else {
                merged += current
                current = next
            }
        }
        merged += current
    }
    return merged
}

/** After merging (which can drop element-index coverage), recompute indices by rect overlap. */
private fun attachElementIndices(noise: List<NoiseElement>, elements: ScoreboardElements): List<NoiseElement> =
    noise.map { n -> n.copy(elementIndices = elementIndicesOverlapping(elements, n.rect)) }

private fun elementIndicesOverlapping(elements: ScoreboardElements, rect: RoiRect): List<Int> =
    elements.elements.filter { rectsOverlap(it.rect, rect) }.map { it.index }

private fun rectsOverlap(a: RoiRect, b: RoiRect): Boolean {
    val overlapX = minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)
    val overlapY = minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)
    return overlapX > 0 && overlapY > 0
}
