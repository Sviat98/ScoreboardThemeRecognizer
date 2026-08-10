package com.bashkevich.scoreboardthemerecognizer.model.theme.repository

import com.bashkevich.scoreboardthemerecognizer.core.remote.LoadResult
import com.bashkevich.scoreboardthemerecognizer.core.remote.toNetworkException
import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.NotAScoreboardException
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.ElementThemeAgent
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.analyzeScoreboardImage
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.cleanScoreboardImage
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.detectScoreboardElements
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.measureScoreboardElements
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.readEnvironmentVariable
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.renderNumberedElementsOverlay
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.toThemeContent
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeBody
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeContent
import kotlin.coroutines.cancellation.CancellationException

class ThemeRepositoryImpl : ThemeRepository {

    private val elementAgent = ElementThemeAgent()

    override suspend fun createTheme(themeBody: ThemeBody): LoadResult<Unit, Throwable> {
        // TODO: persistence is not wired in this port (no Room/DB). Treat as success.
        return LoadResult.Success(Unit)
    }

    override suspend fun generateThemeFromImage(image: ImageFile): LoadResult<ThemeContent, Throwable> {
        // Connected-components path: OpenCV finds the scoreboard's individual elements (digits, serve
        // ball, name letters) and numbers them; the LLM only labels which element number is which role
        // (no coordinate drift); OpenCV measures the colors. Falls back to the Stage-1 on-device
        // heuristic when there is no API key, no elements are found, or the LLM call fails — so
        // generation still works offline. "Not a scoreboard" is NOT a fallback: it is surfaced as an
        // error.
        // Best-effort noise pre-cleaning (flags / seeds / country codes, pure OpenCV): strip them
        // before detection so the LLM (and the offline heuristic) see only the essential data. Any
        // failure here silently falls back to the original image. Cleaned once, reused by both paths.
        val working = cleanBestEffort(image)
        return try {
            LoadResult.Success(generateWithElements(working))
        } catch (e: NotAScoreboardException) {
            LoadResult.Error(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("[ThemeRecognizer] Elements path failed (${e.message}); falling back to Stage-1 heuristic")
            try {
                LoadResult.Success(analyzeScoreboardImage(working).toThemeContent())
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Throwable) {
                LoadResult.Error(e2.toNetworkException() ?: e2)
            }
        }
    }

    private suspend fun cleanBestEffort(image: ImageFile): ImageFile = try {
        cleanScoreboardImage(image)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        println("[ThemeRecognizer] noise cleaning failed (${e.message}); using original image")
        image
    }

    private suspend fun generateWithElements(image: ImageFile): ThemeContent {
        if (readEnvironmentVariable("OPENAI_API_KEY").isNullOrEmpty()) {
            // Offline (no key): skip the LLM and use the on-device heuristic directly.
            return analyzeScoreboardImage(image).toThemeContent()
        }
        val elements = detectScoreboardElements(image)
        if (elements.elements.isEmpty()) {
            println("[ThemeRecognizer] No elements detected; falling back to Stage-1 heuristic")
            return analyzeScoreboardImage(image).toThemeContent()
        }
        val numberedPng = renderNumberedElementsOverlay(image, elements)
        val roles = elementAgent.localizeRoles(numberedPng)
        if (!roles.isScoreboard) {
            throw NotAScoreboardException(roles.reason?.ifBlank { null } ?: "Image is not a tennis scoreboard")
        }
        return measureScoreboardElements(image, elements, roles).toThemeContent()
    }
}
