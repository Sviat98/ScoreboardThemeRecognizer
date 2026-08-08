package com.bashkevich.scoreboardthemerecognizer.components.scoreboard.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bashkevich.scoreboardthemerecognizer.model.theme.domain.LocalScoreboardTheme
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SeedScoreboardComponent(
    modifier: Modifier = Modifier,
    firstParticipantSeed: Int?,
    secondParticipantSeed: Int?,
    paddingFromCenter: Dp = 0.dp,
    seedNumberFontSize: TextUnit = 12.sp
) {
    Column(
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .then(modifier)
            .padding(start = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SeedNumber(
            modifier = Modifier.weight(1f).padding(bottom = paddingFromCenter),
            seedNumber = firstParticipantSeed ?: 0,
            fontSize = seedNumberFontSize
        )
        SeedNumber(
            modifier = Modifier.weight(1f).padding(top = paddingFromCenter),
            seedNumber = secondParticipantSeed ?: 0,
            fontSize = seedNumberFontSize
        )
    }

}

@Composable
fun SeedNumber(
    modifier: Modifier = Modifier,
    seedNumber: Int,
    fontSize: TextUnit
) {
    val theme = LocalScoreboardTheme.current
    Box(modifier = Modifier.then(modifier)) {
        Text(
            modifier = Modifier.align(Alignment.Center),
            text = if (seedNumber == 0) "" else seedNumber.toString(),
            fontSize = fontSize,
            color = theme.mainTextColor.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }

}
