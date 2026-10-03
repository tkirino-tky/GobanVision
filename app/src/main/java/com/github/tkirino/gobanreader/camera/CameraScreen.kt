package com.github.tkirino.gobanreader.camera

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
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
    val coroutineScope = rememberCoroutineScope()

    val defaultZoomRatio = 0.8f
    val camera2Manager = remember { Camera2Manager(context) }
    var currentTextureView by remember { mutableStateOf<TextureView?>(null) }

    var isCapturing by remember { mutableStateOf(false) }

    // リアルタイム検出用のステート
    var detectedCorners by remember { mutableStateOf<List<Point>?>(null) }
    var isDetecting by remember { mutableStateOf(false) }
    var lastDetectionTime by remember { mutableLongStateOf(0L) }

    // 発熱対策：連続未検出カウントと動的サンプリング間隔 (ms)
    var missCount by remember { mutableIntStateOf(0) }
    var currentIntervalMs by remember { mutableLongStateOf(100L) }

    // 検出成功時の画像と原寸座標をフリーズ保持（手振れ・決定時ズレ対策）
    var lastDetectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lastDetectedRawCorners by remember { mutableStateOf<List<Point>?>(null) }

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp

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

    // リアルタイムYOLO推論処理（動的サンプリング付き）
    fun processFrameForCorners(textureView: TextureView) {
        val currentTime = System.currentTimeMillis()
        if (isDetecting || isCapturing || currentTime - lastDetectionTime < currentIntervalMs) return

        val rawBitmap = textureView.bitmap ?: return
        isDetecting = true
        lastDetectionTime = currentTime

        coroutineScope.launch(Dispatchers.Default) {
            try {
                val transformMatrix = Matrix()
                withContext(Dispatchers.Main) {
                    textureView.getTransform(transformMatrix)
                }

                val bitmap = Bitmap.createBitmap(
                    rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, transformMatrix, true
                )

                val imgCols = bitmap.width
                val imgRows = bitmap.height
                val squareSize = minOf(imgCols, imgRows)
                val startX = (imgCols - squareSize) / 2
                val startY = (imgRows - squareSize) / 2

                val guideRect = Rect(startX, startY, squareSize, squareSize)

                val fullMat = Mat()
                Utils.bitmapToMat(bitmap, fullMat)
                val croppedMat = Mat(fullMat, guideRect).clone()
                fullMat.release()

                val yoloDetector = viewModel.yoloCornerDetector
                val result = yoloDetector?.detectCorners(croppedMat, Rect(0, 0, squareSize, squareSize))

                croppedMat.release()

                // CameraScreen.kt の processFrameForCorners 内（成功時ブロック）
                withContext(Dispatchers.Main) {
                    if (result != null && result.corners.size == 4) {
                        missCount = 0
                        currentIntervalMs = 100L

                        val scale = textureView.width.toFloat() / squareSize.toFloat()
                        detectedCorners = result.corners.map { Point(it.x * scale, it.y * scale) }

                        val squareBitmap = Bitmap.createBitmap(bitmap, startX, startY, squareSize, squareSize)
                        lastDetectedBitmap?.recycle()
                        lastDetectedBitmap = squareBitmap
                        lastDetectedRawCorners = result.corners

                        // ★以下の行を削除（またはコメントアウト）してください
                        // viewModel.pushFrameForInference(squareBitmap, result.corners)

                        if (rawBitmap != bitmap && !bitmap.isRecycled) bitmap.recycle()
                    } else {
                        // 検出失敗時：段階的にサンプリング間隔を伸ばす（発熱抑制）
                        missCount++
                        currentIntervalMs = when {
                            missCount > 30 -> 1000L // 約3秒以上見つからない場合は1秒に1回
                            missCount > 10 -> 500L  // 約1秒以上見つからない場合は0.5秒に1回
                            else -> 100L
                        }
                        detectedCorners = null
                        if (rawBitmap != bitmap && !bitmap.isRecycled) bitmap.recycle()
                    }
                    if (!rawBitmap.isRecycled) rawBitmap.recycle()
                    isDetecting = false
                }
            } catch (e: Exception) {
                Log.e("CameraScreen", "フレーム解析エラー", e)
                withContext(Dispatchers.Main) {
                    isDetecting = false
                }
            }
        }
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

    // 決定（直接認識）処理：検出成功時点の画像を使用して直ちに解析へ進む
    fun processDirectly() {
        if (isCapturing) return
        val bitmap = lastDetectedBitmap
        val corners = lastDetectedRawCorners

        if (bitmap == null || corners == null || corners.size != 4) return
        isCapturing = true

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                val file = File(context.cacheDir, "board_capture_$timestamp.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                viewModel.loadPhotoForAdjustment(file.absolutePath) { _ ->
                    coroutineScope.launch(Dispatchers.Main) {
                        isCapturing = false
                        val expanded = com.github.tkirino.gobanreader.utility.CornerUtils.calculateExpandedCorners(corners)
                        viewModel.processWithCorners(expanded)
                        onDetectionSuccess()
                    }
                }
            } catch (e: Exception) {
                Log.e("CameraScreen", "ダイレクト認識処理エラー", e)
                withContext(Dispatchers.Main) {
                    isCapturing = false
                }
            }
        }
    }

    // 手動ボタン押下時処理：検出成功時点の画像をロードしてCornerScreenへ遷移する
    fun processManual() {
        if (isCapturing) return
        val bitmap = lastDetectedBitmap
        val corners = lastDetectedRawCorners

        if (bitmap == null || corners == null) {
            onManualInputClick()
            return
        }
        isCapturing = true

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                val file = File(context.cacheDir, "board_capture_$timestamp.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                viewModel.loadPhotoForAdjustment(file.absolutePath) { _ ->
                    coroutineScope.launch(Dispatchers.Main) {
                        isCapturing = false
                        viewModel.setInitialCorners(corners)
                        onManualInputClick()
                    }
                }
            } catch (e: Exception) {
                Log.e("CameraScreen", "手動調整ロードエラー", e)
                withContext(Dispatchers.Main) {
                    isCapturing = false
                    onManualInputClick()
                }
            }
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
                                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                                    processFrameForCorners(this@apply)
                                }
                            }
                        }
                    },
                    update = { textureView ->
                        currentTextureView = textureView
                    }
                )

                // リアルタイムオーバーレイ描画
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val corners = detectedCorners
                    val isReady = corners != null && corners.size == 4
                    val lineColor = if (isReady) Color.Green else Color.Yellow
                    val strokeWidth = 3.dp.toPx()

                    if (isReady && corners != null) {
                        val path = Path().apply {
                            moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
                            lineTo(corners[1].x.toFloat(), corners[1].y.toFloat())
                            lineTo(corners[2].x.toFloat(), corners[2].y.toFloat())
                            lineTo(corners[3].x.toFloat(), corners[3].y.toFloat())
                            close()
                        }
                        drawPath(
                            path = path,
                            color = lineColor,
                            style = Stroke(width = strokeWidth)
                        )

                        corners.forEach { point ->
                            drawCircle(
                                color = lineColor,
                                radius = 8.dp.toPx(),
                                center = Offset(point.x.toFloat(), point.y.toFloat())
                            )
                        }
                    }
                }
            }
        }

        // 上部ナビゲーションバー
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

        // 下部ボタン群とステータス表示
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val isReady = detectedCorners != null
            Text(
                text = if (isReady) "碁盤を認識しました（Ready）" else "碁盤を探索中...",
                color = if (isReady) Color.Green else Color.Yellow,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 手動調整モードボタン（CornerScreenへ遷移）
                OutlinedButton(
                    onClick = { processManual() },
                    enabled = !isCapturing && isReady,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) {
                    Text(
                        text = "手動",
                        fontSize = 16.sp,
                        color = Color.White
                    )
                }

                // ダイレクト認識決定ボタン（直接DisplayScreenへ遷移）
                Button(
                    onClick = { processDirectly() },
                    enabled = !isCapturing && isReady,
                    modifier = Modifier
                        .weight(2f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isReady) Color(0xFF2E7D32) else Color.Gray
                    )
                ) {
                    Text(
                        text = if (isCapturing) "処理中..." else "この画角で決定（認識へ）",
                        fontSize = 16.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}
