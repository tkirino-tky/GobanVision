package com.github.tkirino.gobanreader.stones

import android.graphics.Bitmap
import android.util.Log
import com.github.tkirino.gobanreader.model.StoneColor
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CnnStoneDetector(private val interpreter: Interpreter) {

    private val patchRadius = 20

    fun detectStones(
        rectifiedMat: Mat,
        geometryGrid: Array<Array<Point>>
    ): List<List<StoneColor>> {
        val boardLayout = MutableList(19) { MutableList(19) { StoneColor.EMPTY } }

        val maxCols = rectifiedMat.cols()
        val maxRows = rectifiedMat.rows()

        try {
            for (row in 0 until 19) {
                for (col in 0 until 19) {
                    val pt = geometryGrid[row][col]
                    val x = pt.x.toInt()
                    val y = pt.y.toInt()

                    val x1 = (x - patchRadius).coerceIn(0, maxCols)
                    val y1 = (y - patchRadius).coerceIn(0, maxRows)
                    val x2 = (x + patchRadius).coerceIn(0, maxCols)
                    val y2 = (y + patchRadius).coerceIn(0, maxRows)

                    val w = x2 - x1
                    val h = y2 - y1

                    if (w > 0 && h > 0 && x1 + w <= maxCols && y1 + h <= maxRows) {
                        val roi = Rect(x1, y1, w, h)
                        val colorPatchMat = rectifiedMat.submat(roi)

                        val resizedColor = Mat()
                        Imgproc.resize(colorPatchMat, resizedColor, Size(40.0, 40.0))

                        val predictedColor = runInference(resizedColor)
                        boardLayout[row][col] = predictedColor

                        colorPatchMat.release()
                        resizedColor.release()
                    }
                }
            }
            Log.d("CnnStoneDetector", "361箇所の碁石認識が完了しました。")
        } catch (e: Exception) {
            Log.e("CnnStoneDetector", "碁石の検出処理中にエラーが発生しました", e)
        }

        return boardLayout.map { it.toList() }
    }

    private fun runInference(colorMat: Mat): StoneColor {
        val rgbMat = Mat()
        return try {
            Imgproc.cvtColor(colorMat, rgbMat, Imgproc.COLOR_BGR2RGB)

            // カラー画像バッファ (1, 40, 40, 3) - 学習時の / 255.0f の正規化に合わせる
            val colorBuffer = ByteBuffer.allocateDirect(1 * 40 * 40 * 3 * 4).order(ByteOrder.nativeOrder())
            val colorBmp = Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbMat, colorBmp)

            val intValues = IntArray(40 * 40)
            colorBmp.getPixels(intValues, 0, colorBmp.width, 0, 0, colorBmp.width, colorBmp.height)
            for (pixel in intValues) {
                colorBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
                colorBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
                colorBuffer.putFloat((pixel and 0xFF) / 255.0f)
            }
            colorBmp.recycle()

            // 単一入力・単一出力の推論実行
            val outputVal = Array(1) { FloatArray(3) }
            interpreter.run(colorBuffer, outputVal)

            val scores = outputVal[0]
            var maxIndex = 0
            var maxVal = scores[0]
            for (i in 1 until scores.size) {
                if (scores[i] > maxVal) {
                    maxVal = scores[i]
                    maxIndex = i
                }
            }

            when (maxIndex) {
                1 -> StoneColor.BLACK
                2 -> StoneColor.WHITE
                else -> StoneColor.EMPTY
            }
        } catch (e: Exception) {
            Log.e("CnnStoneDetector", "推論実行エラー", e)
            StoneColor.EMPTY
        } finally {
            rgbMat.release()
        }
    }
}
