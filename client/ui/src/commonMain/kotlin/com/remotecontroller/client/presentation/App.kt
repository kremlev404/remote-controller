package com.remotecontroller.client.presentation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.remotecontroller.client.presentation.connect.ConnectScreen
import com.remotecontroller.client.presentation.navigation.ConnectRoute
import com.remotecontroller.client.presentation.navigation.RemoteRoute
import com.remotecontroller.client.presentation.remote.RemoteScreen
import com.remotecontroller.client.presentation.theme.RemoteControllerTheme

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
        startDestination = ConnectRoute,
    ) {
        composable<ConnectRoute> {
            ConnectScreen(
                onConnected = {
                    navController.navigate(RemoteRoute) {
                        popUpTo<ConnectRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<RemoteRoute> {
            RemoteScreen(
                onDisconnected = {
                    navController.navigate(ConnectRoute) {
                        popUpTo<RemoteRoute> { inclusive = true }
                    }
                },
            )
        }
    }
}
