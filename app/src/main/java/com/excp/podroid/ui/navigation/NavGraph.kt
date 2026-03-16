/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Simplified navigation for Podroid.
 */
package com.excp.podroid.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.excp.podroid.ui.screens.home.HomeScreen
import com.excp.podroid.ui.screens.settings.SettingsScreen
import com.excp.podroid.ui.screens.terminal.TerminalScreen

/** Navigation route definitions. */
object Routes {
    const val HOME     = "home"
    const val TERMINAL = "terminal"
    const val SETTINGS = "settings"
}

@Composable
fun PodroidNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToTerminal = { navController.navigate(Routes.TERMINAL) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.TERMINAL) {
            TerminalScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
