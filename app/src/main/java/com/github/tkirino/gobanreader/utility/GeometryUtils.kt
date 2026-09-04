package com.github.tkirino.gobanreader.utility

import org.opencv.core.Rect
import org.opencv.core.Point
import kotlin.math.hypot

object GeometryUtils {
    // offsetPercent を引数に追加しました
    fun calculateGuideRect(width: Double, height: Double, offsetPercent: Double = 0.0): Rect {
        val guideW = width * 0.8
        val guideH = guideW * 1.04

        // オフセット分を計算します
        val offsetW = width * offsetPercent
        val offsetH = height * offsetPercent

        // 元の枠をベースに、オフセット分だけ拡大（または縮小）させます
        val actualW = guideW + (offsetW * 2)
        val actualH = guideH + (offsetH * 2)

        val guideLeft = (width - actualW) / 2.0
        val guideTop = (height - actualH) / 2.0

        val margin = 2
        val x = (guideLeft + margin).toInt().coerceIn(0, width.toInt() - 1)
        val y = (guideTop + margin).toInt().coerceIn(0, height.toInt() - 1)
        val w = (actualW - margin * 2).toInt().coerceAtMost(width.toInt() - x)
        val h = (actualH - margin * 2).toInt().coerceAtMost(height.toInt() - y)

        return Rect(x, y, w, h)
    }
}

object CornerUtils {
    /**
     * ユーザーが指定した4隅の座標（左上、右上、右下、左下）を受け取り、
     * 各辺の1/36（1/2マス分）外側に拡張した新しい4隅の座標を計算して返す。
     */
    fun calculateExpandedCorners(corners: List<Point>): List<Point> {
        if (corners.size != 4) return corners
        val tl = corners[0]
        val tr = corners[1]
        val br = corners[2]
        val bl = corners[3]

        // 1. 各辺の長さを算出
        val lTop = hypot(tr.x - tl.x, tr.y - tl.y)
        val lBottom = hypot(br.x - bl.x, br.y - bl.y)
        val lLeft = hypot(bl.x - tl.x, bl.y - tl.y)
        val lRight = hypot(br.x - tr.x, br.y - tr.y)

        if (lTop == 0.0 || lBottom == 0.0 || lLeft == 0.0 || lRight == 0.0) return corners

        // 2. 拡張幅（19路盤の場合、1辺は18マスなのでその1/36 = 1/2マス）
        val offsetTop = lTop / 36.0
        val offsetBottom = lBottom / 36.0
        val offsetLeft = lLeft / 36.0
        val offsetRight = lRight / 36.0

        // 3. 各辺の単位ベクトルを計算
        val topDirX = (tr.x - tl.x) / lTop
        val topDirY = (tr.y - tl.y) / lTop

        val bottomDirX = (br.x - bl.x) / lBottom
        val bottomDirY = (br.y - bl.y) / lBottom

        val leftDirX = (bl.x - tl.x) / lLeft
        val leftDirY = (bl.y - tl.y) / lLeft

        val rightDirX = (br.x - tr.x) / lRight
        val rightDirY = (br.y - tr.y) / lRight

        // 4. 重心（中心）を基準にして「外側」を向く法線ベクトルを算出し、各辺を平行移動する
        val centerX = (tl.x + tr.x + br.x + bl.x) / 4.0
        val centerY = (tl.y + tr.y + br.y + bl.y) / 4.0

        // 各辺の中点から重心へのベクトルとは逆向き（外側）へそれぞれオフセットさせる
        // 上辺 (tl -> tr)
        val topMidX = (tl.x + tr.x) / 2.0
        val topMidY = (tl.y + tr.y) / 2.0
        val topOutX = topMidX - centerX
        val topOutY = topMidY - centerY
        val topLen = hypot(topOutX, topOutY).let { if (it == 0.0) 1.0 else it }
        val sTopX = topOutX / topLen
        val sTopY = topOutY / topLen

        val exTlX = tl.x + sTopX * offsetTop
        val exTlY = tl.y + sTopY * offsetTop
        val exTrX = tr.x + sTopX * offsetTop
        val exTrY = tr.y + sTopY * offsetTop

        // 下辺 (bl -> br)
        val bottomMidX = (bl.x + br.x) / 2.0
        val bottomMidY = (bl.y + br.y) / 2.0
        val botOutX = bottomMidX - centerX
        val botOutY = bottomMidY - centerY
        val botLen = hypot(botOutX, botOutY).let { if (it == 0.0) 1.0 else it }
        val sBotX = botOutX / botLen
        val sBotY = botOutY / botLen

        val exBlX = bl.x + sBotX * offsetBottom
        val exBlY = bl.y + sBotY * offsetBottom
        val exBrX = br.x + sBotX * offsetBottom
        val exBrY = br.y + sBotY * offsetBottom

        // 左辺 (tl -> bl)
        val leftMidX = (tl.x + bl.x) / 2.0
        val leftMidY = (tl.y + bl.y) / 2.0
        val lOutX = leftMidX - centerX
        val lOutY = leftMidY - centerY
        val lLen = hypot(lOutX, lOutY).let { if (it == 0.0) 1.0 else it }
        val sLeftX = lOutX / lLen
        val sLeftY = lOutY / lLen

        val shiftLeftX = sLeftX * offsetLeft
        val shiftLeftY = sLeftY * offsetLeft

        // 右辺 (tr -> br)
        val rightMidX = (tr.x + br.x) / 2.0
        val rightMidY = (tr.y + br.y) / 2.0
        val rOutX = rightMidX - centerX
        val rOutY = rightMidY - centerY
        val rLen = hypot(rOutX, rOutY).let { if (it == 0.0) 1.0 else it }
        val sRightX = rOutX / rLen
        val sRightY = rOutY / rLen

        val shiftRightX = sRightX * offsetRight
        val shiftRightY = sRightY * offsetRight

        // 5. 拡張した上下の直線と左右の直線の交点を計算して、新しい4隅を確定する
        val newTl = findIntersection(
            exTlX, exTlY, exTrX, exTrY,
            exTlX + shiftLeftX, exTlY + shiftLeftY, exBlX + shiftLeftX, exBlY + shiftLeftY
        )
        val newTr = findIntersection(
            exTlX, exTlY, exTrX, exTrY,
            exTrX + shiftRightX, exTrY + shiftRightY, exBrX + shiftRightX, exBrY + shiftRightY
        )
        val newBr = findIntersection(
            exBlX, exBlY, exBrX, exBrY,
            exTrX + shiftRightX, exTrY + shiftRightY, exBrX + shiftRightX, exBrY + shiftRightY
        )
        val newBl = findIntersection(
            exBlX, exBlY, exBrX, exBrY,
            exTlX + shiftLeftX, exTlY + shiftLeftY, exBlX + shiftLeftX, exBlY + shiftLeftY
        )

        return listOf(newTl, newTr, newBr, newBl)
    }

    private fun findIntersection(
        x1: Double, y1: Double, x2: Double, y2: Double,
        x3: Double, y3: Double, x4: Double, y4: Double
    ): Point {
        val denom = (y4 - y3) * (x2 - x1) - (x4 - x3) * (y2 - y1)
        if (denom == 0.0) return Point(x1, y1)
        val ua = ((x4 - x3) * (y1 - y3) - (y4 - y3) * (x1 - x3)) / denom
        return Point(x1 + ua * (x2 - x1), y1 + ua * (y2 - y1))
    }
}



