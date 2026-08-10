package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** Each noise rect is filled expanded by this many px in every direction, so a detected element rect
 *  that is slightly smaller than the real glyph/flag (the contrast detector under-covers edges)
 *  doesn't leave a colored remnant behind. */
private const val FILL_PAD_PX = 3

/** When sampling the band background, exclude a noise rect's bbox expanded by this many px (don't
 *  sample flag/glyph pixels that bleed just outside the detected rect). */
private const val BG_EXCLUDE_PAD = 2

/** Quantization step (per BGR channel) for the band-color mode. */
private const val BG_QUANT = 8

// BGR drawing colors for the markup overlay (element outline by classification).
private val KEEP_COLOR = Scalar(0.0, 255.0, 0.0) // green
private val FLAG_COLOR = Scalar(0.0, 0.0, 255.0) // red
private val SEED_COLOR = Scalar(255.0, 0.0, 0.0) // blue
private val COUNTRY_CODE_COLOR = Scalar(255.0, 0.0, 255.0) // magenta
private val LABEL_OUTLINE = Scalar(0.0, 0.0, 0.0)
private val LABEL_FILL = Scalar(255.0, 255.0, 255.0)

internal actual fun renderNoiseCleaning(
    image: ImageFile,
    elements: ScoreboardElements,
    noise: NoiseClassification,
): NoiseCleaningArtifacts {
    ensureOpenCvLoaded()
    val color = decodeToMat(image.content)
    try {
        val typeByIndex = HashMap<Int, NoiseType>()
        for (n in noise.elements) {
            for (idx in n.elementIndices) typeByIndex.putIfAbsent(idx, n.type)
        }
        val markup = renderMarkup(color, elements, typeByIndex)
        val cleaned = renderCleaned(color, noise.rects())
        return NoiseCleaningArtifacts(markup, cleaned)
    } finally {
        color.release()
    }
}

/** Every element outlined by its classification (green=keep, red=flag, blue=seed, magenta=code). */
private fun renderMarkup(color: Mat, elements: ScoreboardElements, typeByIndex: Map<Int, NoiseType>): ByteArray {
    val out = color.clone()
    try {
        val height = out.height()
        val thickness = (height / 240.0).toInt().coerceAtLeast(2)
        val fontScale = (height / 240.0).coerceAtLeast(0.4)
        for (e in elements.elements) {
            val type = typeByIndex[e.index]
            val c = when (type) {
                NoiseType.FLAG -> FLAG_COLOR
                NoiseType.SEED -> SEED_COLOR
                NoiseType.COUNTRY_CODE -> COUNTRY_CODE_COLOR
                null -> KEEP_COLOR
            }
            val r = e.rect
            Imgproc.rectangle(
                out,
                Point(r.x.toDouble(), r.y.toDouble()),
                Point((r.x + r.width).toDouble(), (r.y + r.height).toDouble()),
                c,
                thickness,
            )
            if (type != null) drawTypeLabel(out, type, r.x, r.y, fontScale, thickness)
        }
        return encodePng(out)
    } finally {
        out.release()
    }
}

private fun drawTypeLabel(out: Mat, type: NoiseType, x: Int, y: Int, fontScale: Double, thickness: Int) {
    val text = type.name
    val pos = Point(x.toDouble(), (y - 4).coerceAtLeast(12).toDouble())
    Imgproc.putText(out, text, pos, Imgproc.FONT_HERSHEY_SIMPLEX, fontScale * 0.6, LABEL_OUTLINE, thickness + 2)
    Imgproc.putText(out, text, pos, Imgproc.FONT_HERSHEY_SIMPLEX, fontScale * 0.6, LABEL_FILL, thickness)
}

/** Each noise rect painted over with the local band background → only essential data remains. */
private fun renderCleaned(color: Mat, noiseRects: List<RoiRect>): ByteArray {
    val out = color.clone()
    try {
        // Fill larger rects first (flags) so smaller ones (seeds) land on the already-corrected band.
        val sorted = noiseRects.sortedByDescending { it.width * it.height }
        for (r in sorted) {
            val bg = bandBackgroundMode(out, r, noiseRects)
            val px = (r.x - FILL_PAD_PX).coerceAtLeast(0)
            val py = (r.y - FILL_PAD_PX).coerceAtLeast(0)
            val px2 = (r.x + r.width + FILL_PAD_PX)
            val py2 = (r.y + r.height + FILL_PAD_PX)
            Imgproc.rectangle(
                out,
                Point(px.toDouble(), py.toDouble()),
                Point(px2.toDouble(), py2.toDouble()),
                bg,
                -1,
            )
        }
        return encodePng(out)
    } finally {
        out.release()
    }
}

/**
 * Mode color of the band the noise element sits on. Samples a horizontal strip across the FULL
 * width at the element's vertical extent, EXCLUDING all [exclude] noise rects, and returns the most
 * frequent quantized color. Text/glyphs are sparse, so the band background dominates the mode —
 * this is robust even when the element rect is smaller than the real glyph/flag (a tight ring would
 * then sample the glyph itself; the full-row mode does not).
 */
private fun bandBackgroundMode(mat: Mat, rect: RoiRect, exclude: List<RoiRect>): Scalar {
    val width = mat.width()
    val height = mat.height()
    val y0 = (rect.y).coerceIn(0, height - 1)
    val y1 = (rect.y + rect.height).coerceIn(0, height - 1)

    fun excluded(x: Int, y: Int): Boolean = exclude.any {
        x in (it.x - BG_EXCLUDE_PAD)..(it.x + it.width + BG_EXCLUDE_PAD) &&
            y in (it.y - BG_EXCLUDE_PAD)..(it.y + it.height + BG_EXCLUDE_PAD)
    }

    val counts = HashMap<Long, IntArray>() // key -> [count, sumB, sumG, sumR]
    fun sample(x: Int, y: Int) {
        // no-arg get() returns a double[] and is compatible with every Mat type.
        val px = mat.get(y, x)
        val key = packQuantized(px[0], px[1], px[2])
        val bin = counts.getOrPut(key) { IntArray(4) }
        bin[0]++
        bin[1] += px[0].toInt()
        bin[2] += px[1].toInt()
        bin[3] += px[2].toInt()
    }

    for (y in y0..y1) {
        for (x in 0 until width) {
            if (excluded(x, y)) continue
            sample(x, y)
        }
    }

    val best = counts.maxByOrNull { it.value[0] }
    if (best == null || best.value[0] == 0) {
        // Fall back to a single pixel just outside the rect.
        val fx = (rect.x - 1).coerceIn(0, width - 1)
        val fy = (rect.y - 1).coerceIn(0, height - 1)
        val p = mat.get(fy, fx)
        return Scalar(p[0], p[1], p[2])
    }
    val bin = best.value
    val count = bin[0]
    return Scalar(bin[1] / count.toDouble(), bin[2] / count.toDouble(), bin[3] / count.toDouble())
}

private fun packQuantized(b: Double, g: Double, r: Double): Long {
    val bq = (b.toInt() / BG_QUANT).coerceIn(0, 31)
    val gq = (g.toInt() / BG_QUANT).coerceIn(0, 31)
    val rq = (r.toInt() / BG_QUANT).coerceIn(0, 31)
    return ((bq.toLong() * 32 + gq) * 32 + rq)
}

private fun encodePng(mat: Mat): ByteArray {
    val buffer = MatOfByte()
    Imgcodecs.imencode(".png", mat, buffer)
    return buffer.toArray().also { buffer.release() }
}
