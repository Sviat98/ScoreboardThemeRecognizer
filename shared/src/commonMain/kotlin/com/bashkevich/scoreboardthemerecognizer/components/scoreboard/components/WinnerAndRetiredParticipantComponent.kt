package com.bashkevich.scoreboardthemerecognizer.components.scoreboard.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.bashkevich.scoreboardthemerecognizer.model.theme.domain.LocalScoreboardTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bashkevich.scoreboardthemerecognizer.components.icons.IconGroup
import com.bashkevich.scoreboardthemerecognizer.components.icons.default_icons.Check
import org.jetbrains.compose.resources.stringResource
import scoreboardthemerecognizer.shared.generated.resources.Res
import scoreboardthemerecognizer.shared.generated.resources.first_participant_won
import scoreboardthemerecognizer.shared.generated.resources.ret
import scoreboardthemerecognizer.shared.generated.resources.second_participant_won

@Composable
fun WinnerAndRetiredParticipantComponent(
    modifier: Modifier = Modifier,
    firstParticipantId: Int,
    secondParticipantId: Int,
    winnerParticipantId: Int?,
    retiredParticipantId: Int?,
    paddingFromCenter: Dp = 0.dp,
    winnerIconSize: Dp = 24.dp,
    retiredLabelFontSize: TextUnit = 20.sp
) {
    val hasParticipantWon = winnerParticipantId != null

    if (hasParticipantWon) {
        Column(
            modifier = Modifier.then(modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            val winnerParticipantNumber = if (winnerParticipantId == firstParticipantId) 1 else 2

            val retiredParticipantNumber = when (retiredParticipantId) {
                firstParticipantId -> 1
                secondParticipantId -> 2
                else -> null
            }

            WinnerRetiredContainer(
                modifier = Modifier.weight(1f).padding(bottom = paddingFromCenter),
                participantNumber = 1,
                winnerParticipantNumber = winnerParticipantNumber,
                retiredParticipantNumber = retiredParticipantNumber,
                winnerIconSize = winnerIconSize,
                retiredLabelFontSize = retiredLabelFontSize
            )
            WinnerRetiredContainer(
                modifier = Modifier.weight(1f).padding(top = paddingFromCenter),
                participantNumber = 2,
                winnerParticipantNumber = winnerParticipantNumber,
                retiredParticipantNumber = retiredParticipantNumber,
                winnerIconSize = winnerIconSize,
                retiredLabelFontSize = retiredLabelFontSize
            )
        }
    }
}

@Composable
fun WinnerRetiredContainer(
    modifier: Modifier = Modifier,
    participantNumber: Int,
    winnerParticipantNumber: Int,
    retiredParticipantNumber: Int?,
    winnerIconSize: Dp,
    retiredLabelFontSize: TextUnit
) {
    val contentDescription =
        if (winnerParticipantNumber == 1) stringResource(Res.string.first_participant_won) else stringResource(Res.string.second_participant_won)
    Box(modifier = Modifier.then(modifier)) {
        if (participantNumber == winnerParticipantNumber) {
            WinnerSign(
                modifier = Modifier.fillMaxHeight(),
                winnerIconSize = winnerIconSize,
                contentDescription = contentDescription
            )
        } else {
            if (retiredParticipantNumber == null) {
                Spacer(modifier = Modifier.fillMaxHeight())
            } else {
                RetiredSign(
                    modifier = Modifier.fillMaxHeight(),
                    fontSize = retiredLabelFontSize
                )
            }
        }

    }
}

@Composable
fun RetiredSign(
    modifier: Modifier = Modifier,
    fontSize: TextUnit
) {
    val theme = LocalScoreboardTheme.current
    Box(modifier = Modifier.then(modifier)) {
        Text(
            modifier = Modifier.align(Alignment.Center),
            text = stringResource(Res.string.ret),
            fontSize = fontSize,
            color = theme.mainTextColor
        )
    }
}

@Composable
fun WinnerSign(
    modifier: Modifier = Modifier,
    winnerIconSize: Dp,
    contentDescription: String? = null
) {
    val theme = LocalScoreboardTheme.current
    Box(modifier = Modifier.then(modifier)) {
        Icon(
            modifier = Modifier.size(winnerIconSize).align(Alignment.Center),
            imageVector = IconGroup.Default.Check,
            tint = theme.mainTextColor,
            contentDescription = contentDescription
        )
    }

}