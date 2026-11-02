package com.remotecontroller.client.feature.settings.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotecontroller.client.domain.model.PointerSensitivity
import com.remotecontroller.client.domain.usecase.ObservePointerDpiUseCase
import com.remotecontroller.client.domain.usecase.ObserveThemeModeUseCase
import com.remotecontroller.client.domain.usecase.SetPointerDpiUseCase
import com.remotecontroller.client.domain.usecase.SetThemeModeUseCase
import com.remotecontroller.client.feature.settings.impl.state.SettingsIntent
import com.remotecontroller.client.feature.settings.impl.state.SettingsUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    observeThemeMode: ObserveThemeModeUseCase,
    observePointerDpi: ObservePointerDpiUseCase,
    private val setThemeMode: SetThemeModeUseCase,
    private val setPointerDpi: SetPointerDpiUseCase,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        observeThemeMode(),
        observePointerDpi(),
    ) { theme, pointerDpi ->
        SettingsUiState.Ready(theme = theme, pointerDpi = pointerDpi)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState.Loading,
    )

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SelectTheme -> viewModelScope.launch {
                setThemeMode(intent.mode)
            }
            SettingsIntent.DecreasePointer -> adjustPointer(-PointerSensitivity.STEP_DPI)
            SettingsIntent.IncreasePointer -> adjustPointer(PointerSensitivity.STEP_DPI)
        }
    }

    private fun adjustPointer(deltaDpi: Int) {
        val ready = state.value as? SettingsUiState.Ready ?: return
        viewModelScope.launch {
            setPointerDpi(ready.pointerDpi + deltaDpi)
        }
    }
}
