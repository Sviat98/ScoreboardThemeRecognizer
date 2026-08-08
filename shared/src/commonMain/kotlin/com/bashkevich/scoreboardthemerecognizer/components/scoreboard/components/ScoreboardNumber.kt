package com.bashkevich.scoreboardthemerecognizer.components.scoreboard.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@Composable
fun ScoreboardNumber(
    modifier: Modifier = Modifier,
    scoreNumber: String,
    textColor: Color,
    textFontSize: TextUnit = 20.sp
) {
    Box(
        modifier = Modifier.then(modifier)
    ){
        Text(
            modifier = Modifier.align(Alignment.Center),
            text = scoreNumber,
            fontSize = textFontSize,
            color =textColor
        )
    }

}