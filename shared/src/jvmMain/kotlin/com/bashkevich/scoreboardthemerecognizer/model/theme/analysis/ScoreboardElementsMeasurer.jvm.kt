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

private val THEME_JSON = Json { prettyPrint = true; encodeDefaults = true }

private const val MARGIN_FRACTION = 0.10
private const val QUANT_BITS = 5
private const val MIN_PIXELS = 4
private const val BG_TOLERANCE = 60.0
private const val GLYPH_CLUSTER_RADIUS = 80.0
private const val OUTER_BAND_PX = 4

private val DEBUG_OVERLAY_ENABLED: Boolean =
    System.getenv("SCOREBOARD_DEBUG_OVERLAY")?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false

private const val DEBUG_OVERLAY_FILE = "scoreboard-debug-layout.png"

internal actual suspend fun measureScoreboardElements(
    image: ImageFile,
    elements: ScoreboardElements,
    roles: ElementRoles,
): ScoreboardComponents = withContext(Dispatchers.Default) {
    ensureOpenCvLoaded()
    if (image.content.isEmpty()) error("Cannot measure an empty image")
    val color = decodeToMat(image.content)
    try {
        measureElementsMat(color, elements, roles)
    } finally {
        color.release()
    }
}

private fun measureElementsMat(color: Mat, elements: ScoreboardElements, roles: ElementRoles): ScoreboardComponents {
    val width = color.width()
    val height = color.height()

    fun rectOf(index: Int?): RoiRect? = index?.let { elements.elements.getOrNull(it)?.rect }
    val mainTextBox = rectOf(roles.mainTextElement)
    val serveBox = rectOf(roles.serveElement)
    val prevWinBox = rectOf(roles.prevSetWinElement)
    val prevLoseBox = rectOf(roles.prevSetLoseElement)
    val curSetBox = rectOf(roles.currentSetElement)
    val curGameBox = rectOf(roles.currentGameElement)

    fun regionHist(region: RoiRect): Histogram {
        val r = clampRect(region, width, height)
        val sub = color.submat(Rect(r.x, r.y, r.width, r.height))
        val h = buildHistogram(sub).also { sub.release() }
        return h
    }

    fun cellBg(box: RoiRect): RgbColor? =
        fillColor(outerBandHist(color, box, OUTER_BAND_PX), MIN_PIXELS) ?: fillColor(regionHist(box), MIN_PIXELS)

    fun textOf(box: RoiRect, fallbackBg: RgbColor): RgbColor? {
        val localBg = cellBg(box) ?: fallbackBg
        return glyphColor(regionHist(inset(box, MARGIN_FRACTION)), localBg, BG_TOLERANCE, MIN_PIXELS)
    }

    val mainBg = mainTextBox?.let { cellBg(it) }
    val mainBgRef = mainBg ?: RgbColor.BLACK
    val mainText = mainTextBox?.let { textOf(it, mainBgRef) }
    val mainTextRef = mainText ?: RgbColor.WHITE

    val serve = serveBox?.let {
        serveColor(regionHist(inset(it, MARGIN_FRACTION)), cellBg(it) ?: mainBgRef, mainTextRef, BG_TOLERANCE, MIN_PIXELS)
    }
    val prevWin = prevWinBox?.let { textOf(it, mainBgRef) }
    val prevLose = prevLoseBox?.let { textOf(it, mainBgRef) }
    val curSetBg = curSetBox?.let { cellBg(it) }
    val curSetText = curSetBox?.let { textOf(it, curSetBg ?: mainBgRef) }
    val curGameBg = curGameBox?.let { cellBg(it) }
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
    writeElementsOverlay(color, elements, components)
    println("$TAG ── resulting theme (JSON) ──\n" + THEME_JSON.encodeToString(result.toThemeContent()))
    return result
}

// ---- geometry ----------------------------------------------------------------

private val ZERO = RoiRect(0, 0, 0, 0)

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

// ---- color measurement (histogram mode + background-distance) ----------------

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
            if (x >= bx0 && x < bx1 && y >= by0 && y < by1) continue
            val px = mat.get(y, x)
            hist.add(px[0].toInt(), px[1].toInt(), px[2].toInt())
        }
    }
    return hist
}

private fun buildHistogram(region: Mat): Histogram {
    val h = Histogram(QUANT_BITS)
    for (y in 0 until region.rows()) {
        for (x in 0 until region.cols()) {
            val px = region.get(y, x)
            h.add(px[0].toInt(), px[1].toInt(), px[2].toInt())
        }
    }
    return h
}

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

private fun fillColor(h: Histogram, minPixels: Int): RgbColor? =
    if (h.total < minPixels) null else h.best { true }

