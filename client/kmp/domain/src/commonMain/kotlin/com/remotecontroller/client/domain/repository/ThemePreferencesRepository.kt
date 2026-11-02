package com.remotecontroller.client.domain.repository

import com.remotecontroller.client.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface ThemePreferencesRepository {
    fun observeThemeMode(): Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)

    fun observePointerDpi(): Flow<Int>

    suspend fun setPointerDpi(dpi: Int)

    fun observeScreenTouchMode(): Flow<Boolean>

    suspend fun setScreenTouchMode(enabled: Boolean)
}
