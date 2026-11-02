package com.remotecontroller.client.feature.common.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotecontroller.client.domain.model.ThemeMode
import com.remotecontroller.client.domain.usecase.ObserveThemeModeUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AppThemeViewModel(
    observeThemeMode: ObserveThemeModeUseCase,
) : ViewModel() {
    val mode: StateFlow<ThemeMode> = observeThemeMode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.System)
}
