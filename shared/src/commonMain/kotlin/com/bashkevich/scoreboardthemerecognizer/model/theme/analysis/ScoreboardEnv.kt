package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

/**
 * Reads an environment variable. Declared `expect` because the only meaningful implementation uses
 * the JVM-specific `System.getenv` (the LLM agent reads `OPENAI_API_KEY` from the environment).
 * Mirrors the expect/actual split used for `Throwable.toNetworkException()` in `core/remote`.
 */
expect fun readEnvironmentVariable(name: String): String?
