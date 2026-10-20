package com.remotecontroller.client.storage.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.remotecontroller.client.domain.model.PointerSensitivity
import com.remotecontroller.client.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ThemePreferencesStore(
    private val dataStore: DataStore<Preferences>,
) {
    val mode: Flow<ThemeMode> = dataStore.data.map { preferences ->
        ThemeMode.fromStored(preferences[MODE_KEY])
    }

    val pointerDpi: Flow<Int> = dataStore.data.map { preferences ->
        PointerSensitivity.coerce(preferences[POINTER_DPI_KEY] ?: PointerSensitivity.DEFAULT_DPI)
    }

    suspend fun set(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[MODE_KEY] = mode.stored
        }
    }

    suspend fun setPointerDpi(dpi: Int) {
        dataStore.edit { preferences ->
            preferences[POINTER_DPI_KEY] = PointerSensitivity.coerce(dpi)
        }
    }

    private companion object {
        val MODE_KEY = stringPreferencesKey("theme_mode")
        val POINTER_DPI_KEY = intPreferencesKey("pointer_dpi")
    }
}
