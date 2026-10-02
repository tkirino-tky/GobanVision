package com.github.tkirino.gobanreader.display

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import com.github.tkirino.gobanreader.model.StoneColor

@Composable
fun GoBoard(
    boardMatrix: List<List<StoneColor>>,
    certaintyMatrix: List<List<Boolean>> = List(19) { List(19) { true } }, // 【追加】確信度フラグマトリクス
    onIntersectionClick: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color(0xFFDCB35C))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val boardSize = size.width
                    val cellSize = boardSize / 20f
                    val padding = cellSize

                    val x = offset.x - padding
                    val y = offset.y - padding
                    val col = (x / cellSize).toInt()
                    val row = (y / cellSize).toInt()

                    if (row in 0 until 19 && col in 0 until 19) {
                        onIntersectionClick(row, col)
                    }
                }
            }
    ) {
        val boardSize = size.width
        val cellSize = boardSize / 20f
        val padding = cellSize

        // 1. 格子線（19本）の描画
        for (i in 0 until 19) {
            val offset = padding + (i * cellSize)
            drawLine(
                color = Color.Black,
                start = Offset(x = padding, y = offset),
                end = Offset(x = boardSize - padding, y = offset),
                strokeWidth = 1.5f
            )
            drawLine(
                color = Color.Black,
                start = Offset(x = offset, y = padding),
                end = Offset(x = offset, y = boardSize - padding),
                strokeWidth = 1.5f
            )
        }

        // 2. 星（点）の描画
        val starIndices = listOf(3, 9, 15)
        val starRadius = cellSize * 0.1f
        for (row in starIndices) {
            for (col in starIndices) {
                drawCircle(
                    color = Color.Black,
                    radius = starRadius,
                    center = Offset(padding + (col * cellSize), padding + (row * cellSize))
                )
            }
        }

        // 3. 石の描画 ＆ 「自信なし（?）」マークのオーバーレイ描画
        val stoneRadius = (cellSize * 0.92f) / 2f

        // Native Text Paint の準備（派手な「？」用）
        val textPaint = Paint().apply {
            color = android.graphics.Color.RED
            textSize = cellSize * 0.85f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        for (row in 0 until 19) {
            for (col in 0 until 19) {
                val stone = boardMatrix[row][col]
                val isCertain = certaintyMatrix.getOrNull(row)?.getOrNull(col) ?: true
                val cx = padding + (col * cellSize)
                val cy = padding + (row * cellSize)

                // 3-A. 石（黒石・白石）の描画
                if (stone != StoneColor.EMPTY) {
                    when (stone) {
                        StoneColor.BLACK -> {
                            drawCircle(
                                color = Color.Black,
                                radius = stoneRadius,
                                center = Offset(cx, cy)
                            )
                        }
                        StoneColor.WHITE -> {
                            drawCircle(
                                color = Color.White,
                                radius = stoneRadius,
                                center = Offset(cx, cy)
                            )
                            drawCircle(
                                color = Color.LightGray,
                                radius = stoneRadius,
                                center = Offset(cx, cy),
                                style = Stroke(width = 1f)
                            )
                        }
                        else -> {}
                    }
                }

                // 3-B. 自信なし（!isCertain）の場合は交点の上に「？」と目立つ背景を描画
                if (!isCertain) {
                    // 目立つように黄色の丸背景を敷く（空点でも石の上でもくっきり浮き出ます）
                    drawCircle(
                        color = Color(0xFFFFEB3B), // 鮮やかな黄色
                        radius = stoneRadius * 0.75f,
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = Color.Red,
                        radius = stoneRadius * 0.75f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 2f)
                    )

                    // 赤色の太字「？」テキストを描画
                    // テキスト描画のY軸調整（ベースライン補正）
                    val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
                    drawContext.canvas.nativeCanvas.drawText(
                        "?",
                        cx,
                        textY,
                        textPaint
                    )
                }
            }
        }
    }
}
