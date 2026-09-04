package com.github.tkirino.gobanreader.corner

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.github.tkirino.gobanreader.MainViewModel
import com.github.tkirino.gobanreader.utility.CornerUtils
import org.opencv.core.Point
import kotlin.math.roundToInt

@Composable
fun CornerScreen(
    viewModel: MainViewModel,
    bitmap: Bitmap,
    initialCorners: List<Point>,
    rawDetection: List<Point>,
    onConfirmed: (List<Point>) -> Unit,
    onBack: () -> Unit
) {
    var corners by remember(initialCorners) { mutableStateOf(initialCorners) }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    var activeIndex by remember { mutableStateOf<Int?>(null) }
    var currentTouchPosition by remember { mutableStateOf<Offset?>(null) }

    // 実際の描画サイズをピクセル単位で保持するステート
    var viewWidth by remember { mutableStateOf(0f) }
    var viewHeight by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    val bitmapWidth = bitmap.width.toFloat()
    val bitmapHeight = bitmap.height.toFloat()

    // サイズがまだ決まっていない初期値（0）のときの安全策
    val scale = if (viewWidth > 0f && viewHeight > 0f) {
        minOf(viewWidth / bitmapWidth, viewHeight / bitmapHeight)
    } else {
        1f
    }
    val offsetX = if (viewWidth > 0f) (viewWidth - bitmapWidth * scale) / 2 else 0f
    val offsetY = if (viewHeight > 0f) (viewHeight - bitmapHeight * scale) / 2 else 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .onSizeChanged { size ->
                viewWidth = size.width.toFloat()
                viewHeight = size.height.toFloat()
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                bitmap = imageBitmap,
                contentDescription = "Board",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { touchPoint ->
                                if (scale == 0f) return@detectDragGestures
                                val rawX = (touchPoint.x.toDouble() - offsetX) / scale
                                val rawY = (touchPoint.y.toDouble() - offsetY) / scale

                                activeIndex = corners.indices.minByOrNull { i ->
                                    val c = corners[i]
                                    Math.hypot(c.x - rawX, c.y - rawY)
                                }
                                currentTouchPosition = touchPoint
                            },
                            onDrag = { change, dragAmount ->
                                val index = activeIndex ?: return@detectDragGestures
                                if (scale == 0f) return@detectDragGestures
                                currentTouchPosition = change.position

                                val sensitivity = 0.3f
                                val deltaX = (dragAmount.x.toDouble() / scale) * sensitivity
                                val deltaY = (dragAmount.y.toDouble() / scale) * sensitivity

                                val newCorners = corners.toMutableList()
                                val current = newCorners[index]

                                newCorners[index] = Point(
                                    (current.x + deltaX).coerceIn(0.0, bitmapWidth.toDouble()),
                                    (current.y + deltaY).coerceIn(0.0, bitmapHeight.toDouble())
                                )
                                corners = newCorners
                            },
                            onDragEnd = {
                                activeIndex = null
                                currentTouchPosition = null
                            },
                            onDragCancel = {
                                activeIndex = null
                                currentTouchPosition = null
                            }
                        )
                    }
            ) {
                if (scale == 0f) return@Canvas
                fun toOffset(p: Point) = Offset(
                    (p.x.toFloat() * scale) + offsetX.toFloat(),
                    (p.y.toFloat() * scale) + offsetY.toFloat()
                )

                for (i in corners.indices) {
                    drawLine(
                        color = Color.Green,
                        strokeWidth = 5f,
                        start = toOffset(corners[i]),
                        end = toOffset(corners[(i + 1) % 4])
                    )
                }

                val markerRadius = 25f
                val crossHairLength = 40f
                val strokeWidth = 4f
                corners.forEach { point ->
                    val center = toOffset(point)
                    drawCircle(
                        color = Color.Red,
                        radius = markerRadius,
                        center = center,
                        style = Stroke(width = strokeWidth)
                    )
                    drawLine(
                        color = Color.Red,
                        start = Offset(center.x - crossHairLength, center.y),
                        end = Offset(center.x + crossHairLength, center.y),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = Color.Red,
                        start = Offset(center.x, center.y - crossHairLength),
                        end = Offset(center.x, center.y + crossHairLength),
                        strokeWidth = strokeWidth
                    )
                }
            }

            val index = activeIndex
            val touchPos = currentTouchPosition
            if (index != null && touchPos != null && viewWidth > 0f) {
                val targetPoint = corners[index]

                val px = targetPoint.x.toInt().coerceIn(0, bitmap.width - 1)
                val py = targetPoint.y.toInt().coerceIn(0, bitmap.height - 1)

                val cropSize = 180
                val halfCrop = cropSize / 2
                val startX = (px - halfCrop).coerceIn(0, bitmap.width - cropSize)
                val startY = (py - halfCrop).coerceIn(0, bitmap.height - cropSize)
                val actualWidth = minOf(cropSize, bitmap.width - startX)
                val actualHeight = minOf(cropSize, bitmap.height - startY)

                if (actualWidth > 0 && actualHeight > 0) {
                    val croppedMagnifiedBitmap = remember(targetPoint, bitmap) {
                        try {
                            Bitmap.createBitmap(bitmap, startX, startY, actualWidth, actualHeight)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (croppedMagnifiedBitmap != null) {
                        val magnifiedImageBitmap = remember(croppedMagnifiedBitmap) {
                            croppedMagnifiedBitmap.asImageBitmap()
                        }

                        val loupeSizeDp = 130.dp
                        val loupeSizePx = with(density) { loupeSizeDp.toPx() }
                        val loupeX = (touchPos.x - loupeSizePx / 2).coerceIn(0f, viewWidth - loupeSizePx)
                        val loupeY = (touchPos.y - loupeSizePx - 100f).coerceIn(0f, viewHeight - loupeSizePx)

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(loupeX.roundToInt(), loupeY.roundToInt()) }
                                .size(loupeSizeDp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .border(3.dp, Color.Red, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = magnifiedImageBitmap,
                                contentDescription = "Magnifier",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds
                            )
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val center = Offset(size.width / 2, size.height / 2)
                                drawLine(Color.Red, Offset(center.x - 30f, center.y), Offset(center.x + 30f, center.y), 4f)
                                drawLine(Color.Red, Offset(center.x, center.y - 30f), Offset(center.x, center.y + 30f), 4f)
                            }
                        }
                    }
                }
            }

            // 画面左上の戻るボタン
            Button(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .statusBarsPadding()
            ) {
                Text("戻る")
            }
        }

        // 画面下部の判定メッセージと確定ボタン
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = Color.White,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .border(1.dp, Color.Black, MaterialTheme.shapes.small),
                shadowElevation = 4.dp
            ) {
                Text(
                    text = viewModel.cornerQualityMessage,
                    color = Color.Black,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Button(
                onClick = {
                    val expanded = CornerUtils.calculateExpandedCorners(corners)
                    onConfirmed(expanded)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("この範囲で確定")
            }
        }
    }
}
