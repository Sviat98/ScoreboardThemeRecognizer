package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeColor
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeContent

/**
 * Result of the LLM→OpenCV measurement phase. Six tight component crops, each measured for the
 * background fill and/or the glyph color it owns. Pure-KMP (commonMain) so the repository can
 * consume it without the jvmMain-only OpenCV bindings. Reuses [RgbColor] / [RoiRect] from
 * [ScoreboardZones] (single source of truth for those primitives).
 *
 * The six roles map 1:1 onto the nine [ThemeContent] color slots (see [toThemeContent]):
 *  - MAIN_TEXT     → main background + main text
 *  - SERVE         → serve color (glyph only)
 *  - PREV_SET_WIN  → previous-set win text (glyph only)
 *  - PREV_SET_LOSE → previous-set lose text (glyph only)
 *  - CURRENT_SET   → current-set background + current-set text
 *  - CURRENT_GAME  → current-game background + current-game text
 */
enum class ComponentRole { MAIN_TEXT, SERVE, PREV_SET_WIN, PREV_SET_LOSE, CURRENT_SET, CURRENT_GAME }

data class ComponentRect(
    val role: ComponentRole,
    /** Box the LLM returned, in pixels on the original image (diagnostics). */
    val llmBox: RoiRect,
    /** Region actually measured, after snap/inset (diagnostics). */
    val refinedBox: RoiRect,
    /** Fill color of the box, for FILL roles (main text, current set, current game). */
    val background: RgbColor? = null,
    /** Glyph color, for GLYPH roles (text / serve / win / lose). */
    val text: RgbColor? = null,
)

data class ScoreboardComponents(
    val imageWidth: Int,
    val imageHeight: Int,
    val components: List<ComponentRect>,
)

fun ScoreboardComponents.component(role: ComponentRole): ComponentRect? =
    components.firstOrNull { it.role == role }

/**
 * Maps the six measured components onto a full [ThemeContent]. Each theme field pulls from the
 * component that owns it; missing components fall back to the main background/text so the preview
 * still renders. Same fallback policy as [ScoreboardZones.toThemeContent] and the backend.
 */
fun ScoreboardComponents.toThemeContent(): ThemeContent {
    val mainText = component(ComponentRole.MAIN_TEXT)
    val serve = component(ComponentRole.SERVE)
    val prevWin = component(ComponentRole.PREV_SET_WIN)
    val prevLose = component(ComponentRole.PREV_SET_LOSE)
    val currentSet = component(ComponentRole.CURRENT_SET)
    val currentGame = component(ComponentRole.CURRENT_GAME)

    val mainBackground = mainText?.background
        ?: currentSet?.background
        ?: currentGame?.background
        ?: RgbColor.BLACK
    val mainTextColor = mainText?.text ?: RgbColor.WHITE

    return ThemeContent(
        mainBackgroundColor = mainBackground.toThemeColor(),
        mainTextColor = mainTextColor.toThemeColor(),
        serveColor = (serve?.text ?: mainTextColor).toThemeColor(),
        previousSetWinTextColor = (prevWin?.text ?: mainTextColor).toThemeColor(),
        previousSetLoseTextColor = prevLose?.text?.toThemeColor()
            ?: mainTextColor.toThemeColor(alpha = 0.5f),
        currentSetBackgroundColor = (currentSet?.background ?: mainBackground).toThemeColor(),
        currentSetTextColor = (currentSet?.text ?: mainTextColor).toThemeColor(),
        currentGameBackgroundColor = (currentGame?.background ?: mainBackground).toThemeColor(),
        currentGameTextColor = (currentGame?.text ?: mainTextColor).toThemeColor(),
    )
}
