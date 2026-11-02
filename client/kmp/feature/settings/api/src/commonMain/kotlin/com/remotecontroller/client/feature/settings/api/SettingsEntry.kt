package com.remotecontroller.client.feature.settings.api

import com.remotecontroller.client.core.FeatureEntry

/**
 * Entry for the settings screen.
 * Defines the route used to open appearance settings.
 */
open class SettingsEntry : FeatureEntry {
    override val route: String
        get() = baseRoute

    override val argumentsKeys: List<String>
        get() = emptyList()

    companion object {
        const val baseRoute: String = "settings"
    }
}
