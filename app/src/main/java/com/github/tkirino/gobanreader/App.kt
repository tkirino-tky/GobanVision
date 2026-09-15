package com.github.tkirino.gobanreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
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
        startDestination = Route.Camera // ① 最初にカメラ画面(CameraScreen)からスタート
    ) {
        composable<Route.Camera> {
            CameraScreen(
                viewModel = readerViewModel,
                onDetectionSuccess = {
                    // ② 碁盤認識が出来た場合は直ちに結果の表示(DisplayScreen)へ移動
                    navController.navigate(Route.Display)
                },
                onManualInputClick = {
                    // ③ コーナーの手動入力画面(CornerScreen)へ移動
                    val dummyFile = File(context.cacheDir, "goban_photo.jpg")
                    readerViewModel.loadPhotoForAdjustment(dummyFile)
                    navController.navigate(Route.Corner)
                },
                onSettingsClick = {
                    // ④ 設定画面へ移動
                    navController.navigate(Route.Settings)
                },
                onBackClick = {
                    navController.popBackStack(Route.Camera, inclusive = false)
                }
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
                        navController.navigate(Route.Display) {
                            popUpTo(Route.Camera) { inclusive = false }
                        }
                    },
                    onBack = {
                        navController.popBackStack(Route.Camera, inclusive = false)
                    }
                )
            }
        }
        composable<Route.Display> {
            DisplayScreen(
                readerViewModel,
                onBackClick = {
                    navController.popBackStack(Route.Camera, inclusive = false)
                }
            )
        }
        composable<Route.Settings> {
            SettingScreen(
                viewModel = readerViewModel,
                onBlackPlayerChanged = { name -> readerViewModel.updateBlackPlayer(name) },
                onWhitePlayerChanged = { name -> readerViewModel.updateWhitePlayer(name) },
                onGetGobanClick = {
                    // 設定画面からカメラに戻る場合
                    navController.popBackStack(Route.Camera, inclusive = false)
                },
                onHistoryClick = { navController.navigate(Route.History) }
            )
        }
        composable<Route.History> {
            // 履歴画面
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
