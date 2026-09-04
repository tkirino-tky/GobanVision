package com.github.tkirino.gobanreader.vision

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.core.MatOfPoint2f
import org.opencv.imgproc.Imgproc

object BoardRectifier {

    fun rectify(src: Mat, corners: List<Point>): Mat {
        val boardSize = 1000.0

        // 念のためサイズをチェックし、4点でない場合はデフォルトの四隅（または安全な矩形）にフォールバックする
        val validCorners = if (corners.size == 4) {
            corners
        } else {
            // 万が一サイズがおかしい場合の安全策
            listOf(
                Point(0.0, 0.0),
                Point(src.cols().toDouble(), 0.0),
                Point(src.cols().toDouble(), src.rows().toDouble()),
                Point(0.0, src.rows().toDouble())
            )
        }

        val srcMat = MatOfPoint2f(
            validCorners[0],
            validCorners[1],
            validCorners[2],
            validCorners[3]
        )

        val destPoints = listOf(
            Point(0.0, 0.0),
            Point(boardSize, 0.0),
            Point(boardSize, boardSize),
            Point(0.0, boardSize)
        )
        val dstMat = MatOfPoint2f(
            destPoints[0],
            destPoints[1],
            destPoints[2],
            destPoints[3]
        )

        val transform = Imgproc.getPerspectiveTransform(srcMat, dstMat)
        val result = Mat()
        Imgproc.warpPerspective(src, result, transform, Size(boardSize, boardSize))

        srcMat.release()
        dstMat.release()
        transform.release()

        return result
    }
}
