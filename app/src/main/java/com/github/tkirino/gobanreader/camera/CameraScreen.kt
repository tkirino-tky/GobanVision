package com.github.tkirino.gobanreader.camera

import android.Manifest
import android.media.MediaActionSound
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.tkirino.gobanreader.MainViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.io.File

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    onStartReadingClick: (File) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    // 実際に切り替えたい倍率のリスト（超広角の 0.8x と 標準の 1.0x）
    val supportedZooms = listOf(0.8f, 1.0f)
    var currentZoom by remember { mutableStateOf(0.8f) }

    val camera2Manager = remember { Camera2Manager(context) }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    val takePhoto = {
        val photoFile = File(context.cacheDir, "goban_photo.jpg")
        sound.play(MediaActionSound.SHUTTER_CLICK)
        camera2Manager.takePicture(photoFile) { savedFile ->
            onStartReadingClick(savedFile)
        }
    }

    LaunchedEffect(viewModel.remoteShutterTrigger) {
        if (viewModel.remoteShutterTrigger > 0) {
            takePhoto()
        }
    }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) cameraPermissionState.launchPermissionRequest()
    }

    DisposableEffect(Unit) {
        onDispose {
            camera2Manager.closeCamera()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraPermissionState.status.isGranted) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .align(Alignment.Center)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                                    camera2Manager.openCamera(this@apply, currentZoom) {
                                        Log.d("CameraScreen", "プレビュー開始 (zoom: $currentZoom)")
                                    }
                                }
                                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                                    camera2Manager.closeCamera()
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
                            }
                        }
                    },
                    update = { textureView ->
                        // 倍率（currentZoom）が変更されたときにカメラを再オープンしてレンズを切り替える
                        if (textureView.isAvailable) {
                            camera2Manager.openCamera(textureView, currentZoom) {
                                Log.d("CameraScreen", "倍率変更による再オープン (zoom: $currentZoom)")
                            }
                        }
                    }
                )
            }
        }

        // 碁盤枠ガイド
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .aspectRatio(1f / 1.04f)
                .align(Alignment.Center)
                .border(2.dp, Color.White.copy(alpha = 0.7f))
        )

        // 戻るボタン
        Button(
            onClick = onBackClick,
            modifier = Modifier.align(Alignment.TopStart).padding(20.dp)
        ) {
            Text("戻る")
        }

        // 倍率変更ボタン群（クリックで実際にカメラレンズが切り替わります）
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            supportedZooms.forEach { ratio ->
                val isSelected = (currentZoom == ratio)
                Button(
                    onClick = {
                        currentZoom = ratio
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) Color.White else Color.Black.copy(alpha = 0.5f),
                        contentColor = if (isSelected) Color.Black else Color.White
                    ),
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(text = "${ratio}x", fontSize = 12.sp)
                }
            }
        }

        // シャッターボタン
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp)
                .size(80.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { takePhoto() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.8f),
                    radius = size.minDimension / 2f,
                    style = Stroke(width = 6.dp.toPx())
                )
            }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.White, CircleShape)
            )
        }
    }
}
