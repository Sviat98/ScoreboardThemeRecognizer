package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

/**
 * Reads an environment variable. Declared `expect` because the only meaningful implementation uses
 * the JVM-specific `System.getenv` (the LLM agent reads `OPENAI_API_KEY` from the environment).
 * Mirrors the expect/actual split used for `Throwable.toNetworkException()` in `core/remote`.
 */
expect fun readEnvironmentVariable(name: String): String?

/**
 * Resolves the OpenAI API key: first from the `OPENAI_API_KEY` env var, then from a local
 * `openai.key` file in the project root (gitignored). The file fallback lets the key be supplied to
 * a process that didn't inherit the env var (e.g. a Gradle test JVM under a long-lived shell).
 */
expect fun loadOpenAIApiKey(): String?

/**
 * Reads a text file from the project root (for the debug "boxes.json" input). `expect`/`actual`
 * (JVM `java.io.File`). Returns null when the file is missing or unreadable.
 */
expect fun readProjectRootText(fileName: String): String?
