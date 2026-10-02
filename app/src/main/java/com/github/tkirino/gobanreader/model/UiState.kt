package com.github.tkirino.gobanreader.model

import android.graphics.Bitmap
import org.opencv.core.Point

data class ReaderUiState(
    val adjustmentBitmap: Bitmap? = null,
    val initialCorners: List<Point> = emptyList(),
    val rawCorners: List<Point> = emptyList(),
    val gameRecord: GameRecord = GameRecord(),

    // 19x19 の盤面レイアウト
    val boardLayout: List<List<StoneColor>> =
        List(gameRecord.boardSize) { List(gameRecord.boardSize) { StoneColor.EMPTY } },

    // 19x19 の確信度フラグ（false の場所は「?」表示）
    val certaintyLayout: List<List<Boolean>> =
        List(gameRecord.boardSize) { List(gameRecord.boardSize) { true } },

    val blackCaptured: Int = 0,
    val whiteCaptured: Int = 0,
    val isLoading: Boolean = false
)
