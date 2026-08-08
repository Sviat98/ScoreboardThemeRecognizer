package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

actual fun readEnvironmentVariable(name: String): String? = System.getenv(name)
