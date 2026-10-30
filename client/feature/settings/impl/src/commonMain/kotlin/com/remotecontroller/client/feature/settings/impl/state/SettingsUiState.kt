package com.remotecontroller.client.feature.settings.impl.state

import com.remotecontroller.client.domain.model.ThemeMode

sealed class SettingsUiState {
    data object Loading : SettingsUiState()

    data class Ready(
        val theme: ThemeMode,
        val pointerDpi: Int,
    ) : SettingsUiState()
}

sealed class SettingsIntent {
    data class SelectTheme(val mode: ThemeMode) : SettingsIntent()

    data object DecreasePointer : SettingsIntent()

    data object IncreasePointer : SettingsIntent()
}
