package com.bashkevich.scoreboardthemerecognizer.model.theme.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThemeDto(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("content")
    val content: ThemeContent
)

@Serializable
data class ThemeContent(
    @SerialName("main_background_color")
    val mainBackgroundColor: ThemeColor,
    @SerialName("main_text_color")
    val mainTextColor: ThemeColor,
    @SerialName("serve_color")
    val serveColor: ThemeColor,
    @SerialName("previous_set_win_text_color")
    val previousSetWinTextColor: ThemeColor,
    @SerialName("previous_set_lose_text_color")
    val previousSetLoseTextColor: ThemeColor,
    @SerialName("current_set_background_color")
    val currentSetBackgroundColor: ThemeColor,
    @SerialName("current_set_text_color")
    val currentSetTextColor: ThemeColor,
    @SerialName("current_game_background_color")
    val currentGameBackgroundColor: ThemeColor,
    @SerialName("current_game_text_color")
    val currentGameTextColor: ThemeColor,
)

@Serializable
data class ThemeColor(
    val color: String,
    val alpha: Float = 1f,
)

