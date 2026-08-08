package com.bashkevich.scoreboardthemerecognizer.model.theme.repository

import com.bashkevich.scoreboardthemerecognizer.core.remote.LoadResult
import com.bashkevich.scoreboardthemerecognizer.core.remote.toNetworkException
import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.NotAScoreboardException
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.ScoreboardThemeAgent
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.analyzeScoreboardImage
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.measureComponentsColors
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.readEnvironmentVariable
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.toThemeContent
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeBody
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeContent
import kotlin.coroutines.cancellation.CancellationException

class ThemeRepositoryImpl : ThemeRepository {

    private val themeAgent = ScoreboardThemeAgent()

    override suspend fun createTheme(themeBody: ThemeBody): LoadResult<Unit, Throwable> {
        // TODO: persistence is not wired in this port (no Room/DB). Treat as success.
        return LoadResult.Success(Unit)
    }

    override suspend fun generateThemeFromImage(image: ImageFile): LoadResult<ThemeContent, Throwable> {
        // Stage 2: a Koog vision agent (OpenAI GPT-4o) localizes six scoreboard components, then an
        // OpenCV measurer reads the exact colors. Falls back to the Stage-1 on-device heuristic
        // when there is no API key, the LLM call fails, or it returns no boxes — so generation still
        // works offline. "Not a scoreboard" is NOT a fallback: it is surfaced to the user as an error.
        return try {
            LoadResult.Success(generateWithAgent(image))
        } catch (e: NotAScoreboardException) {
            LoadResult.Error(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("[ThemeRecognizer] Agent path failed (${e.message}); falling back to Stage-1 heuristic")
            try {
                LoadResult.Success(analyzeScoreboardImage(image).toThemeContent())
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Throwable) {
                LoadResult.Error(e2.toNetworkException() ?: e2)
            }
        }
    }

    private suspend fun generateWithAgent(image: ImageFile): ThemeContent {
        if (readEnvironmentVariable("OPENAI_API_KEY").isNullOrEmpty()) {
            // Offline (no key): skip the LLM entirely and use the on-device heuristic directly.
            return analyzeScoreboardImage(image).toThemeContent()
        }
        val layout = themeAgent.localize(image)
        if (!layout.isScoreboard) {
            throw NotAScoreboardException(layout.reason?.ifBlank { null } ?: "Image is not a tennis scoreboard")
        }
        if (layout.components.isEmpty()) {
            error("LLM returned no component boxes")
        }
        return measureComponentsColors(image, layout).toThemeContent()
    }
}
