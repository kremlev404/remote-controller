package com.remotecontroller.client.storage.repository

import com.remotecontroller.client.domain.model.ThemeMode
import com.remotecontroller.client.domain.repository.ThemePreferencesRepository
import com.remotecontroller.client.storage.local.ThemePreferencesStore
import kotlinx.coroutines.flow.Flow

class ThemePreferencesRepositoryImpl(
    private val store: ThemePreferencesStore,
) : ThemePreferencesRepository {
    override fun observeThemeMode(): Flow<ThemeMode> = store.mode

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.set(mode)
    }

    override fun observePointerDpi(): Flow<Int> = store.pointerDpi

    override suspend fun setPointerDpi(dpi: Int) {
        store.setPointerDpi(dpi)
    }

    override fun observeScreenTouchMode(): Flow<Boolean> = store.screenTouchMode

    override suspend fun setScreenTouchMode(enabled: Boolean) {
        store.setScreenTouchMode(enabled)
    }
}
