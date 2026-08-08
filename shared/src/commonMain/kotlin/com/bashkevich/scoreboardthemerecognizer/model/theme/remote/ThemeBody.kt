package com.bashkevich.scoreboardthemerecognizer.model.theme.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThemeBody(
    @SerialName("name")
    val name: String,
    @SerialName("content")
    val content: ThemeContent,
)
