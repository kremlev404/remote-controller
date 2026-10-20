package com.remotecontroller.client.feature.common

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.remotecontroller.client.feature.common.theme.RemoteControllerTheme
import com.remotecontroller.client.feature.connect.api.ConnectEntry
import com.remotecontroller.client.feature.connect.impl.ConnectScreen
import com.remotecontroller.client.feature.remote.api.RemoteEntry
import com.remotecontroller.client.feature.remote.impl.RemoteScreen

private val connectEntry = ConnectEntry()
private val remoteEntry = RemoteEntry()

@Composable
fun App() {
    RemoteControllerTheme {
        RemoteControllerNavHost()
    }
}

@Composable
private fun RemoteControllerNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = connectEntry.route,
    ) {
        composable(route = connectEntry.route) {
            ConnectScreen(
                onConnected = {
                    navController.navigate(remoteEntry.route) {
                        popUpTo(connectEntry.route) { inclusive = true }
                    }
                },
            )
        }
        composable(route = remoteEntry.route) {
            RemoteScreen(
                onDisconnected = {
                    navController.navigate(connectEntry.route) {
                        popUpTo(remoteEntry.route) { inclusive = true }
                    }
                },
            )
        }
    }
}
