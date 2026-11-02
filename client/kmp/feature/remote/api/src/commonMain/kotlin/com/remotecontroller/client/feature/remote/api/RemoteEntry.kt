package com.remotecontroller.client.feature.remote.api

import com.remotecontroller.client.core.FeatureEntry

/**
 * Entry for the remote screen.
 * Defines the route and arguments used to open the screen.
 */
open class RemoteEntry : FeatureEntry {
    override val route: String
        get() = baseRoute

    override val argumentsKeys: List<String>
        get() = emptyList()

    companion object {
        const val baseRoute: String = "remote"
    }
}
