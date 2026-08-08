package com.bashkevich.scoreboardthemerecognizer.di

import com.bashkevich.scoreboardthemerecognizer.model.theme.repository.ThemeRepository
import com.bashkevich.scoreboardthemerecognizer.model.theme.repository.ThemeRepositoryImpl
import com.bashkevich.scoreboardthemerecognizer.screens.settings.generatetheme.GenerateThemeViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val themeModule = module {
    single<ThemeRepository> { ThemeRepositoryImpl() }
    viewModelOf(::GenerateThemeViewModel)
}

/** Initializes the global Koin context. Call once before the Compose UI starts. */
fun initKoin() {
    startKoin {
        modules(themeModule)
    }
}
