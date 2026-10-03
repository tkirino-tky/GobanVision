package com.github.tkirino.gobanreader.stones

import android.graphics.Bitmap
import android.util.Log
import com.github.tkirino.gobanreader.model.StoneColor
import org.opencv.android.Utils
import org.opencv.core.Core
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
    private val confidenceThreshold = 0.80f

    companion object {
        private const val INDEX_EMPTY = 0
        private const val INDEX_BLACK = 1
        private const val INDEX_WHITE = 2
    }

    /**
     * 19x19全交点の確率分布 [19][19][3] を算出する関数
     */
    fun predictProbabilities(
        rectifiedMat: Mat,
        geometryGrid: Array<Array<Point>>
    ): Array<Array<FloatArray>> {
        val probGrid = Array(19) { Array(19) { FloatArray(3) } }
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

                        probGrid[row][col] = runInferenceProbs(resizedColor)

                        colorPatchMat.release()
                        resizedColor.release()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CnnStoneDetector", "確率予測中にエラーが発生しました", e)
        }
        return probGrid
    }

    // タイポ互換用（既存呼び出し保護）
    fun predictProabilities(
        rectifiedMat: Mat,
        geometryGrid: Array<Array<Point>>
    ): Array<Array<FloatArray>> = predictProbabilities(rectifiedMat, geometryGrid)

    /**
     * 確率分布から最終的な盤面状態（石の色・確信度）を決定する関数
     */
    fun aggregateInferences(
        frameProbsList: List<Array<Array<FloatArray>>>
    ): Pair<List<List<StoneColor>>, List<List<Boolean>>> {
        val boardLayout = MutableList(19) { MutableList(19) { StoneColor.EMPTY } }
        val certaintyLayout = MutableList(19) { MutableList(19) { true } }

        if (frameProbsList.isEmpty()) {
            return Pair(boardLayout.map { it.toList() }, certaintyLayout.map { it.toList() })
        }

        for (row in 0 until 19) {
            for (col in 0 until 19) {
                val probs = frameProbsList[0][row][col]

                var maxIndex = 0
                var maxVal = probs[0]
                for (i in 1 until 3) {
                    if (probs[i] > maxVal) {
                        maxVal = probs[i]
                        maxIndex = i
                    }
                }

                // ★ 光飛び対策安全フィルタ：
                // 白石(INDEX_WHITE)と判定されても確率が0.80未満の場合はノイズとみなしEMPTY（空白）へ変換
                var winningClass = maxIndex
                if (winningClass == INDEX_WHITE && maxVal < confidenceThreshold) {
                    winningClass = INDEX_EMPTY
                }

                val predictedColor = when (winningClass) {
                    INDEX_BLACK -> StoneColor.BLACK
                    INDEX_WHITE -> StoneColor.WHITE
                    else -> StoneColor.EMPTY
                }

                val isCertain = if (winningClass == INDEX_EMPTY) true else (maxVal >= confidenceThreshold)

                boardLayout[row][col] = predictedColor
                certaintyLayout[row][col] = isCertain
            }
        }

        return Pair(boardLayout.map { it.toList() }, certaintyLayout.map { it.toList() })
    }

    /**
     * 単一パッチに対するTFLite推論を実行（CLAHE前処理適用）
     */
    private fun runInferenceProbs(colorMat: Mat): FloatArray {
        val rgbMat = Mat()
        val ycrCbMat = Mat()
        val channels = ArrayList<Mat>()

        return try {
            // YCrCb色空間で輝度(Y)チャンネルのみCLAHE補正
            Imgproc.cvtColor(colorMat, ycrCbMat, Imgproc.COLOR_BGR2YCrCb)
            Core.split(ycrCbMat, channels)

            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(channels[0], channels[0])

            Core.merge(channels, ycrCbMat)
            Imgproc.cvtColor(ycrCbMat, rgbMat, Imgproc.COLOR_YCrCb2RGB)

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

            outputVal[0]

        } catch (e: Exception) {
            Log.e("CnnStoneDetector", "推論エラー", e)
            floatArrayOf(1.0f, 0.0f, 0.0f)
        } finally {
            rgbMat.release()
            ycrCbMat.release()
            channels.forEach { it.release() }
        }
    }

    /**
     * 単一フレーム解析エントリーポイント
     */
    fun detectStones(
        rectifiedMat: Mat,
        geometryGrid: Array<Array<Point>>
    ): Pair<List<List<StoneColor>>, List<List<Boolean>>> {
        val singleProbs = predictProbabilities(rectifiedMat, geometryGrid)
        return aggregateInferences(listOf(singleProbs))
    }
}
