package com.bashkevich.scoreboardthemerecognizer.model.theme.analysis

import java.io.File

actual fun readProjectRootText(fileName: String): String? = try {
    projectRoot().resolve(fileName).takeIf { it.exists() }?.readText()
} catch (e: Exception) {
    null
}
