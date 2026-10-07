package com.github.tkirino.gobanreader.utility

import org.opencv.core.Point

object CornerUtils {
    /**
     * 各辺の1/36（0.5マス分）外側に拡張した新しい4隅の座標を計算（既存機能：100%保持）
     */
    fun calculateExpandedCorners(corners: List<Point>): List<Point> {
        return calculateExpandedCornersByRatio(corners, 36.0)
    }

    /**
     * 各辺の1/24（0.75マス分）外側に拡張した新しい4隅の座標を計算（新規：1.5倍幅 60x60px用）
     */
    fun calculateExpandedCorners60(corners: List<Point>): List<Point> {
        return calculateExpandedCornersByRatio(corners, 24.0)
    }

    /**
     * 四角形の各頂点を、隣接する2辺のベクトル長さに比例して外側へ拡張します。
     * 直線の交点計算を使わず、頂点ごとのベクトル合成で求めるため暴走しません。
     *
     * @param corners 元の4頂点 [TL(0), TR(1), BR(2), BL(3)] (OpenCV Point)
     * @param ratioDenominator 分母の値 (例: 36.0 や 24.0)
     * @return 拡張された4頂点 [TL, TR, BR, BL] (OpenCV Point)
     */
    fun calculateExpandedCornersByRatio(corners: List<Point>, ratioDenominator: Double): List<Point> {
        if (corners.size != 4 || ratioDenominator == 0.0) return corners

        val ratio = 1.0 / ratioDenominator

        val p0 = corners[0] // Top-Left
        val p1 = corners[1] // Top-Right
        val p2 = corners[2] // Bottom-Right
        val p3 = corners[3] // Bottom-Left

        // 各頂点から外側に向かう拡張ベクトルを合成

        // 1. Top-Left (p0): 上辺(p0->p1)の逆方向 & 左辺(p0->p3)の逆方向 へ伸ばす
        val newTL = Point(
            p0.x - (p1.x - p0.x) * ratio - (p3.x - p0.x) * ratio,
            p0.y - (p1.y - p0.y) * ratio - (p3.y - p0.y) * ratio
        )

        // 2. Top-Right (p1): 上辺(p0->p1)の正方向 & 右辺(p1->p2)の逆方向 へ伸ばす
        val newTR = Point(
            p1.x + (p1.x - p0.x) * ratio - (p2.x - p1.x) * ratio,
            p1.y + (p1.y - p0.y) * ratio - (p2.y - p1.y) * ratio
        )

        // 3. Bottom-Right (p2): 下辺(p3->p2)の正方向 & 右辺(p1->p2)の正方向 へ伸ばす
        val newBR = Point(
            p2.x + (p2.x - p3.x) * ratio + (p2.x - p1.x) * ratio,
            p2.y + (p2.y - p3.y) * ratio + (p2.y - p1.y) * ratio
        )

        // 4. Bottom-Left (p3): 下辺(p3->p2)の逆方向 & 左辺(p0->p3)の正方向 へ伸ばす
        val newBL = Point(
            p3.x - (p2.x - p3.x) * ratio + (p3.x - p0.x) * ratio,
            p3.y - (p2.y - p3.y) * ratio + (p3.y - p0.y) * ratio
        )

        return listOf(newTL, newTR, newBR, newBL)
    }
}
