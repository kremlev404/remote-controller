package com.remotecontroller.client.domain.usecase

import com.remotecontroller.client.domain.model.ThemeMode
import com.remotecontroller.client.domain.repository.ThemePreferencesRepository
import kotlinx.coroutines.flow.Flow

class ObserveThemeModeUseCase(
    private val repository: ThemePreferencesRepository,
) {
    operator fun invoke(): Flow<ThemeMode> = repository.observeThemeMode()
}

class SetThemeModeUseCase(
    private val repository: ThemePreferencesRepository,
) {
    suspend operator fun invoke(mode: ThemeMode) {
        repository.setThemeMode(mode)
    }
}

class ObservePointerDpiUseCase(
    private val repository: ThemePreferencesRepository,
) {
    operator fun invoke(): Flow<Int> = repository.observePointerDpi()
}

class SetPointerDpiUseCase(
    private val repository: ThemePreferencesRepository,
) {
    suspend operator fun invoke(dpi: Int) {
        repository.setPointerDpi(dpi)
    }
}
