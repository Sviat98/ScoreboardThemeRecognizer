package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * LLM response for the **localization** phase of the theme agent. The model NEVER names colors —
 * it only returns six normalized (0..1) role boxes; a deterministic OpenCV measurer then reads the
 * exact colors from the real pixels. So "black instead of navy" disappears by construction (the
 * model has no way to distort a color), exactly as in the TennisScoreKeeperBackend pipeline.
 *
 * Intentionally a **flat** data class (not a sealed on_success/on_failure hierarchy): Koog's OpenAI
 * JSON-schema generator requires a plain class at the root, otherwise it throws
 * `Key $ref is missing in the map`. The [isScoreboard] flag gives the model a legal escape hatch
 * within structured output. Roles are strings (not an enum) for the same schema-generator reason.
 */
@Serializable
data class AiComponentLayout(
    @SerialName("is_scoreboard") val isScoreboard: Boolean = true,
    @SerialName("reason") val reason: String? = null,
    @SerialName("components") val components: List<AiBox> = emptyList(),
)

/**
 * One normalized bounding box: top-left corner (x, y) + size (w, h), all in 0..1 of the image
 * dimensions (resolution-independent, since vision LLMs may resize internally).
 */
@Serializable
data class AiBox(
    @SerialName("role") val role: String, // main_text|serve|prev_set_win|prev_set_lose|current_set|current_game
    @SerialName("x") val x: Double,
    @SerialName("y") val y: Double,
    @SerialName("w") val w: Double,
    @SerialName("h") val h: Double,
)
