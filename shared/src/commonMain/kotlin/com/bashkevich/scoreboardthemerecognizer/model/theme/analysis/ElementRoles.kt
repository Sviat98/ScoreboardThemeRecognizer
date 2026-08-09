package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The LLM's ONLY output: which detected element NUMBER plays each role. OpenCV found and numbered the
 * elements; the LLM just matches them semantically ("box 3 is the serve ball, box 7 is the current
 * game digit"). Each field is an element index (0-based, reading order) or null if not present.
 *
 * Flat `@Serializable` (not sealed) — Koog's OpenAI JSON-schema generator throws on a sealed root.
 */
@Serializable
data class ElementRoles(
    @SerialName("is_scoreboard") val isScoreboard: Boolean = true,
    @SerialName("reason") val reason: String? = null,
    @SerialName("main_text_element") val mainTextElement: Int? = null,
    @SerialName("serve_element") val serveElement: Int? = null,
    @SerialName("prev_set_win_element") val prevSetWinElement: Int? = null,
    @SerialName("prev_set_lose_element") val prevSetLoseElement: Int? = null,
    @SerialName("current_set_element") val currentSetElement: Int? = null,
    @SerialName("current_game_element") val currentGameElement: Int? = null,
)
