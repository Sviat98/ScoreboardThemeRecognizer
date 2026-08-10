package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeContent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The LLM's palette-classification output: for each of the nine [ThemeContent] color slots, the hex
 * string from the provided palette that best matches it (or null if the slot isn't visible).
 *
 * Flat `@Serializable` (not sealed) — Koog's OpenAI JSON-schema generator throws on a sealed root
 * (see [ElementRoles]). Several slots may share the same hex (e.g. all text slots may resolve to
 * the same white). The `@SerialName` of each field mirrors the matching [ThemeContent] field.
 */
@Serializable
data class PaletteClassification(
    @SerialName("main_background_color") val mainBackgroundColor: String? = null,
    @SerialName("main_text_color") val mainTextColor: String? = null,
    @SerialName("serve_color") val serveColor: String? = null,
    @SerialName("previous_set_win_text_color") val previousSetWinTextColor: String? = null,
    @SerialName("previous_set_lose_text_color") val previousSetLoseTextColor: String? = null,
    @SerialName("current_set_background_color") val currentSetBackgroundColor: String? = null,
    @SerialName("current_set_text_color") val currentSetTextColor: String? = null,
    @SerialName("current_game_background_color") val currentGameBackgroundColor: String? = null,
    @SerialName("current_game_text_color") val currentGameTextColor: String? = null,
)
