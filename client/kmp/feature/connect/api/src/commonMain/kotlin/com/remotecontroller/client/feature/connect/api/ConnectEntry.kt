package com.remotecontroller.client.feature.connect.api

import com.remotecontroller.client.core.FeatureEntry

/**
 * Entry for the connect screen.
 * Defines the route and arguments used to open the screen.
 */
open class ConnectEntry : FeatureEntry {
    override val route: String
        get() = baseRoute

    override val argumentsKeys: List<String>
        get() = emptyList()

    companion object {
        const val baseRoute: String = "connect"
    }
}
