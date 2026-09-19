package com.excp.podroid.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.excp.podroid.ui.screens.home.HomeScreen
import com.excp.podroid.ui.screens.settings.SettingsScreen
import com.excp.podroid.ui.screens.setup.SetupScreen
import com.excp.podroid.ui.screens.terminal.TerminalScreen
import com.excp.podroid.ui.screens.terminal.TerminalViewModel
import com.excp.podroid.ui.screens.backup.ContainerBackupScreen
import com.excp.podroid.ui.screens.status.StatusScreen
import com.excp.podroid.ui.screens.x11.X11Screen

object Routes {
    const val SETUP         = "setup"
    const val HOME          = "home"
    const val TERMINAL      = "terminal"
    const val TERMINAL_X11  = "terminal/x11"
    const val SETTINGS      = "settings"
    const val STATUS        = "status"
    const val CONTAINER_BACKUP = "container_backup"
}

@Composable
fun PodroidNavGraph(
    windowSizeClass: WindowSizeClass,
    navController: NavHostController = rememberNavController(),
) {
    // Read isSetupDone from a Hilt-scoped helper so MainActivity doesn't need
    // a field-injected SettingsRepository just to drive the start destination.
    val isSetupDone by hiltViewModel<NavGraphViewModel>()
        .isSetupDone
        .collectAsStateWithLifecycle(initialValue = null)

    // Scoped to PodroidNavGraph composable — survives all navigation including popUpTo(0)
    val terminalViewModel: TerminalViewModel = hiltViewModel()

    if (isSetupDone == null) {
        // isSetupDone starts null on every fresh composition (e.g. the
        // activity.recreate() a language change triggers) until the flow's
        // first value arrives. Paint the themed background instead of
        // returning with nothing composed, so that frame doesn't flash an
        // empty window.
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    val startDestination = when (isSetupDone) {
        true  -> Routes.HOME
        else  -> Routes.SETUP
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.SETUP) {
            SetupScreen(
                windowSizeClass = windowSizeClass,
                onSetupComplete = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                windowSizeClass = windowSizeClass,
                onNavigateToTerminal = {
                    navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
                onNavigateToStatus = {
                    navController.navigate(Routes.STATUS) { launchSingleTop = true }
                },
                onNavigateToContainerBackup = {
                    navController.navigate(Routes.CONTAINER_BACKUP) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.STATUS) {
            StatusScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (navController.currentDestination?.route == Routes.STATUS) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.TERMINAL) {
            TerminalScreen(
                windowSizeClass = windowSizeClass,
                viewModel = terminalViewModel,
                onNavigateBack = {
                    // Only pop if we're not already at HOME to avoid the warning
                    if (navController.currentDestination?.route == Routes.TERMINAL) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onNavigateToX11 = {
                    navController.navigate(Routes.TERMINAL_X11) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.TERMINAL_X11) {
            X11Screen(
                onNavigateBack = {
                    if (!navController.popBackStack(Routes.TERMINAL, inclusive = false)) {
                        navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                    }
                },
                onNavigateToTerminal = {
                    if (!navController.popBackStack(Routes.TERMINAL, inclusive = false)) {
                        navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
                    }
                },
            )
        }

        composable(Routes.CONTAINER_BACKUP) {
            ContainerBackupScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (navController.currentDestination?.route == Routes.CONTAINER_BACKUP) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.SETTINGS) {
            val activity = LocalActivity.current
            val onLanguageChanged = remember(activity) {
                { activity?.recreate() ?: Unit }
            }
            SettingsScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (navController.currentDestination?.route == Routes.SETTINGS) {
                        navController.popBackStack()
                    } else if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onLanguageChanged = onLanguageChanged,
                onNavigateToContainerBackup = {
                    navController.navigate(Routes.CONTAINER_BACKUP) { launchSingleTop = true }
                },
            )
        }
    }
}
