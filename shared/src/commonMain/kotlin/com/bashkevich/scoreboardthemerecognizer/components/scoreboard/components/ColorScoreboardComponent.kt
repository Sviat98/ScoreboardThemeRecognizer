package com.bashkevich.scoreboardthemerecognizer.components.scoreboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bashkevich.scoreboardthemerecognizer.model.match.domain.Match

@Composable
fun ColorScoreboardComponent(
    modifier: Modifier = Modifier,
    match: Match
) {
    val firstParticipant = match.firstParticipant
    val secondParticipant = match.secondParticipant

    Column(
        modifier = Modifier.then(modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ParticipantColor(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            primaryColor = firstParticipant.primaryColor,
            secondaryColor = firstParticipant.secondaryColor
        )
        ParticipantColor(
            modifier = Modifier.weight(1f).fillMaxWidth(),
                //.width(12.dp),
            primaryColor = secondParticipant.primaryColor,
            secondaryColor = secondParticipant.secondaryColor
        )
    }
}

@Composable
fun ParticipantColor(
    modifier: Modifier = Modifier,
    primaryColor: Color,
    secondaryColor: Color?
) {

    Column(
        modifier = Modifier.then(modifier).clip(SemiCircleShape)
            .border(width = 1.dp, color = Color.Black, shape = SemiCircleShape)
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(primaryColor))
        secondaryColor?.let {
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(secondaryColor))
        }
    }
}

val SemiCircleShape = GenericShape { size, _ ->
    val radius = size.width

    arcTo(
        rect = Rect(0f, 0f, radius * 2, radius * 2),
        startAngleDegrees = 270f,
        sweepAngleDegrees = -90f,
        forceMoveTo = false
    )

    arcTo(
        rect = Rect(0f, size.height - radius * 2, radius * 2, size.height),
        startAngleDegrees = 180f,
        sweepAngleDegrees = -90f,
        forceMoveTo = false
    )
}
