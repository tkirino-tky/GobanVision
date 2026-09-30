package com.github.tkirino.gobanreader.camera

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.tkirino.gobanreader.MainViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class SquareTextureView(context: Context) : TextureView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    onDetectionSuccess: () -> Unit,
    onManualInputClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    // デフォルトの広角倍率（0.8f）
    val defaultZoomRatio = 0.8f

    val camera2Manager = remember { Camera2Manager(context) }
    var currentTextureView by remember { mutableStateOf<TextureView?>(null) }

    var isCapturing by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp

    // カメラの実際の解像度比率に合わせて歪みを吸収する関数
    fun fixAspectRatio(textureView: TextureView, previewSize: Size) {
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (viewWidth <= 0 || viewHeight <= 0) return

        val imgWidth = previewSize.height.toFloat()
        val imgHeight = previewSize.width.toFloat()

        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val imgRatio = imgWidth / imgHeight

        val matrix = Matrix()
        val scaleX: Float
        val scaleY: Float

        if (imgRatio < viewRatio) {
            scaleX = 1.0f
            scaleY = viewRatio / imgRatio
        } else {
            scaleX = imgRatio / viewRatio
            scaleY = 1.0f
        }

        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        textureView.setTransform(matrix)
    }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            camera2Manager.closeCamera()
        }
    }

    fun captureAndProcess() {
        if (isCapturing) return
        isCapturing = true

        try {
            val textureView = currentTextureView ?: run {
                isCapturing = false
                return
            }

            val rawBitmap = textureView.bitmap ?: run {
                isCapturing = false
                return
            }

            // 歪み補正Matrixを反映したBitmapを生成
            val transformMatrix = Matrix()
            textureView.getTransform(transformMatrix)
            val bitmap = Bitmap.createBitmap(
                rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, transformMatrix, true
            )

            if (rawBitmap != bitmap && !rawBitmap.isRecycled) {
                rawBitmap.recycle()
            }

            val timestamp = System.currentTimeMillis()
            val file = File(context.cacheDir, "board_capture_$timestamp.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            viewModel.loadPhotoForAdjustment(file.absolutePath) { isGood ->
                kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                    isCapturing = false
                    if (isGood) {
                        val detectedCorners = viewModel.uiState.value.initialCorners
                        val expanded = com.github.tkirino.gobanreader.utility.CornerUtils.calculateExpandedCorners(detectedCorners)
                        viewModel.processWithCorners(expanded)
                        onDetectionSuccess()
                    } else {
                        onManualInputClick()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("CameraScreen", "キャプチャ処理エラー", e)
            isCapturing = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .size(screenWidthDp, screenWidthDp)
                .align(Alignment.Center)
                .background(Color.DarkGray)
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            if (cameraPermissionState.status.isGranted) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SquareTextureView(ctx).apply {
                            currentTextureView = this
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                                    // Surfaceが準備完了した時に一度だけオープン
                                    camera2Manager.openCamera(this@apply, defaultZoomRatio) { size ->
                                        fixAspectRatio(this@apply, size)
                                    }
                                }
                                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                                    fixAspectRatio(this@apply, camera2Manager.previewSize)
                                }
                                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                                    camera2Manager.closeCamera()
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
                            }
                        }
                    },
                    update = { textureView ->
                        currentTextureView = textureView
                    }
                )
            }
        }

        // 上部ナビゲーションバー（倍率ボタンを削除し、「戻る」と「設定」のみ配置）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBackClick) {
                Text("戻る")
            }

            Button(onClick = onSettingsClick) {
                Text("設定")
            }
        }

        // 下部キャプチャボタン
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = { captureAndProcess() },
                enabled = !isCapturing,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
            ) {
                Text(
                    text = if (isCapturing) "処理中..." else "次へ（認識・調整へ進む）",
                    fontSize = 16.sp,
                    color = Color.White
                )
            }
        }
    }
}
