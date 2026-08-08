package com.bashkevich.scoreboardthemerecognizer.model.participant.domain

import androidx.compose.ui.graphics.Color
import com.bashkevich.scoreboardthemerecognizer.model.player.domain.PlayerInDoublesMatch
import com.bashkevich.scoreboardthemerecognizer.model.player.domain.PlayerInSinglesMatch
import com.bashkevich.scoreboardthemerecognizer.model.player.domain.TennisPlayerInMatch

sealed class TennisParticipantInMatch {
    abstract val id: Int
    abstract val seed: Int?
    abstract val displayName: String
    abstract val primaryColor: Color
    abstract val secondaryColor: Color?
    abstract val isServing: Boolean
    abstract val isWinner: Boolean
    abstract val isRetired: Boolean
}

data class ParticipantInSinglesMatch(
    override val id: Int,
    override val seed: Int?,
    override val displayName: String,
    override val primaryColor: Color,
    override val secondaryColor: Color?,
    override val isServing: Boolean,
    override val isWinner: Boolean,
    override val isRetired: Boolean,
    val player: TennisPlayerInMatch
) : TennisParticipantInMatch()

data class ParticipantInDoublesMatch(
    override val id: Int,
    override val seed: Int?,
    override val displayName: String,
    override val primaryColor: Color,
    override val secondaryColor: Color?,
    override val isServing: Boolean,
    override val isWinner: Boolean,
    override val isRetired: Boolean,
    val firstPlayer: TennisPlayerInMatch,
    val secondPlayer: TennisPlayerInMatch
) : TennisParticipantInMatch()

fun String.convertColor() = "FF$this".toLong(16)

val PARTICIPANT_IN_SINGLES_MATCH_DEFAULT = ParticipantInSinglesMatch(
    id = 0,
    seed = null,
    displayName = "",
    primaryColor = Color.White,
    secondaryColor = null,
    isServing = false,
    isWinner = false,
    isRetired = false,
    player = PlayerInSinglesMatch(id = 0, surname = "", name = ""),
)

val PARTICIPANT_IN_DOUBLES_MATCH_DEFAULT = ParticipantInDoublesMatch(
    id = 0,
    seed = null,
    displayName = "",
    primaryColor = Color.White,
    secondaryColor = null,
    isServing = false,
    isWinner = false,
    isRetired = false,
    firstPlayer = PlayerInDoublesMatch(
        id = 0,
        surname = "",
        name = "",
        isServingNow = false,
        isServingNext = false
    ),
    secondPlayer = PlayerInDoublesMatch(
        id = 0,
        surname = "",
        name = "",
        isServingNow = false,
        isServingNext = false
    ),
)
