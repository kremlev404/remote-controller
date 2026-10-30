package com.remotecontroller.client.storage.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.remotecontroller.client.domain.repository.DeviceGrantRepository
import com.remotecontroller.shared.network.LinkGrant
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

class DeviceGrantStore(
    private val dataStore: DataStore<Preferences>,
) : DeviceGrantRepository {
    override suspend fun grantFor(pc: String): LinkGrant? =
        decode(dataStore.data.first()[GRANTS_KEY])[pc]

    override suspend fun remember(pc: String, grant: LinkGrant) {
        update { it + (pc to grant) }
    }

    override suspend fun forget(pc: String) {
        update { it - pc }
    }

    private suspend fun update(change: (Map<String, LinkGrant>) -> Map<String, LinkGrant>) {
        dataStore.edit { preferences ->
            preferences[GRANTS_KEY] = Json.encodeToString(change(decode(preferences[GRANTS_KEY])))
        }
    }

    private fun decode(raw: String?): Map<String, LinkGrant> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, LinkGrant>>(raw) }.getOrDefault(emptyMap())
    }

    private companion object {
        val GRANTS_KEY = stringPreferencesKey("device_grants")
    }
}
