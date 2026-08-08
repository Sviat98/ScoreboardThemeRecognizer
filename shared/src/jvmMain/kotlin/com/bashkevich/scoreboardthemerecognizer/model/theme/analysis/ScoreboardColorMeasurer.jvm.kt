package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

private const val TAG = "[ThemeRecognizer]"

/** Pretty-printer for the resulting theme JSON dumped to the console. */
private val THEME_JSON = Json { prettyPrint = true; encodeDefaults = true }

/** Fraction of a region's pixels to discard on each side (drops anti-aliased edges / color mixing). */
private const val MARGIN_FRACTION = 0.10

/** Histogram quantization (bits per channel; 5 → 32 levels — fine enough that navy ≠ black). */
private const val QUANT_BITS = 5

/** Minimum pixels in a region for its measurement to count. */
private const val MIN_PIXELS = 4

/** A pixel counts as "background" when within this Euclidean RGB distance of the reference fill. */
private const val BG_TOLERANCE = 60.0

/** Two buckets this close (RGB) belong to the same glyph cluster (solid fill + its anti-aliasing). */
private const val GLYPH_CLUSTER_RADIUS = 80.0

/** How far outside a box to sample the surrounding cell background (px). Robust to tight boxes whose
 *  own interior is dominated by the digit's anti-aliasing halo rather than the real cell fill. */
private const val OUTER_BAND_PX = 4

/**
 * When env `SCOREBOARD_DEBUG_OVERLAY` is `1`/`true`, writes a PNG with the LLM + refined boxes drawn
 * over the image into the project root. Defaults off so unit tests and normal runs don't write files.
 */
private val DEBUG_OVERLAY_ENABLED: Boolean =
    System.getenv("SCOREBOARD_DEBUG_OVERLAY")?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false

private const val DEBUG_OVERLAY_FILE = "scoreboard-debug-layout.png"

internal actual suspend fun measureComponentsColors(
    image: ImageFile,
    layout: AiComponentLayout,
): ScoreboardComponents = withContext(Dispatchers.Default) {
    ensureOpenCvLoaded()
    if (image.content.isEmpty()) error("Cannot measure an empty image")
    val color = decodeToMat(image.content)
    try {
        measureMat(color, layout)
    } finally {
        color.release()
    }
}

