package com.github.tkirino.gobanreader.corner

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.github.tkirino.gobanreader.MainViewModel
import com.github.tkirino.gobanreader.utility.CornerUtils
import org.opencv.core.Point
import kotlin.math.hypot
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

    // 画面全体および画像表示領域のサイズ計測
    var screenWidth by remember { mutableStateOf(0f) }
    var screenHeight by remember { mutableStateOf(0f) }
    var viewWidth by remember { mutableStateOf(0f) }
    var viewHeight by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    val bitmapWidth = bitmap.width.toFloat()
    val bitmapHeight = bitmap.height.toFloat()

    val scale = if (viewWidth > 0f && bitmapWidth > 0f) viewWidth / bitmapWidth else 1f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .navigationBarsPadding()
            .onSizeChanged { size ->
                screenWidth = size.width.toFloat()
                screenHeight = size.height.toFloat()
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .onSizeChanged { size ->
                    viewWidth = size.width.toFloat()
                    viewHeight = size.height.toFloat()
                }
        ) {
            Image(
                bitmap = imageBitmap,
                contentDescription = "Board",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(scale) {
                        detectDragGestures(
                            onDragStart = { touchPoint ->
                                if (scale == 0f) return@detectDragGestures
                                val rawX = touchPoint.x.toDouble() / scale
                                val rawY = touchPoint.y.toDouble() / scale

                                activeIndex = corners.indices.minByOrNull { i ->
                                    val c = corners[i]
                                    Math.hypot(c.x - rawX, c.y - rawY)
                                }
                                currentTouchPosition = touchPoint
                            },
                            onDrag = { change, dragAmount ->
                                val index = activeIndex ?: return@detectDragGestures
                                if (scale == 0f) return@detectDragGestures
                                change.consume()

                                currentTouchPosition = change.position

                                // 手振れ吸収デッドゾーン（小さなブレを無視）と繊細な移動感度(0.25f)
                                val dragDistance = hypot(dragAmount.x, dragAmount.y)
                                if (dragDistance > 1.5f) {
                                    val sensitivity = 0.25f
                                    val deltaX = (dragAmount.x.toDouble() / scale) * sensitivity
                                    val deltaY = (dragAmount.y.toDouble() / scale) * sensitivity

                                    val newCorners = corners.toMutableList()
                                    val current = newCorners[index]

                                    val nextX = (current.x + deltaX).coerceIn(0.0, bitmapWidth.toDouble())
                                    val nextY = (current.y + deltaY).coerceIn(0.0, bitmapHeight.toDouble())

                                    newCorners[index] = Point(nextX, nextY)
                                    corners = newCorners
                                }
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
                    p.x.toFloat() * scale,
                    p.y.toFloat() * scale
                )

                for (i in corners.indices) {
                    if (corners.isNotEmpty()) {
                        drawLine(
                            color = Color.Green,
                            strokeWidth = 5f,
                            start = toOffset(corners[i]),
                            end = toOffset(corners[(i + 1) % corners.size])
                        )
                    }
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

            // --- 虫眼鏡（ルーペ）：座標制限解除・動的配置・微調整最適化 ---
            val index = activeIndex
            val touchPos = currentTouchPosition
            if (index != null && touchPos != null && viewWidth > 0f && corners.indices.contains(index)) {
                val targetPoint = corners[index]
                val cropSize = 120f
                val halfCrop = cropSize / 2f

                val srcX = (targetPoint.x.toFloat() - halfCrop).coerceIn(0f, (bitmapWidth - cropSize).coerceAtLeast(0f)).toInt()
                val srcY = (targetPoint.y.toFloat() - halfCrop).coerceIn(0f, (bitmapHeight - cropSize).coerceAtLeast(0f)).toInt()

                val loupeSizeDp = 140.dp
                val loupeSizePx = with(density) { loupeSizeDp.toPx() }

                // 指が画面下部（盤面下側）にある場合は、虫眼鏡を指の上に配置しきれないため指の下へ退避
                val loupeX = (touchPos.x - loupeSizePx / 2).coerceIn(0f, viewWidth - loupeSizePx)
                val preferredY = if (touchPos.y > viewHeight * 0.6f) {
                    touchPos.y - loupeSizePx - 140f // 指のかなり上方に配置
                } else {
                    touchPos.y - loupeSizePx - 110f // 通常配置
                }

                // 上下に押し出されないよう全体高さ(screenHeight)側基準でマージンを調整
                val loupeY = preferredY.coerceIn(-viewHeight * 0.2f, viewHeight + 50f)

                Box(
                    modifier = Modifier
                        .offset { IntOffset(loupeX.roundToInt(), loupeY.roundToInt()) }
                        .size(loupeSizeDp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(3.dp, Color.Red, CircleShape)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawImage(
                            image = imageBitmap,
                            srcOffset = IntOffset(srcX, srcY),
                            srcSize = IntSize(cropSize.toInt(), cropSize.toInt()),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt())
                        )
                        val center = Offset(size.width / 2, size.height / 2)
                        drawLine(Color.Red, Offset(center.x - 30f, center.y), Offset(center.x + 30f, center.y), 4f)
                        drawLine(Color.Red, Offset(center.x, center.y - 30f), Offset(center.x, center.y + 30f), 4f)
                    }
                }
            }
        }

        Button(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .statusBarsPadding()
        ) {
            Text("戻る")
        }

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
