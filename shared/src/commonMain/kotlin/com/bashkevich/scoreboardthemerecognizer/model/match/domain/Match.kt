package com.bashkevich.scoreboardthemerecognizer.model.match.domain

import androidx.compose.ui.graphics.Color
import com.bashkevich.scoreboardthemerecognizer.model.participant.domain.ParticipantInDoublesMatch
import com.bashkevich.scoreboardthemerecognizer.model.participant.domain.ParticipantInSinglesMatch
import com.bashkevich.scoreboardthemerecognizer.model.participant.domain.TennisParticipantInMatch
import com.bashkevich.scoreboardthemerecognizer.model.player.domain.PlayerInDoublesMatch
import com.bashkevich.scoreboardthemerecognizer.model.player.domain.PlayerInSinglesMatch

enum class MatchStatus { NOT_STARTED, IN_PROGRESS, PAUSED, COMPLETED }

enum class SpecialSetMode { SUPER_TIEBREAK, ENDLESS }

data class Match(
    val id: Int,
    val pointShift: Int,
    val videoLink: String?,
    val firstParticipant: TennisParticipantInMatch,
    val secondParticipant: TennisParticipantInMatch,
    val status: MatchStatus,
    val previousSets: List<TennisSet>,
    val currentSet: TennisSet?,
    val currentSetMode: SpecialSetMode?,
    val currentGame: TennisGame?,
    val themeId: Int
)


data class TennisSet(
    val firstParticipantGamesWon: Int,
    val secondParticipantGamesWon: Int,
)

data class TennisGame(
    val firstParticipantPointsWon: String,
    val secondParticipantPointsWon: String,
)

// пока что пустые сеты и геймы равны null
val EMPTY_TENNIS_SET = TennisSet(firstParticipantGamesWon = 0, secondParticipantGamesWon = 0)


val SAMPLE_MATCH = Match(
    id = -1,
    pointShift = 0,
    videoLink = null,
    firstParticipant = ParticipantInSinglesMatch(
        id = 1,
        seed = 1,
        //displayName = "DJOKOVIC",
        displayName = "VENNEGOOR OF HESSELINK",
        primaryColor = Color.Green,
        secondaryColor = Color.Blue,
        isServing = false,
        isWinner = false,
        isRetired = false,
        player = PlayerInSinglesMatch(id = 1, surname = "Djokovic", name = "Novak")
    ),
    secondParticipant = ParticipantInSinglesMatch(
        id = 2,
        seed = null,
        displayName = "AUGER-ALIASSIME",
        primaryColor = Color.Red,
        secondaryColor = null,
        isServing = true,
        isWinner = false,
        isRetired = false,
        player = PlayerInSinglesMatch(id = 2, surname = "Auger-Aliassime", name = "Felix")
    ),
    status = MatchStatus.IN_PROGRESS,
    previousSets = listOf(
        TennisSet(firstParticipantGamesWon = 6, secondParticipantGamesWon = 4),
        TennisSet(firstParticipantGamesWon = 3, secondParticipantGamesWon = 6),
    ),
    currentSet = TennisSet(firstParticipantGamesWon = 10, secondParticipantGamesWon = 9),
    currentSetMode = null,
    currentGame = null,
    themeId = 0
    //TennisGame(firstParticipantPointsWon = "30", secondParticipantPointsWon = "15")
)

val DOUBLES_SAMPLE_MATCH = Match(
    id = -2,
    pointShift = 0,
    videoLink = null,
    firstParticipant = ParticipantInDoublesMatch(
        id = 0,
        seed = 1,
        displayName = "ИВАНОВ/ПЕТРОВ",
        primaryColor = Color.White,
        secondaryColor = null,
        isServing = false,
        isWinner = false,
        isRetired = false,
        firstPlayer = PlayerInDoublesMatch(
            id = 0,
            surname = "Иванов",
            name = "Иван",
            isServingNow = false,
            isServingNext = true
        ),
        secondPlayer = PlayerInDoublesMatch(
            id = 0,
            surname = "Петров",
            name = "Петр",
            isServingNow = false,
            isServingNext = false
        )
    ),
    secondParticipant = ParticipantInDoublesMatch(
        id = 0,
        seed = null,
        displayName = "КУЗНЕЦОВ/СИДОРОВ",
        primaryColor = Color.White,
        secondaryColor = null,
        isServing = true,
        isWinner = false,
        isRetired = false,
        firstPlayer = PlayerInDoublesMatch(
            id = 0,
            surname = "Кузнецов",
            name = "Егор",
            isServingNow = false,
            isServingNext = false
        ),
        secondPlayer = PlayerInDoublesMatch(
            id = 0,
            surname = "Сидоров",
            name = "Андрей",
            isServingNow = true,
            isServingNext = false
        ),
    ),
    status = MatchStatus.IN_PROGRESS,
    previousSets = listOf(
        TennisSet(firstParticipantGamesWon = 6, secondParticipantGamesWon = 4),
        TennisSet(firstParticipantGamesWon = 3, secondParticipantGamesWon = 6),
    ),
    currentSet = TennisSet(firstParticipantGamesWon = 10, secondParticipantGamesWon = 9),
    currentSetMode = null,
    currentGame = TennisGame(firstParticipantPointsWon = "30", secondParticipantPointsWon = "15"),
    themeId = 0
)
