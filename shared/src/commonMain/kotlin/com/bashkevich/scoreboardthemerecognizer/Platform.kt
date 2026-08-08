package com.bashkevich.scoreboardthemerecognizer

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform