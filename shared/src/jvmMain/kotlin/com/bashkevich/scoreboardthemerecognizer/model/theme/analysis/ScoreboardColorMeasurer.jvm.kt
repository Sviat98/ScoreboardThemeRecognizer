package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

private const val TAG = "[ThemeRecognizer]"

/** How much to expand an LLM box before snapping (fraction of its size) — tolerance for LLM drift. */
private const val SNAP_EXPAND_FRACTION = 0.25

/** Fraction of a region's pixels to discard on each side (drops anti-aliased edges / color mixing). */
private const val MARGIN_FRACTION = 0.10

/** Histogram quantization (bits per channel; 5 → 32 levels — fine enough that navy ≠ black). */
private const val QUANT_BITS = 5

/** Minimum pixels in a region for its measurement to count. */
private const val MIN_PIXELS = 4

/** A pixel counts as "background" when within this Euclidean RGB distance of the reference fill. */
private const val BG_TOLERANCE = 60.0

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

    /** Expand the box, snap to the strongest foreground glyph; fall back to an inset of the box. */
    fun glyphRegionOf(box: RoiRect): RoiRect {
        val expanded = expand(box, width, height, SNAP_EXPAND_FRACTION)
        return snapToGlyph(color, expanded) ?: inset(box, MARGIN_FRACTION)
    }

    // 1. FILL backgrounds — inset region, take the dominant (most frequent) bucket.
    val mainTextBox = boxes[ComponentRole.MAIN_TEXT]
    val curSetBox = boxes[ComponentRole.CURRENT_SET]
    val curGameBox = boxes[ComponentRole.CURRENT_GAME]

    val mainBg = mainTextBox?.let { fillColor(regionHist(inset(it, MARGIN_FRACTION)), MIN_PIXELS) }
    val curSetBg = curSetBox?.let { fillColor(regionHist(inset(it, MARGIN_FRACTION)), MIN_PIXELS) }
    val curGameBg = curGameBox?.let { fillColor(regionHist(inset(it, MARGIN_FRACTION)), MIN_PIXELS) }

    val mainBgRef = mainBg ?: RgbColor.BLACK

    // 2. main text glyph (ref = main background).
    val mainTextRegion = mainTextBox?.let { glyphRegionOf(it) }
    val mainText = mainTextRegion?.let { glyphColor(regionHist(it), mainBgRef, BG_TOLERANCE, MIN_PIXELS) }
    // 3. serve — a solid glyph on the main background; its color is the dominant foreground bucket.
    val serveBox = boxes[ComponentRole.SERVE]
    val serveRegion = serveBox?.let { glyphRegionOf(it) }
    val serve = serveRegion?.let {
        serveColor(regionHist(it), mainBgRef, BG_TOLERANCE, MIN_PIXELS)
    }

    // 4. previous-set win/lose glyphs (ref = main background).
    val prevWinBox = boxes[ComponentRole.PREV_SET_WIN]
    val prevWinRegion = prevWinBox?.let { glyphRegionOf(it) }
    val prevWin = prevWinRegion?.let { glyphColor(regionHist(it), mainBgRef, BG_TOLERANCE, MIN_PIXELS) }
    val prevLoseBox = boxes[ComponentRole.PREV_SET_LOSE]
    val prevLoseRegion = prevLoseBox?.let { glyphRegionOf(it) }
    val prevLose = prevLoseRegion?.let { glyphColor(regionHist(it), mainBgRef, BG_TOLERANCE, MIN_PIXELS) }

    // 5/6. current set/game text glyphs (ref = own background).
    val curSetTextRegion = curSetBox?.let { glyphRegionOf(it) }
    val curSetText = curSetTextRegion?.let {
        glyphColor(regionHist(it), curSetBg ?: mainBgRef, BG_TOLERANCE, MIN_PIXELS)
    }
    val curGameTextRegion = curGameBox?.let { glyphRegionOf(it) }
    val curGameText = curGameTextRegion?.let {
        glyphColor(regionHist(it), curGameBg ?: mainBgRef, BG_TOLERANCE, MIN_PIXELS)
    }

    val components = listOf(
        ComponentRect(ComponentRole.MAIN_TEXT, mainTextBox ?: ZERO, mainTextRegion ?: mainTextBox ?: ZERO, background = mainBg, text = mainText),
        ComponentRect(ComponentRole.SERVE, serveBox ?: ZERO, serveRegion ?: serveBox ?: ZERO, text = serve),
        ComponentRect(ComponentRole.PREV_SET_WIN, prevWinBox ?: ZERO, prevWinRegion ?: prevWinBox ?: ZERO, text = prevWin),
        ComponentRect(ComponentRole.PREV_SET_LOSE, prevLoseBox ?: ZERO, prevLoseRegion ?: prevLoseBox ?: ZERO, text = prevLose),
        ComponentRect(ComponentRole.CURRENT_SET, curSetBox ?: ZERO, curSetTextRegion ?: curSetBox ?: ZERO, background = curSetBg, text = curSetText),
        ComponentRect(ComponentRole.CURRENT_GAME, curGameBox ?: ZERO, curGameTextRegion ?: curGameBox ?: ZERO, background = curGameBg, text = curGameText),
    )

    printReport(width, height, components)
    val result = ScoreboardComponents(width, height, components)
    writeDebugOverlay(color, layout, result)
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

