package com.github.tkirino.gobanreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.tkirino.gobanreader.camera.CameraScreen
import com.github.tkirino.gobanreader.corner.CornerScreen
import com.github.tkirino.gobanreader.display.DisplayScreen
import com.github.tkirino.gobanreader.setting.SettingScreen
import kotlinx.serialization.Serializable
import java.io.File

@Composable
fun App(
    viewModel: MainViewModel? = null,
    onCameraScreenChanged: (Boolean) -> Unit = {}
) {
    val readerViewModel: MainViewModel = viewModel ?: viewModel()
    val uiState by readerViewModel.uiState.collectAsState()
    val navController = rememberNavController()

    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { backStackEntry ->
            val route = backStackEntry.destination.route
            val inCamera = route?.contains("Camera") == true
            onCameraScreenChanged(inCamera)
        }
    }

    NavHost(
        navController = navController,
        startDestination = Route.Settings
    ) {
        composable<Route.Settings> {
            SettingScreen(
                viewModel = readerViewModel,
                onBlackPlayerChanged = { name -> readerViewModel.updateBlackPlayer(name) },
                onWhitePlayerChanged = { name -> readerViewModel.updateWhitePlayer(name) },
                onGetGobanClick = { navController.navigate(Route.Camera) },
                onHistoryClick = { navController.navigate(Route.History) }
            )
        }
        composable<Route.Camera> {
            CameraScreen(
                viewModel = readerViewModel,
                onStartReadingClick = { file: File ->
                    readerViewModel.loadPhotoForAdjustment(file)
                    navController.navigate(Route.Corner)
                },
                onBackClick = { navController.popBackStack() }
            )
        }
        composable<Route.Corner> {
            val bitmap = uiState.adjustmentBitmap

            if (bitmap != null) {
                CornerScreen(
                    viewModel = readerViewModel,
                    bitmap = bitmap,
                    initialCorners = uiState.initialCorners,
                    rawDetection = uiState.rawCorners,
                    onConfirmed = { corners ->
                        readerViewModel.processWithCorners(corners)
                        navController.navigate(Route.Display)
                    },
                    onBack = {
                        navController.popBackStack(Route.Settings, inclusive = false)
                    }
                )
            }
        }
        composable<Route.Display> {
            DisplayScreen(
                readerViewModel,
                onBackClick = { navController.navigate(Route.Settings) }
            )
        }
    }
}

object Route {
    @Serializable
    data object Settings
    @Serializable
    data object History
    @Serializable
    data object Camera
    @Serializable
    data object Corner
    @Serializable
    data object Display
}
