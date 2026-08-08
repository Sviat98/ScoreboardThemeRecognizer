package com.bashkevich.scoreboardthemerecognizer.model.theme.repository

import com.bashkevich.scoreboardthemerecognizer.core.remote.LoadResult
import com.bashkevich.scoreboardthemerecognizer.core.remote.toNetworkException
import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.analyzeScoreboardImage
import com.bashkevich.scoreboardthemerecognizer.model.theme.analysis.toThemeContent
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeBody
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeContent
import kotlin.coroutines.cancellation.CancellationException

class ThemeRepositoryImpl : ThemeRepository {

    override suspend fun createTheme(themeBody: ThemeBody): LoadResult<Unit, Throwable> {
        // TODO: persistence is not wired in this port (no Room/DB). Treat as success.
        return LoadResult.Success(Unit)
    }

    override suspend fun generateThemeFromImage(image: ImageFile): LoadResult<ThemeContent, Throwable> {
        // Stage 1: on-device OpenCV analysis (no LLM yet). The analyzer detects the scoreboard
        // ROI, extracts background/text/accent colors via K-Means, prints a [ThemeRecognizer]
        // report to stdout, and returns a palette that is mapped to a ThemeContent here.
        // Mirrors runOperationCatching by hand because that helper takes a non-suspend block,
        // while analyzeScoreboardImage is suspend.
        // TODO (Stage 2): refine the palette with a Koog AIAgent (Reasoning Layer) — contrast
        //   validation and semantic role assignment — without letting the LLM pick raw colors.
        return try {
            LoadResult.Success(analyzeScoreboardImage(image).toThemeContent())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LoadResult.Error(e.toNetworkException() ?: e)
        }
    }
}
