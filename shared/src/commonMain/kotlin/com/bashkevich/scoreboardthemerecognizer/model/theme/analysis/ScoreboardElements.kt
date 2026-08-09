package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

/**
 * The scoreboard's individual ELEMENTS, detected deterministically by OpenCV via connected
 * components on the text-foreground mask (no column/row heuristics). Each element is one specific
 * glyph — a digit, the serve ball, a name letter — with its precise pixel rect. Elements are
 * numbered 0..n-1 in reading order (top → bottom, left → right); the LLM later assigns each ROLE to
 * an element number, and OpenCV measures the color in that element's rect. All rects are in the
 * ORIGINAL image's pixel coordinates (offset by the crop origin).
 */
data class ScoreboardElement(val index: Int, val rect: RoiRect)

data class ScoreboardElements(
    val imageWidth: Int,
    val imageHeight: Int,
    val elements: List<ScoreboardElement>,
    val croppedTo: RoiRect? = null,
)