private fun measureMat(color: Mat, layout: AiComponentLayout): ScoreboardComponents {
    val width = color.width()
    val height = color.height()

    val boxes: Map<ComponentRole, RoiRect> = layout.components.mapNotNull { ab ->
        val role = parseRole(ab.role) ?: return@mapNotNull null
        role to toPixelBox(ab, width, height)
    }.toMap()

    fun regionHist(region: RoiRect): Histogram {
        val r = clampRect(region, width, height)
        val sub = color.submat(Rect(r.x, r.y, r.width, r.height))
        val h = buildHistogram(sub).also { sub.release() }
        return h
    }

    val mainTextBox = boxes[ComponentRole.MAIN_TEXT]
    val curSetBox = boxes[ComponentRole.CURRENT_SET]
    val curGameBox = boxes[ComponentRole.CURRENT_GAME]
    val serveBox = boxes[ComponentRole.SERVE]
    val prevWinBox = boxes[ComponentRole.PREV_SET_WIN]
    val prevLoseBox = boxes[ComponentRole.PREV_SET_LOSE]

    // 1. FILL backgrounds — dominant color of the band just OUTSIDE each box (the surrounding cell
    // background). This is robust to a tight box whose interior is dominated by the digit's
    // anti-aliasing halo (which would otherwise be misread as the cell fill); falls back to the
    // full-box mode if the outer band is empty (box touches an image edge).
    fun cellBg(box: RoiRect): RgbColor? =
        fillColor(outerBandHist(color, box, OUTER_BAND_PX), MIN_PIXELS) ?: fillColor(regionHist(box), MIN_PIXELS)

    val mainBg = mainTextBox?.let { cellBg(it) }
    val curSetBg = curSetBox?.let { cellBg(it) }
    val curGameBg = curGameBox?.let { cellBg(it) }
    val mainBgRef = mainBg ?: RgbColor.BLACK

    // 2-6. Glyph text colors. Each glyph is read against the SAME outer-band background as the fills
    // (the cell background surrounding the box) — NOT the box interior. That keeps a tight box (where
    // the glyph is the majority of the box) from being misread: the reference background still comes
    // from outside the glyph, so the glyph is always "what differs most from the background", whether
    // the box is loose or tight, or sits at the image edge. Text = farthest-from-bg bucket to find
    // the glyph (even a tiny serve ball), then the MODE of that cluster for the solid color.
    fun textOf(box: RoiRect, fallbackBg: RgbColor): RgbColor? {
        val localBg = cellBg(box) ?: fallbackBg
        return glyphColor(regionHist(inset(box, MARGIN_FRACTION)), localBg, BG_TOLERANCE, MIN_PIXELS)
    }

    val mainText = mainTextBox?.let { textOf(it, mainBgRef) }
    val serve = serveBox?.let { textOf(it, mainBgRef) }
    val prevWin = prevWinBox?.let { textOf(it, mainBgRef) }
    val prevLose = prevLoseBox?.let { textOf(it, mainBgRef) }
    val curSetText = curSetBox?.let { textOf(it, curSetBg ?: mainBgRef) }
    val curGameText = curGameBox?.let { textOf(it, curGameBg ?: mainBgRef) }

    fun refinedOf(box: RoiRect?): RoiRect = box?.let { inset(it, MARGIN_FRACTION) } ?: ZERO

    val components = listOf(
        ComponentRect(ComponentRole.MAIN_TEXT, mainTextBox ?: ZERO, refinedOf(mainTextBox), background = mainBg, text = mainText),
        ComponentRect(ComponentRole.SERVE, serveBox ?: ZERO, refinedOf(serveBox), text = serve),
        ComponentRect(ComponentRole.PREV_SET_WIN, prevWinBox ?: ZERO, refinedOf(prevWinBox), text = prevWin),
        ComponentRect(ComponentRole.PREV_SET_LOSE, prevLoseBox ?: ZERO, refinedOf(prevLoseBox), text = prevLose),
        ComponentRect(ComponentRole.CURRENT_SET, curSetBox ?: ZERO, refinedOf(curSetBox), background = curSetBg, text = curSetText),
        ComponentRect(ComponentRole.CURRENT_GAME, curGameBox ?: ZERO, refinedOf(curGameBox), background = curGameBg, text = curGameText),
    )

    printReport(width, height, components)
    val result = ScoreboardComponents(width, height, components)
    writeDebugOverlay(color, layout, result)
    println(
        "$TAG ── resulting theme (JSON) ──\n" +
            THEME_JSON.encodeToString(result.toThemeContent())
    )
    return result
}

// ---- box geometry -----------------------------------------------------------

private val ZERO = RoiRect(0, 0, 0, 0)

private fun parseRole(s: String): ComponentRole? = when (s.trim().lowercase()) {
    "main_text" -> ComponentRole.MAIN_TEXT
    "serve" -> ComponentRole.SERVE
    "prev_set_win" -> ComponentRole.PREV_SET_WIN
    "prev_set_lose" -> ComponentRole.PREV_SET_LOSE
    "current_set" -> ComponentRole.CURRENT_SET
    "current_game" -> ComponentRole.CURRENT_GAME
    else -> null
}

private fun toPixelBox(box: AiBox, width: Int, height: Int): RoiRect {
    val x = (box.x.coerceIn(0.0, 1.0) * width).toInt()
    val y = (box.y.coerceIn(0.0, 1.0) * height).toInt()
    val w = (box.w.coerceIn(0.0, 1.0) * width).toInt().coerceAtLeast(1)
    val h = (box.h.coerceIn(0.0, 1.0) * height).toInt().coerceAtLeast(1)
    return clampRect(RoiRect(x, y, w, h), width, height)
}

