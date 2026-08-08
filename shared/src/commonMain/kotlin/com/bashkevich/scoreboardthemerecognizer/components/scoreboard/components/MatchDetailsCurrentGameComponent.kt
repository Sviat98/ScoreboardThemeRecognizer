package com.bashkevich.scoreboardthemerecognizer.components.scoreboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.bashkevich.scoreboardthemerecognizer.model.theme.domain.LocalScoreboardTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.bashkevich.scoreboardthemerecognizer.model.match.domain.TennisGame

@Composable
fun MatchDetailsCurrentGameComponent(
    modifier: Modifier = Modifier,
    currentGame: TennisGame,
    fontSize: TextUnit = 20.sp
) {
    val theme = LocalScoreboardTheme.current
    Box(modifier = Modifier.then(modifier)){
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = theme.currentGameBackgroundColor),
            horizontalAlignment = Alignment.CenterHorizontally, // Выравнивание по центру
        ) {
            val textColor = theme.currentGameTextColor
            ScoreboardNumber(
                modifier = Modifier.weight(1f),
                scoreNumber = currentGame.firstParticipantPointsWon,
                textColor = textColor,
                textFontSize = fontSize
            )
            ScoreboardNumber(
                modifier = Modifier.weight(1f),
                scoreNumber = currentGame.secondParticipantPointsWon,
                textColor = textColor,
                textFontSize = fontSize
            )
        }
        HorizontalDivider(modifier = Modifier.fillMaxWidth().align(Alignment.TopStart), color = theme.mainBackgroundColor)
        HorizontalDivider(modifier = Modifier.fillMaxWidth().align(Alignment.Center), color = theme.mainBackgroundColor)
        HorizontalDivider(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter), color = theme.mainBackgroundColor)
    }

}