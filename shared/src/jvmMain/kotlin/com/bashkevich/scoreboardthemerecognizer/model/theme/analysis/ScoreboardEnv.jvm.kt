package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

actual fun readEnvironmentVariable(name: String): String? = System.getenv(name)

actual fun loadOpenAIApiKey(): String? =
    System.getenv("OPENAI_API_KEY")
        ?: projectRoot().resolve("openai.key").takeIf { it.exists() }?.readText()?.trim()

actual fun readProjectRootText(fileName: String): String? = try {
    projectRoot().resolve(fileName).takeIf { it.exists() }?.readText()
} catch (e: Exception) {
    null
}
