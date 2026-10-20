package com.remotecontroller.client.feature.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.remotecontroller.client.domain.model.ThemeMode
import com.remotecontroller.client.feature.common.theme.AppThemeViewModel
import com.remotecontroller.client.feature.common.theme.RemoteControllerTheme
import com.remotecontroller.client.feature.connect.api.ConnectEntry
import com.remotecontroller.client.feature.connect.impl.ConnectScreen
import com.remotecontroller.client.feature.remote.api.RemoteEntry
import com.remotecontroller.client.feature.remote.impl.RemoteScreen
import com.remotecontroller.client.feature.settings.api.SettingsEntry
import com.remotecontroller.client.feature.settings.impl.SettingsScreen
import org.koin.compose.viewmodel.koinViewModel

private val connectEntry = ConnectEntry()
private val remoteEntry = RemoteEntry()
private val settingsEntry = SettingsEntry()

@Composable
fun App(themeViewModel: AppThemeViewModel = koinViewModel()) {
    val mode by themeViewModel.mode.collectAsState()
    val darkTheme = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    RemoteControllerTheme(darkTheme = darkTheme) {
        RemoteControllerNavHost()
    }
}

@Composable
private fun RemoteControllerNavHost() {
    val navController = rememberNavController()
    val openSettings = { navController.navigate(settingsEntry.route) }

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
                onOpenSettings = openSettings,
            )
        }
        composable(route = remoteEntry.route) {
            RemoteScreen(
                onDisconnected = {
                    navController.navigate(connectEntry.route) {
                        popUpTo(remoteEntry.route) { inclusive = true }
                    }
                },
                onOpenSettings = openSettings,
            )
        }
        composable(route = settingsEntry.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
