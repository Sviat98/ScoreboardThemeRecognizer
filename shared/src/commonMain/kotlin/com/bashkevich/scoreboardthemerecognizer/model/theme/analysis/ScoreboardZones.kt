package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeColor
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeContent
import kotlin.math.sqrt

/**
 * Result of the zone-based scoreboard analysis. The image is sliced left→right into vertical
 * columns (names, previous sets, current set, current game); each zone is analyzed
 * independently and its dominant colors are assigned to semantic roles by *which zone they
 * came from*, not by heuristics. Pure-KMP (commonMain) so the repository can consume it
 * without depending on the jvmMain-only OpenCV bindings.
 */
data class ScoreboardZones(
    val imageWidth: Int,
    val imageHeight: Int,
    /** Raw content columns found by the vertical projection (diagnostics). */
    val detectedBlocks: List<RoiRect>,
    val zones: List<ZoneAnalysis>,
    /** True when column auto-detection failed and equal quarters were used instead. */
    val usedFallback: Boolean,
    /** Rect within the original upload the surrounding background was trimmed to; null when no
     * margin was detected and the whole image was analyzed. */
    val croppedTo: RoiRect? = null,
)

enum class ScoreboardZoneKind { NAMES_AND_SERVE, PREVIOUS_SETS, CURRENT_SET, CURRENT_GAME }

/**
 * One vertical zone. Role fields are null when the zone was not detected or the relevant
 * cluster was degenerate; [toThemeContent] falls back to neighbouring zones in that case.
 */
data class ZoneAnalysis(
    val kind: ScoreboardZoneKind,
    val roi: RoiRect,
    /** K-Means clusters of this zone, sorted by pixel count (largest first). For the report. */
    val clusters: List<ClusterInfo>,
    val background: RgbColor? = null,     // zones: names, current set, current game (largest cluster)
    val primaryText: RgbColor? = null,    // zones: names, current set, current game
    val serve: RgbColor? = null,          // zone: names (smallest cluster)
    val winText: RgbColor? = null,        // zone: previous sets (larger of the two text clusters)
    val loseText: RgbColor? = null,       // zone: previous sets (smaller of the two text clusters)
)

data class ClusterInfo(
    val centroid: RgbColor,
    val pixelCount: Int,
    val share: Double,
)

data class RoiRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * RGB color in 0..255. [centroid] of a [ClusterInfo] is the **mode** (most frequent color) of
 * the cluster's pixels, not the mean — so flat scoreboard fills (e.g. a solid #222E57
 * background) come out bit-accurate instead of drifting toward anti-aliasing edges.
 */
data class RgbColor(val r: Int, val g: Int, val b: Int) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) { "RGB components must be in 0..255" }
    }

    fun toHex(): String = "#" + r.toHexByte() + g.toHexByte() + b.toHexByte()

    fun toThemeColor(alpha: Float = 1f): ThemeColor = ThemeColor(color = toHex(), alpha = alpha)

    fun distanceTo(other: RgbColor): Double {
        val dr = (r - other.r).toDouble()
        val dg = (g - other.g).toDouble()
        val db = (b - other.b).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

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
 * Maps the per-zone colors to a full [ThemeContent]. Each theme field pulls directly from the
 * zone that owns it; missing zones fall back to the main background/text so the preview still
 * renders. No accent heuristics — the semantic role is fixed by the zone.
 */
fun ScoreboardZones.toThemeContent(): ThemeContent {
    val byKind = zones.associateBy { it.kind }
    val names = byKind[ScoreboardZoneKind.NAMES_AND_SERVE]
    val previousSets = byKind[ScoreboardZoneKind.PREVIOUS_SETS]
    val currentSet = byKind[ScoreboardZoneKind.CURRENT_SET]
    val currentGame = byKind[ScoreboardZoneKind.CURRENT_GAME]

    val mainBackground = names?.background
        ?: currentSet?.background
        ?: currentGame?.background
        ?: RgbColor.BLACK
    val mainText = names?.primaryText
        ?: currentSet?.primaryText
        ?: currentGame?.primaryText
        ?: RgbColor.WHITE

    return ThemeContent(
        mainBackgroundColor = mainBackground.toThemeColor(),
        mainTextColor = mainText.toThemeColor(),
        serveColor = (names?.serve ?: mainText).toThemeColor(),
        previousSetWinTextColor = (previousSets?.winText ?: mainText).toThemeColor(),
        previousSetLoseTextColor = previousSets?.loseText?.toThemeColor()
            ?: mainText.toThemeColor(alpha = 0.5f),
        currentSetBackgroundColor = (currentSet?.background ?: mainBackground).toThemeColor(),
        currentSetTextColor = (currentSet?.primaryText ?: mainText).toThemeColor(),
        currentGameBackgroundColor = (currentGame?.background ?: mainBackground).toThemeColor(),
        currentGameTextColor = (currentGame?.primaryText ?: mainText).toThemeColor(),
    )
}
