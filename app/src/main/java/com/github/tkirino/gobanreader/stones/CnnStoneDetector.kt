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

data class DetectionResult(
    val color: StoneColor,
    val isCertain: Boolean
)

class CnnStoneDetector(private val interpreter: Interpreter) {

    private val patchRadius = 20
    // 確信度の閾値（80%未満は「確信なし（?）」とする）
    private val confidenceThreshold = 0.80f

    fun detectStones(
        rectifiedMat: Mat,
        geometryGrid: Array<Array<Point>>
    ): Pair<List<List<StoneColor>>, List<List<Boolean>>> {
        val boardLayout = MutableList(19) { MutableList(19) { StoneColor.EMPTY } }
        val certaintyLayout = MutableList(19) { MutableList(19) { true } }

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

                        val result = runInference(resizedColor)
                        boardLayout[row][col] = result.color
                        certaintyLayout[row][col] = result.isCertain

                        colorPatchMat.release()
                        resizedColor.release()
                    }
                }
            }
            Log.d("CnnStoneDetector", "361箇所の碁石認識が完了しました。")
        } catch (e: Exception) {
            Log.e("CnnStoneDetector", "碁石の検出処理中にエラーが発生しました", e)
        }

        return Pair(boardLayout.map { it.toList() }, certaintyLayout.map { it.toList() })
    }

    private fun runInference(colorMat: Mat): DetectionResult {
        val rgbMat = Mat()
        return try {
            Imgproc.cvtColor(colorMat, rgbMat, Imgproc.COLOR_BGR2RGB)

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

            val predictedColor = when (maxIndex) {
                1 -> StoneColor.BLACK
                2 -> StoneColor.WHITE
                else -> StoneColor.EMPTY
            }

            val isCertain = maxVal >= confidenceThreshold

            DetectionResult(color = predictedColor, isCertain = isCertain)
        } catch (e: Exception) {
            Log.e("CnnStoneDetector", "推論実行エラー", e)
            DetectionResult(color = StoneColor.EMPTY, isCertain = true)
        } finally {
            rgbMat.release()
        }
    }
}
