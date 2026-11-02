package com.remotecontroller.client.feature.connect.impl

import androidx.compose.runtime.Composable

fun interface LocalNetworkPermissionController {
    fun request(thenConnect: Boolean)
}

@Composable
internal expect fun rememberLocalNetworkPermissionController(
    permission: String,
    isGranted: () -> Boolean,
    onResult: (granted: Boolean, thenConnect: Boolean) -> Unit,
): LocalNetworkPermissionController

internal expect fun localNetworkPermissionHint(): String
