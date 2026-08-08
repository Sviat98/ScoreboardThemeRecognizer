package com.bashkevich.scoreboardthemerecognizer.model.player.domain

sealed class TennisPlayerInMatch {
    abstract val id: Int
    abstract val surname: String
    abstract val name: String
}

data class PlayerInSinglesMatch(
    override val id: Int,
    override val surname: String,
    override val name: String,
) : TennisPlayerInMatch()

data class PlayerInDoublesMatch(
    override val id: Int,
    override val surname: String,
    override val name: String,
    val isServingNow: Boolean,
    val isServingNext: Boolean,
) : TennisPlayerInMatch()

val PLAYER_IN_DOUBLES_MATCH_DEFAULT = PlayerInDoublesMatch(
    id = 0,
    surname = "",
    name = "",
    isServingNow = false,
    isServingNext = false
)

fun TennisPlayerInMatch.toDisplayFormat() = when (this) {
    PLAYER_IN_DOUBLES_MATCH_DEFAULT -> ""
    else -> "${this.surname} ${this.name}"
}
