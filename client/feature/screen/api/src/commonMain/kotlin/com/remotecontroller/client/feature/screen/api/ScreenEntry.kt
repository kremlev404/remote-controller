package com.remotecontroller.client.feature.screen.api

import com.remotecontroller.client.core.FeatureEntry

open class ScreenEntry : FeatureEntry {
    override val route: String
        get() = baseRoute

    override val argumentsKeys: List<String>
        get() = emptyList()

    companion object {
        const val baseRoute: String = "screen"
    }
}
