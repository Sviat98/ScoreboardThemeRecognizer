package com.bashkevich.scoreboardthemerecognizer.model.theme.domain

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeBody
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeColor
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeContent
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeDto

data class ScoreboardTheme(
    val id: Int,
    val name: String,
    val mainBackgroundColor: Color,
    val mainTextColor: Color,
    val serveColor: Color,
    val previousSetWinTextColor: Color,
    val previousSetLoseTextColor: Color,
    val currentSetBackgroundColor: Color,
    val currentSetTextColor: Color,
    val currentGameBackgroundColor: Color,
    val currentGameTextColor: Color,
) {
    companion object {
        val DEFAULT = ScoreboardTheme(
            id = 0,
            name = "Default",
            mainBackgroundColor = Color.Black,
            mainTextColor = Color.White,
            serveColor = Color.White,
            previousSetWinTextColor = Color.White,
            previousSetLoseTextColor = Color.White.copy(alpha = 0.7f),
            currentSetBackgroundColor = Color.Gray,
            currentSetTextColor = Color.Black,
            currentGameBackgroundColor = Color.White,
            currentGameTextColor = Color.Black,
        )

        val DEFAULT_1 = ScoreboardTheme(
            id = -1,
            name = "Default 111",
            mainBackgroundColor = Color(0xFF142c6c),
            mainTextColor = Color.White,
            serveColor = Color.Yellow,
            previousSetWinTextColor = Color.White,
            previousSetLoseTextColor = Color.White.copy(alpha = 0.5f),
            currentSetBackgroundColor = Color.Yellow,
            currentSetTextColor = Color.Black,
            currentGameBackgroundColor = Color.White,
            currentGameTextColor = Color.Black,
        )
    }
}

val LocalScoreboardTheme = staticCompositionLocalOf<ScoreboardTheme> {
    error("No ScoreboardTheme provided")
}

fun String.convertColor() = "FF$this".toLong(16)

fun ThemeColor.toColor() = Color(color.removePrefix("#").convertColor()).copy(alpha = alpha)

fun Color.toThemeColor(): ThemeColor {
    val hex = value.toHexString().substring(2, 8)
    return ThemeColor(color = "#$hex", alpha = alpha)
}

fun ThemeDto.toDomain() = ScoreboardTheme(
    id = id.toInt(),
    name = name,
    mainBackgroundColor = content.mainBackgroundColor.toColor(),
    mainTextColor = content.mainTextColor.toColor(),
    serveColor = content.serveColor.toColor(),
    previousSetWinTextColor = content.previousSetWinTextColor.toColor(),
    previousSetLoseTextColor = content.previousSetLoseTextColor.toColor(),
    currentSetBackgroundColor = content.currentSetBackgroundColor.toColor(),
    currentSetTextColor = content.currentSetTextColor.toColor(),
    currentGameBackgroundColor = content.currentGameBackgroundColor.toColor(),
    currentGameTextColor = content.currentGameTextColor.toColor(),
)

fun ScoreboardTheme.toThemeBody() = ThemeBody(
    name = name,
    content = ThemeContent(
        mainBackgroundColor = mainBackgroundColor.toThemeColor(),
        mainTextColor = mainTextColor.toThemeColor(),
        serveColor = serveColor.toThemeColor(),
        previousSetWinTextColor = previousSetWinTextColor.toThemeColor(),
        previousSetLoseTextColor = previousSetLoseTextColor.toThemeColor(),
        currentSetBackgroundColor = currentSetBackgroundColor.toThemeColor(),
        currentSetTextColor = currentSetTextColor.toThemeColor(),
        currentGameBackgroundColor = currentGameBackgroundColor.toThemeColor(),
        currentGameTextColor = currentGameTextColor.toThemeColor(),
    ),
)

fun ThemeContent.toScoreboardTheme(
    id: Int = 0,
    name: String = "",
): ScoreboardTheme = ScoreboardTheme(
    id = id,
    name = name,
    mainBackgroundColor = mainBackgroundColor.toColor(),
    mainTextColor = mainTextColor.toColor(),
    serveColor = serveColor.toColor(),
    previousSetWinTextColor = previousSetWinTextColor.toColor(),
    previousSetLoseTextColor = previousSetLoseTextColor.toColor(),
    currentSetBackgroundColor = currentSetBackgroundColor.toColor(),
    currentSetTextColor = currentSetTextColor.toColor(),
    currentGameBackgroundColor = currentGameBackgroundColor.toColor(),
    currentGameTextColor = currentGameTextColor.toColor(),
)
