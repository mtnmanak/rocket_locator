package io.github.mtnmanak.rocketlocator26.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.mtnmanak.rocketlocator26.ui.devices.DevicesScreen
import io.github.mtnmanak.rocketlocator26.ui.devices.DevicesViewModel

/** Navigation route names for [AppNav]. */
object AppRoutes {
    /** Main telemetry dashboard. */
    const val HOME = "home"

    /** Device picker: paired Classic devices, BLE scan, live NMEA console. */
    const val DEVICES = "devices"
}

/**
 * Root navigation graph of the app.
 *
 * Hosts the [HomeScreen] dashboard as the start destination and the
 * [DevicesScreen] picker as a pushed destination. Each destination owns its
 * ViewModel, scoped to its back-stack entry by `viewModel()`.
 */
@Composable
fun AppNav() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = AppRoutes.HOME,
    ) {
        composable(AppRoutes.HOME) {
            val viewModel: HomeViewModel = viewModel()
            HomeScreen(
                viewModel = viewModel,
                onOpenDevices = { navController.navigate(AppRoutes.DEVICES) },
            )
        }
        composable(AppRoutes.DEVICES) {
            val viewModel: DevicesViewModel = viewModel()
            DevicesScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