private fun clampRect(r: RoiRect, width: Int, height: Int): RoiRect {
    val x = r.x.coerceIn(0, (width - 1).coerceAtLeast(0))
    val y = r.y.coerceIn(0, (height - 1).coerceAtLeast(0))
    val x2 = (r.x + r.width).coerceIn(x + 1, width)
    val y2 = (r.y + r.height).coerceIn(y + 1, height)
    return RoiRect(x, y, x2 - x, y2 - y)
}

private fun inset(r: RoiRect, frac: Double): RoiRect {
    val dx = (r.width * frac).toInt()
    val dy = (r.height * frac).toInt()
    val w = (r.width - 2 * dx).coerceAtLeast(1)
    val h = (r.height - 2 * dy).coerceAtLeast(1)
    return RoiRect(r.x + dx, r.y + dy, w, h)
}

// ---- color measurement (histogram mode + background-distance) --------------

/**
 * Histogram of the [padPx]-pixel band just OUTSIDE [box] (the expanded rect minus the box itself).
 * Its dominant color is the cell background surrounding the glyph — robust to a tight box whose own
 * interior is dominated by the digit's anti-aliasing halo.
 */
private fun outerBandHist(mat: Mat, box: RoiRect, padPx: Int): Histogram {
    val outer = clampRect(
        RoiRect(box.x - padPx, box.y - padPx, box.width + 2 * padPx, box.height + 2 * padPx),
        mat.width(), mat.height(),
    )
    val bx0 = box.x
    val bx1 = box.x + box.width
    val by0 = box.y
    val by1 = box.y + box.height
    val hist = Histogram(QUANT_BITS)
    for (y in outer.y until outer.y + outer.height) {
        for (x in outer.x until outer.x + outer.width) {
            if (x >= bx0 && x < bx1 && y >= by0 && y < by1) continue // skip the box itself
            val px = mat.get(y, x) // BGR
            hist.add(px[0].toInt(), px[1].toInt(), px[2].toInt())
        }
    }
    return hist
}

private fun buildHistogram(region: Mat): Histogram {
    val h = Histogram(QUANT_BITS)
    for (y in 0 until region.rows()) {
        for (x in 0 until region.cols()) {
            // Per-pixel get returns the channel values as doubles in BGR order; works on submats.
            val px = region.get(y, x)
            h.add(px[0].toInt(), px[1].toInt(), px[2].toInt())
        }
    }
    return h
}

/**
 * Quantized color histogram of a region. Each bucket stores a pixel count and per-channel sums, so
 * the returned center is the true per-pixel mean within the bucket (not the quantized value) — the
 * mode (most frequent bucket) therefore represents a flat fill bit-accurately. Mirrors
 * `ScoreboardColorExtractor.Histogram` from TennisScoreKeeperBackend.
 */
private class Histogram(quantBits: Int) {
    private val shift = 8 - quantBits
    private val gShift = quantBits
    private val rShift = 2 * quantBits

    private val counts = HashMap<Int, Long>()
    private val sumB = HashMap<Int, Long>()
    private val sumG = HashMap<Int, Long>()
    private val sumR = HashMap<Int, Long>()

    var total: Long = 0L
        private set

    fun add(b: Int, g: Int, r: Int) {
        val key = ((b shr shift) shl rShift) or ((g shr shift) shl gShift) or (r shr shift)
        counts.merge(key, 1L) { acc, v -> acc + v }
        sumB.merge(key, b.toLong()) { acc, v -> acc + v }
        sumG.merge(key, g.toLong()) { acc, v -> acc + v }
        sumR.merge(key, r.toLong()) { acc, v -> acc + v }
        total++
    }

    /** Most frequent bucket whose center satisfies [predicate]; ties broken by smaller key. */
    fun best(predicate: (RgbColor) -> Boolean): RgbColor? {
        var bestKey = -1
        var bestCount = 0L
        for ((key, count) in counts) {
            if (count == 0L) continue
            if (!predicate(center(key, count))) continue
            if (count > bestCount || (count == bestCount && (bestKey == -1 || key < bestKey))) {
                bestCount = count
                bestKey = key
            }
        }
        return if (bestKey == -1) null else center(bestKey, bestCount)
    }

