package com.bashkevich.scoreboardthemerecognizer.components.scoreboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.bashkevich.scoreboardthemerecognizer.model.theme.domain.LocalScoreboardTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.bashkevich.scoreboardthemerecognizer.model.match.domain.TennisSet

@Composable
fun CurrentSetComponent(
    modifier: Modifier = Modifier,
    currentSet: TennisSet,
    numberFontSize: TextUnit = 20.sp,
) {
    val theme = LocalScoreboardTheme.current
    val mainBackgroundColor = theme.mainBackgroundColor
    Box(modifier = Modifier.then(modifier)) {
        CurrentSetNumbersContainer(
            modifier = Modifier
                .fillMaxSize()
                .background(color = theme.currentSetBackgroundColor)
            ,
            currentSet = currentSet,
            numberFontSize = numberFontSize
        )
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopStart),
            color = mainBackgroundColor
        )
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth().align(Alignment.Center),
            color = mainBackgroundColor
        )
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            color = mainBackgroundColor
        )
    }

}

@Composable
fun CurrentSetNumbersContainer(
    modifier: Modifier = Modifier,
    currentSet: TennisSet,
    numberFontSize: TextUnit
) {
    Column(
        modifier = Modifier.then(modifier),
        horizontalAlignment = Alignment.CenterHorizontally, // Выравнивание по центру
    ) {
        val theme = LocalScoreboardTheme.current
        val textColor = theme.currentSetTextColor
        ScoreboardNumber(
            modifier = Modifier.weight(1f),
            scoreNumber = currentSet.firstParticipantGamesWon.toString(),
            textColor = textColor,
            textFontSize = numberFontSize
        )
        ScoreboardNumber(
            modifier = Modifier.weight(1f),
            scoreNumber = currentSet.secondParticipantGamesWon.toString(),
            textColor = textColor,
            textFontSize = numberFontSize
        )
    }
}