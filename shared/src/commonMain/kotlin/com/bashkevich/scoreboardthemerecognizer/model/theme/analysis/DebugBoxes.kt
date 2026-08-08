package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

/**
 * Reads a text file from the project root (for the debug "boxes.json" input). Declared `expect`
 * because the implementation uses JVM `java.io.File`; the jvmMain `actual` resolves it relative to
 * the directory holding `settings.gradle.kts`. Returns null when the file is missing or unreadable.
 */
expect fun readProjectRootText(fileName: String): String?
