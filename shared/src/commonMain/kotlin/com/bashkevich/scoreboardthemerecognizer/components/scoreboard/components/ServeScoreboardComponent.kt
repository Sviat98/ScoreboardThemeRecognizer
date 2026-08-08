package com.bashkevich.scoreboardthemerecognizer.components.scoreboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.bashkevich.scoreboardthemerecognizer.model.theme.domain.LocalScoreboardTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bashkevich.scoreboardthemerecognizer.model.match.domain.Match

@Composable
fun ServeScoreboardComponent(
    modifier: Modifier = Modifier,
    match: Match,
    paddingFromCenter: Dp = 0.dp,
    ) {
    val firstParticipant = match.firstParticipant
    val secondParticipant = match.secondParticipant

    val isWinnerInMatch = firstParticipant.isWinner || secondParticipant.isWinner

    if (!isWinnerInMatch) {
        Column(
            modifier = Modifier.then(modifier)
                .padding(end=4.dp)
        ) {
            ServingBox(modifier = Modifier.weight(1f).padding(bottom = paddingFromCenter), showServe = firstParticipant.isServing)
            ServingBox(modifier = Modifier.weight(1f).padding(top = paddingFromCenter), showServe = secondParticipant.isServing)
        }
    }


}

@Composable
fun ServingBox(
    modifier: Modifier = Modifier,
    showServe: Boolean
) {
    val theme = LocalScoreboardTheme.current
    Box(modifier = Modifier.then(modifier)) {
        if (showServe) {
            Box(
                modifier = Modifier.size(8.dp).clip(
                    CircleShape
                ).background(color = theme.serveColor).align(Alignment.Center)
            )
        }
    }
}