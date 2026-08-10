package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import de.androidpit.colorthief.ColorThief
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Number of representative colors Color Thief's MMCQ should return. High enough to surface rare
 *  accents (serve dot, lose-text grey) that a small palette would merge away. */
private const val PALETTE_COLOR_COUNT = 16

/** Color Thief pixel-sampling step (1 = every pixel). 10 matches the library default. */
private const val PALETTE_QUALITY = 10

/** Subsample step for our own frequency pass (2 = every other pixel). Plenty of samples for a stable %. */
private const val FREQUENCY_STEP = 2

/** Two palette colors closer than this (Euclidean RGB distance) are merged into one entry, so a
 *  family of near-duplicate shades (e.g. several greens) collapses onto a single representative
 *  row. Tunable — raise to merge more aggressively, set to 0.0 to disable merging. */
private const val MERGE_THRESHOLD = 40.0

/**
 * JVM implementation of [extractColorPalette].
 *
 * No background trimming — the user supplies a screenshot already cropped to the scoreboard. Decode
 * the image as-is → run Color Thief → re-scan the pixels to compute per-color frequency (Color Thief
 * returns representative colors but no population). The image is re-encoded to PNG and returned
 * alongside so the LLM classifier and the screen see exactly what the palette was computed from.
 *
 * All blocking work runs on [Dispatchers.Default].
 */
internal actual suspend fun extractColorPalette(image: ImageFile): PaletteResult =
    withContext(Dispatchers.Default) {
        if (image.content.isEmpty()) error("Cannot extract palette from an empty image")
        val buffered = ImageIO.read(ByteArrayInputStream(image.content))
            ?: error("Failed to decode image for palette extraction")
        val palette = paletteWithFrequency(buffered)
        PaletteResult(palette = palette, analyzedImagePng = encodeToPng(buffered))
    }

private fun encodeToPng(image: BufferedImage): ByteArray {
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
}

/**
 * Color Thief gives representative colors but no frequency, so we re-scan the pixels and assign each
 * to its nearest palette color (squared Euclidean on RGB — ordering is identical to real distance)
 * to compute the per-color share. `ignoreWhite = false`: white text and its anti-aliased shades are
 * first-class palette members here, not noise to drop.
 */
private fun paletteWithFrequency(image: BufferedImage): List<ClusterInfo> {
    val raw = ColorThief.getPalette(image, PALETTE_COLOR_COUNT, PALETTE_QUALITY, false)
        ?: return emptyList()
    val palette = raw.map { RgbColor(it[0], it[1], it[2]) }
    if (palette.isEmpty()) return emptyList()

    val width = image.width
    val height = image.height
    val argb = image.getRGB(0, 0, width, height, null, 0, width)

    val counts = IntArray(palette.size)
    var total = 0
    var i = 0
    while (i < argb.size) {
        val r = (argb[i] shr 16) and 0xFF
        val g = (argb[i] shr 8) and 0xFF
        val b = argb[i] and 0xFF
        var bestIdx = 0
        var bestDist = Int.MAX_VALUE
        for (j in palette.indices) {
            val p = palette[j]
            val dr = r - p.r
            val dg = g - p.g
            val db = b - p.b
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) {
                bestDist = d
                bestIdx = j
            }
        }
        counts[bestIdx]++
        total++
        i += FREQUENCY_STEP
    }

    val merged = mergeCloseColors(
        entries = palette.mapIndexed { idx, c -> c to counts[idx] },
        threshold = MERGE_THRESHOLD,
    )
    val denom = if (total > 0) total.toDouble() else 1.0
    return merged.map { (color, count) ->
        ClusterInfo(centroid = color, pixelCount = count, share = count / denom)
    }.sortedByDescending { it.pixelCount }
}

/**
 * Merges palette entries whose RGB distance is within [threshold] (greedy, most-populous-first).
 * Each color joins the first existing cluster within the threshold, else seeds a new one; the
 * cluster's representative is the count-weighted average of its members and its count is their sum,
 * so the per-color shares still sum to 1. Collapses near-duplicate shades (e.g. a family of greens)
 * onto one row. Zero-count entries (a palette color no sampled pixel was nearest to) are dropped.
 */
private fun mergeCloseColors(entries: List<Pair<RgbColor, Int>>, threshold: Double): List<Pair<RgbColor, Int>> {
    val clusters = ArrayList<ColorAccumulator>()
    for ((color, count) in entries.filter { it.second > 0 }.sortedByDescending { it.second }) {
        val target = clusters.firstOrNull { it.centroid.distanceTo(color) <= threshold }
        if (target != null) target.add(color, count) else clusters.add(ColorAccumulator(color, count))
    }
    return clusters.map { it.centroid to it.count.toInt() }
}

/** Running count-weighted RGB accumulator for one merge cluster. */
private class ColorAccumulator(color: RgbColor, count: Int) {
    private val seed: RgbColor = color
    private var sumR = color.r.toDouble() * count
    private var sumG = color.g.toDouble() * count
    private var sumB = color.b.toDouble() * count
    private var pixelCount: Long = count.toLong()
    val count: Long get() = pixelCount
    val centroid: RgbColor
        get() = if (pixelCount == 0L) seed else RgbColor(
            (sumR / pixelCount).toInt().coerceIn(0, 255),
            (sumG / pixelCount).toInt().coerceIn(0, 255),
            (sumB / pixelCount).toInt().coerceIn(0, 255),
        )

    fun add(color: RgbColor, count: Int) {
        sumR += color.r.toDouble() * count
        sumG += color.g.toDouble() * count
        sumB += color.b.toDouble() * count
        pixelCount += count.toLong()
    }
}