private fun expand(r: RoiRect, width: Int, height: Int, frac: Double): RoiRect {
    val dx = (r.width * frac).toInt()
    val dy = (r.height * frac).toInt()
    return clampRect(RoiRect(r.x - dx, r.y - dy, r.width + 2 * dx, r.height + 2 * dy), width, height)
}

/**
 * Finds the single strongest foreground blob inside [region] and returns its tight bounding rect
 * (image coordinates). The foreground mask is computed by local contrast (reused from the Stage-1
 * analyzer) and morphologically closed so a glyph's outline ring collapses into one solid blob;
 * the largest connected component is the glyph. Returns null when nothing usable is found, so the
 * caller falls back to an inset of the original LLM box.
 */
private fun snapToGlyph(color: Mat, region: RoiRect): RoiRect? {
    val clamped = clampRect(region, color.width(), color.height())
    if (clamped.width < 3 || clamped.height < 3) return null
    val sub = color.submat(Rect(clamped.x, clamped.y, clamped.width, clamped.height))
    var gray: Mat? = null
    var mask: Mat? = null
    var labels: Mat? = null
    var stats: Mat? = null
    var centroids: Mat? = null
    var kernel: Mat? = null
    try {
        gray = Mat()
        Imgproc.cvtColor(sub, gray, Imgproc.COLOR_BGR2GRAY)
        mask = textForegroundMask(gray)

        // Close gaps in the glyph outline so the connected component is a solid blob.
        kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)

        labels = Mat()
        stats = Mat()
        centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8)
        val statsMat = stats // capture the now non-null stats for indexed reads below
        var best = -1
        var bestArea = 0.0
        for (i in 1 until count) {
            val area = statsMat.get(i, Imgproc.CC_STAT_AREA)[0]
            if (area > bestArea) {
                bestArea = area
                best = i
            }
        }
        if (best < 0) return null
        val left = statsMat.get(best, Imgproc.CC_STAT_LEFT)[0].toInt()
        val top = statsMat.get(best, Imgproc.CC_STAT_TOP)[0].toInt()
        val w = statsMat.get(best, Imgproc.CC_STAT_WIDTH)[0].toInt()
        val h = statsMat.get(best, Imgproc.CC_STAT_HEIGHT)[0].toInt()
        if (w < 2 || h < 2) return null
        return RoiRect(clamped.x + left, clamped.y + top, w, h)
    } finally {
        sub.release()
        gray?.release()
        mask?.release()
        labels?.release()
        stats?.release()
        centroids?.release()
        kernel?.release()
    }
}

// ---- color measurement (histogram mode + background-distance) --------------

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

/** GLYPH role: the bucket farthest from the reference background (the pure glyph color), among
 *  buckets with enough pixels to reject noise. Farthest — not most frequent — so anti-aliasing
 *  mid-tones can't outnumber pure-stroke pixels and mute the text color. */
private fun glyphColor(h: Histogram, refBg: RgbColor, tolerance: Double, minPixels: Int): RgbColor? {
    if (h.total < minPixels) return null
    val countFloor = (h.total / 100).coerceAtLeast(2L) // ≥1% of pixels (min 2) — drop outlier buckets
    return h.bestScored(minCount = countFloor, minScore = tolerance) { it.distanceTo(refBg) }
}

/** serve: a solid filled indicator (ball/dot), so its color is the most frequent bucket far from
 *  the main background. Mode is robust to a few outlier pixels and, unlike the thin-stroke text
 *  case, anti-aliasing does not dominate a filled glyph. We deliberately do NOT require the serve
 *  to differ from the main text — a serve can be nearly text-colored. */
private fun serveColor(h: Histogram, mainBg: RgbColor, bgTolerance: Double, minPixels: Int): RgbColor? {
    if (h.total < minPixels) return null
    return h.best { it.distanceTo(mainBg) > bgTolerance }
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
