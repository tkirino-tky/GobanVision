package com.github.tkirino.gobanreader.camera

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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

    val supportedZooms = listOf(0.8f, 1.0f)
    var currentZoom by remember { mutableStateOf(0.8f) }

    val camera2Manager = remember { Camera2Manager(context) }
    var currentTextureView by remember { mutableStateOf<TextureView?>(null) }

    // 重複キャプチャ防止用フラグ
    var isCapturing by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp

    fun adjustTextureViewTransform(textureView: TextureView, viewWidth: Int, viewHeight: Int) {
        val matrix = Matrix()

        // カメラの入力アスペクト比（縦長 3:4）
        val imageWidth = 1080f
        val imageHeight = 1440f

        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val imageRatio = imageWidth / imageHeight

        val scaleX: Float
        val scaleY: Float

        if (imageRatio < viewRatio) {
            scaleX = 1.0f
            scaleY = viewRatio / imageRatio
        } else {
            scaleX = imageRatio / viewRatio
            scaleY = 1.0f
        }

        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        textureView.setTransform(matrix)
    }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) cameraPermissionState.launchPermissionRequest()
    }

    LaunchedEffect(currentZoom) {
        currentTextureView?.let { textureView ->
            if (textureView.isAvailable) {
                camera2Manager.openCamera(textureView, currentZoom) {
                    Log.d("CameraScreen", "ズーム変更による再オープン (zoom: $currentZoom)")
                }
            }
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

            val viewWidth = textureView.width
            val viewHeight = textureView.height

            if (viewWidth <= 0 || viewHeight <= 0) {
                isCapturing = false
                return
            }

            // TextureViewに適用されているTransform Matrixを取得し、そのままBitmapに適用して生成
            val transformMatrix = Matrix()
            textureView.getTransform(transformMatrix)

            val rawBitmap = textureView.bitmap ?: run {
                isCapturing = false
                return
            }

            // 画面表示と同じMatrixを適用して、正しく補正されたBitmapを作成
            val bitmap = Bitmap.createBitmap(
                rawBitmap,
                0,
                0,
                rawBitmap.width,
                rawBitmap.height,
                transformMatrix,
                true
            )

            if (rawBitmap != bitmap && !rawBitmap.isRecycled) {
                rawBitmap.recycle()
            }

            // PNG形式でファイル保存
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
                                    adjustTextureViewTransform(this@apply, width, height)
                                    camera2Manager.openCamera(this@apply, currentZoom) {
                                        Log.d("CameraScreen", "正方形プレビュー開始 (zoom: $currentZoom)")
                                    }
                                }
                                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                                    adjustTextureViewTransform(this@apply, width, height)
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
                        if (textureView.isAvailable) {
                            adjustTextureViewTransform(textureView, textureView.width, textureView.height)
                        }
                    }
                )
            }
        }

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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                supportedZooms.forEach { ratio ->
                    val isSelected = (currentZoom == ratio)
                    Button(
                        onClick = { currentZoom = ratio },
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

            Button(onClick = onSettingsClick) {
                Text("設定")
            }
        }

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