    /**
     * Among buckets with at least [minCount] pixels whose [score] exceeds [minScore], returns the
     * one with the highest score. Used for GLYPH roles where the true color is the *extreme*
     * foreground (farthest from the background), not the most frequent: anti-aliasing mid-tones
     * would otherwise outnumber pure-stroke pixels and mute the measured text color (e.g. dark text
     * on a light fill coming out gray instead of near-black).
     */
    fun bestScored(minCount: Long, minScore: Double, score: (RgbColor) -> Double): RgbColor? {
        var bestKey = -1
        var bestCount = 0L
        var bestScore = minScore
        for ((key, count) in counts) {
            if (count < minCount) continue
            val s = score(center(key, count))
            if (s > bestScore) {
                bestScore = s
                bestKey = key
                bestCount = count
            }
        }
        return if (bestKey == -1) null else center(bestKey, bestCount)
    }

    /** Like [best] (most frequent bucket satisfying [predicate]) but ignores buckets with fewer than
     *  [minCount] pixels — drops sparse outlier buckets (JPEG/edge noise) without muting the color. */
    fun bestWithFloor(minCount: Long, predicate: (RgbColor) -> Boolean): RgbColor? {
        var bestKey = -1
        var bestCount = 0L
        for ((key, count) in counts) {
            if (count < minCount) continue
            if (!predicate(center(key, count))) continue
            if (count > bestCount || (count == bestCount && (bestKey == -1 || key < bestKey))) {
                bestCount = count
                bestKey = key
            }
        }
        return if (bestKey == -1) null else center(bestKey, bestCount)
    }

    private fun center(key: Int, count: Long): RgbColor {
        val r = (sumR.getValue(key).toDouble() / count).toInt().coerceIn(0, 255)
        val g = (sumG.getValue(key).toDouble() / count).toInt().coerceIn(0, 255)
        val b = (sumB.getValue(key).toDouble() / count).toInt().coerceIn(0, 255)
        return RgbColor(r, g, b)
    }
}

/** FILL role: the single most frequent bucket (the palette mode). */
private fun fillColor(h: Histogram, minPixels: Int): RgbColor? =
    if (h.total < minPixels) null else h.best { true }

/** GLYPH role: locates the glyph by its EXTREME bucket (farthest from the reference background —
 *  robust even when the glyph is a tiny minority, e.g. a serve ball, so the background's own JPEG
 *  shade variation can't outvote it), then returns the MODE of the buckets within [GLYPH_CLUSTER_RADIUS]
 *  of that extreme — the glyph's solid color, not an over-bright edge pixel. */
private fun glyphColor(h: Histogram, refBg: RgbColor, tolerance: Double, minPixels: Int): RgbColor? {
    if (h.total < minPixels) return null
    val countFloor = (h.total / 100).coerceAtLeast(2L) // ≥1% of pixels (min 2) — drop outlier buckets
    val extreme = h.bestScored(minCount = countFloor, minScore = tolerance) { it.distanceTo(refBg) }
        ?: return null
    return h.bestWithFloor(minCount = countFloor) {
        it.distanceTo(refBg) > tolerance && it.distanceTo(extreme) <= GLYPH_CLUSTER_RADIUS
    } ?: extreme
}

private fun printReport(width: Int, height: Int, components: List<ComponentRect>) {
    val lines = buildList {
        add("$TAG ── component measurement (LLM → OpenCV) ──")
        add("  image size : ${width}x${height}")
        for (c in components) {
            val roles = buildList {
                c.background?.let { add("bg=${it.toHex()}") }
                c.text?.let { add("text=${it.toHex()}") }
            }.joinToString("  ").ifEmpty { "(none)" }
            add(
                "  ${c.role.name.padEnd(14)}: $roles  " +
                    "llm=[${c.llmBox.x},${c.llmBox.y} ${c.llmBox.width}x${c.llmBox.height}]"
            )
        }
        add("$TAG ── end ──")
    }
    println(lines.joinToString("\n"))
}

// ---- debug overlay ----------------------------------------------------------

