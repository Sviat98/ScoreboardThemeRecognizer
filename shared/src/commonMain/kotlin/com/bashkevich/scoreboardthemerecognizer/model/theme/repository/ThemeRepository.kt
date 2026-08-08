package com.bashkevich.scoreboardthemerecognizer.model.theme.repository

import com.bashkevich.scoreboardthemerecognizer.core.remote.LoadResult
import com.bashkevich.scoreboardthemerecognizer.model.file.domain.ImageFile
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeBody
import com.bashkevich.scoreboardthemerecognizer.model.theme.remote.ThemeContent

interface ThemeRepository {
    suspend fun createTheme(themeBody: ThemeBody): LoadResult<Unit, Throwable>
    suspend fun generateThemeFromImage(image: ImageFile): LoadResult<ThemeContent, Throwable>
}
