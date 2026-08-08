package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeColor
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeContent
import kotlin.math.sqrt

/**
 * Pure-KMP result model of the OpenCV color analysis. Holds the raw dominant colors
 * discovered in the image plus enough debug data to print a human-readable report.
 *
 * No OpenCV types here on purpose: this lives in commonMain so the repository
 * ([com.bashkevich.scoreboardthemerecognizer.model.theme.repository.ThemeRepositoryImpl])
 * can consume it without depending on the jvmMain-only OpenCV bindings.
 */
data class ScoreboardPalette(
    val backgroundColor: RgbColor,
    val textColor: RgbColor,
    val accents: List<RgbColor>,
    val scoreboardRoi: RoiRect,
    val detectedTextRoi: RoiRect,
    val imageWidth: Int,
    val imageHeight: Int,
    val bgTextClusters: List<ClusterInfo>,
    val accentClusters: List<ClusterInfo>,
    val usedWholeImageFallback: Boolean,
)

/** One K-Means cluster: its average color, how many pixels fell into it, and their share of the ROI. */
data class ClusterInfo(
    val centroid: RgbColor,
    val pixelCount: Int,
    val share: Double,
)

/** Integer rectangle in image pixel space. */
data class RoiRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * RGB color in 0..255. Carries the small bit of color math the palette mapping needs
 * (luminance, contrast text, saturation, distance) so that [ThemeContent] can be derived
 * from the discovered background/text/accent colors without an LLM.
 */
data class RgbColor(val r: Int, val g: Int, val b: Int) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) { "RGB components must be in 0..255" }
    }

    fun toHex(): String = "#" + r.toHexByte() + g.toHexByte() + b.toHexByte()

    fun toThemeColor(alpha: Float = 1f): ThemeColor = ThemeColor(color = toHex(), alpha = alpha)

    /** Perceptual luminance (Rec. 601 weights), 0..255. */
    fun luminance(): Double = 0.299 * r + 0.587 * g + 0.114 * b

    /** Returns black or white, whichever reads better on top of this color. */
    fun contrastText(): RgbColor = if (luminance() > 128.0) BLACK else WHITE

    /** Rough saturation: spread between the strongest and weakest channel, 0..255. */
    fun saturation(): Int = maxOf(r, g, b) - minOf(r, g, b)

    fun distanceTo(other: RgbColor): Double {
        val dr = (r - other.r).toDouble()
        val dg = (g - other.g).toDouble()
        val db = (b - other.b).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /** A visible "band" color derived from this one: lighten dark colors, darken light ones. */
    fun shiftedBand(): RgbColor = if (luminance() < 128.0) lighten(0.4) else darken(0.4)

    fun lighten(factor: Double): RgbColor = RgbColor(
        r = (r + (255 - r) * factor).toInt().coerceIn(0, 255),
        g = (g + (255 - g) * factor).toInt().coerceIn(0, 255),
        b = (b + (255 - b) * factor).toInt().coerceIn(0, 255),
    )

    fun darken(factor: Double): RgbColor = RgbColor(
        r = (r * (1.0 - factor)).toInt().coerceIn(0, 255),
        g = (g * (1.0 - factor)).toInt().coerceIn(0, 255),
        b = (b * (1.0 - factor)).toInt().coerceIn(0, 255),
    )

    private fun Int.toHexByte(): String {
        val v = coerceIn(0, 255)
        return HEX[v / 16].toString() + HEX[v % 16].toString()
    }

    companion object {
        private val HEX = "0123456789ABCDEF".toCharArray()
        val BLACK = RgbColor(0, 0, 0)
        val WHITE = RgbColor(255, 255, 255)
    }
}

/**
 * Maps the analyzed palette to a full [ThemeContent].
 *
 * Stage 1 (this) extracts every color from the image the same way — via K-Means on the
 * scoreboard ROI. Background and text come straight from the two dominant clusters; the
 * remaining accent colors are assigned to the semantic roles (serve / current set / current
 * game) by brightness/saturation/contrast heuristics. These assignments are intentionally
 * crude: the LLM Reasoning Layer (Stage 2) will refine which accent means what.
 */
fun ScoreboardPalette.toThemeContent(): ThemeContent {
    val distinct = accents
    val serve = distinct.maxByOrNull { it.saturation() } ?: textColor
    val afterServe = distinct.filter { it != serve }
    val currentSetBg = afterServe.maxByOrNull { it.distanceTo(backgroundColor) }
        ?: backgroundColor.shiftedBand()
    val afterSet = afterServe.filter { it != currentSetBg }
    val currentGameBg = afterSet.firstOrNull() ?: backgroundColor.contrastText()

    return ThemeContent(
        mainBackgroundColor = backgroundColor.toThemeColor(),
        mainTextColor = textColor.toThemeColor(),
        serveColor = serve.toThemeColor(),
        previousSetWinTextColor = textColor.toThemeColor(),
        previousSetLoseTextColor = textColor.toThemeColor(alpha = 0.5f),
        currentSetBackgroundColor = currentSetBg.toThemeColor(),
        currentSetTextColor = currentSetBg.contrastText().toThemeColor(),
        currentGameBackgroundColor = currentGameBg.toThemeColor(),
        currentGameTextColor = currentGameBg.contrastText().toThemeColor(),
    )
}
