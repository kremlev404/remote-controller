package com.remotecontroller.client.feature.connect.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.remotecontroller.client.data.network.IosLocalNetwork

@Composable
internal actual fun rememberLocalNetworkPermissionController(
    permission: String,
    isGranted: () -> Boolean,
    onResult: (granted: Boolean, thenConnect: Boolean) -> Unit,
): LocalNetworkPermissionController {
    val latestGranted = rememberUpdatedState(isGranted)
    val latestResult = rememberUpdatedState(onResult)
    val connectAfterGrant = remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener: (Boolean) -> Unit = { granted ->
            val thenConnect = connectAfterGrant.value
            connectAfterGrant.value = false
            latestResult.value(granted, thenConnect)
        }
        IosLocalNetwork.addListener(listener)
        onDispose { IosLocalNetwork.removeListener(listener) }
    }

    return LocalNetworkPermissionController { thenConnect ->
        if (latestGranted.value()) {
            latestResult.value(true, thenConnect)
            return@LocalNetworkPermissionController
        }
        if (thenConnect) connectAfterGrant.value = true
        IosLocalNetwork.request()
    }
}

internal actual fun localNetworkPermissionHint(): String =
    "Allow local network access so this iPhone can reach your PC. If you already declined, enable Local Network in Settings."