private fun glyphColor(h: Histogram, refBg: RgbColor, tolerance: Double, minPixels: Int): RgbColor? {
    if (h.total < minPixels) return null
    val countFloor = (h.total / 100).coerceAtLeast(2L)
    val extreme = h.bestScored(minCount = countFloor, minScore = tolerance) { it.distanceTo(refBg) }
        ?: return null
    return h.bestWithFloor(minCount = countFloor) {
        it.distanceTo(refBg) > tolerance && it.distanceTo(extreme) <= GLYPH_CLUSTER_RADIUS
    } ?: extreme
}

private fun serveColor(h: Histogram, bg: RgbColor, mainText: RgbColor, tolerance: Double, minPixels: Int): RgbColor? {
    if (h.total < minPixels) return null
    val countFloor = (h.total / 100).coerceAtLeast(2L)
    val score: (RgbColor) -> Double = { c -> minOf(c.distanceTo(bg), c.distanceTo(mainText)) }
    val extreme = h.bestScored(countFloor, tolerance, score) ?: return null
    return h.bestWithFloor(countFloor) { c ->
        score(c) > tolerance && c.distanceTo(extreme) <= GLYPH_CLUSTER_RADIUS
    } ?: extreme
}

// ---- report + debug overlay --------------------------------------------------

private fun printReport(width: Int, height: Int, components: List<ComponentRect>) {
    val lines = buildList {
        add("$TAG ── element measurement (OpenCV elements → LLM roles → measure) ──")
        add("  image size : ${width}x${height}")
        for (c in components) {
            val roles = buildList {
                c.background?.let { add("bg=${it.toHex()}") }
                c.text?.let { add("text=${it.toHex()}") }
            }.joinToString("  ").ifEmpty { "(none)" }
            add("  ${c.role.name.padEnd(14)}: $roles  rect=[${c.llmBox.x},${c.llmBox.y} ${c.llmBox.width}x${c.llmBox.height}]")
        }
        add("$TAG ── end ──")
    }
    println(lines.joinToString("\n"))
}

private val ELEMENT_COLOR = Scalar(0.0, 255.0, 255.0) // yellow (all detected elements)
private val ROLE_COLOR = Scalar(0.0, 255.0, 0.0) // green (role-assigned elements)
private val LABEL_BG = Scalar(0.0, 0.0, 0.0)
private val LABEL_FG = Scalar(255.0, 255.0, 255.0)

private fun writeElementsOverlay(color: Mat, elements: ScoreboardElements, components: List<ComponentRect>) {
    if (!DEBUG_OVERLAY_ENABLED) return
    val width = color.width()
    val height = color.height()
    val fontScale = (height / 240.0).coerceAtLeast(0.4)
    val thickness = (height / 240.0).toInt().coerceAtLeast(1)

    fun drawRect(r: RoiRect, c: Scalar, t: Int) {
        val x2 = (r.x + r.width).coerceAtMost(width)
        val y2 = (r.y + r.height).coerceAtMost(height)
        Imgproc.rectangle(color, Point(r.x.toDouble(), r.y.toDouble()), Point(x2.toDouble(), y2.toDouble()), c, t)
    }

    fun drawLabel(text: String, x: Int, y: Int) {
        val padH = (height * 0.06).toInt().coerceAtLeast(14)
        val padW = (text.length * fontScale * 9).toInt().coerceAtLeast(40)
        val padY = (y - 3).coerceAtLeast(padH)
        Imgproc.rectangle(color, Point(x.toDouble(), (padY - padH).toDouble()), Point((x + padW).toDouble(), (padY + 2).toDouble()), LABEL_BG, -1)
        Imgproc.putText(color, text, Point((x + 2).toDouble(), padY.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, fontScale, LABEL_FG, thickness)
    }

    // All detected elements (faint yellow, numbered).
    for (e in elements.elements) {
        drawRect(e.rect, ELEMENT_COLOR, 1)
        Imgproc.putText(
            color,
            e.index.toString(),
            Point((e.rect.x + 2).toDouble(), (e.rect.y - 3).coerceAtLeast(12).toDouble()),
            Imgproc.FONT_HERSHEY_SIMPLEX,
            fontScale * 0.8,
            LABEL_FG,
            thickness,
        )
    }
    // Role-assigned elements (green, labeled with role + measured colors).
    for (c in components) {
        if (c.llmBox.width <= 0 || c.llmBox.height <= 0) continue
        drawRect(c.llmBox, ROLE_COLOR, thickness)
        val label = buildString {
            append(c.role.name)
            c.background?.let { append(" bg=").append(it.toHex()) }
            c.text?.let { append(" text=").append(it.toHex()) }
        }
        drawLabel(label, c.llmBox.x, c.llmBox.y)
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

internal fun projectRoot(): File {
    var cur: File = File(".").absoluteFile.canonicalFile
    while (cur.parentFile != null) {
        if (File(cur, "settings.gradle.kts").exists()) return cur
        cur = cur.parentFile
    }
    return File(".").absoluteFile.canonicalFile
}