private val OVERLAY_RED = Scalar(0.0, 0.0, 255.0) // LLM box
private val OVERLAY_GREEN = Scalar(0.0, 255.0, 0.0) // refined (snapped) box
private val OVERLAY_BLACK = Scalar(0.0, 0.0, 0.0) // label background pad
private val OVERLAY_WHITE = Scalar(255.0, 255.0, 255.0) // label text

/**
 * Draws the LLM boxes (red) and the OpenCV-refined boxes (green) over the image, labels each with
 * its role and measured colors, and writes a PNG to the project root. A debugging aid to confirm
 * the coordinates the LLM returns actually land on the right elements, and to see where the snap
 * tightened them. Toggle with [DEBUG_OVERLAY_ENABLED].
 */
private fun writeDebugOverlay(color: Mat, layout: AiComponentLayout, components: ScoreboardComponents) {
    if (!DEBUG_OVERLAY_ENABLED) return
    val width = color.width()
    val height = color.height()
    val fontScale = (height / 240.0).coerceAtLeast(0.4)
    val thickness = (height / 240.0).toInt().coerceAtLeast(1)
    val byRole = components.components.associateBy { it.role }

    for (ab in layout.components) {
        val role = parseRole(ab.role) ?: continue
        val box = toPixelBox(ab, width, height)
        val x2 = (box.x + box.width).coerceAtMost(width)
        val y2 = (box.y + box.height).coerceAtMost(height)
        val comp = byRole[role]

        // LLM box — red outline.
        Imgproc.rectangle(
            color,
            Point(box.x.toDouble(), box.y.toDouble()),
            Point(x2.toDouble(), y2.toDouble()),
            OVERLAY_RED,
            thickness,
        )

        // Refined (snapped) box — green outline.
        comp?.refinedBox?.let { rb ->
            if (rb.width > 0 && rb.height > 0) {
                val rx2 = (rb.x + rb.width).coerceAtMost(width)
                val ry2 = (rb.y + rb.height).coerceAtMost(height)
                Imgproc.rectangle(
                    color,
                    Point(rb.x.toDouble(), rb.y.toDouble()),
                    Point(rx2.toDouble(), ry2.toDouble()),
                    OVERLAY_GREEN,
                    thickness,
                )
            }
        }

        // Label: "role bg=#RRGGBB text=#RRGGBB" on a black pad above the box.
        val label = buildString {
            append(ab.role)
            comp?.background?.let { append(" bg=").append(it.toHex()) }
            comp?.text?.let { append(" text=").append(it.toHex()) }
        }
        val padH = (height * 0.06).toInt().coerceAtLeast(14)
        val padW = (label.length * fontScale * 9).toInt().coerceAtLeast(40)
        val padY = (box.y - 3).coerceAtLeast(padH)
        Imgproc.rectangle(
            color,
            Point(box.x.toDouble(), (padY - padH).toDouble()),
            Point((box.x + padW).toDouble(), (padY + 2).toDouble()),
            OVERLAY_BLACK,
            -1,
        )
        Imgproc.putText(
            color,
            label,
            Point((box.x + 2).toDouble(), padY.toDouble()),
            Imgproc.FONT_HERSHEY_SIMPLEX,
            fontScale,
            OVERLAY_WHITE,
            thickness,
        )
    }

    val target = projectRoot().resolve(DEBUG_OVERLAY_FILE)
    try {
        target.writeBytes(encodeToPng(color))
        println("[ThemeRecognizer] debug overlay written to: ${target.absolutePath}")
    } catch (e: Exception) {
        println("[ThemeRecognizer] failed to write debug overlay to ${target.absolutePath}: ${e.message}")
    }
}

private fun encodeToPng(mat: Mat): ByteArray {
    val buffer = MatOfByte()
    Imgcodecs.imencode(".png", mat, buffer)
    return buffer.toArray().also { buffer.release() }
}

/** Walks up from the working directory to the folder holding `settings.gradle.kts` (the project root). */
internal fun projectRoot(): File {
    var cur: File = File(".").absoluteFile.canonicalFile
    while (cur.parentFile != null) {
        if (File(cur, "settings.gradle.kts").exists()) return cur
        cur = cur.parentFile
    }
    return File(".").absoluteFile.canonicalFile
}
