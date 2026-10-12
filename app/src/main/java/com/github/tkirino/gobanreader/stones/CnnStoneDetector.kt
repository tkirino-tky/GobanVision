package com.github.tkirino.gobanreader.stones

import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import com.github.tkirino.gobanreader.config.DebugConfig
import com.github.tkirino.gobanreader.model.StoneColor
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class DetectionResult(
    val color: StoneColor,
    val isCertain: Boolean
)

class CnnStoneDetector(private val interpreter: Interpreter) {

    // 60x60px画像に対応するため、半径(half)を30pxに設定（30 + 30 = 60px）
    private val patchSize = 60
    private val patchRadius = 30

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

                    val x1 = x - patchRadius
                    val y1 = y - patchRadius

                    val safeX1 = x1.coerceIn(0, maxOf(0, maxCols - patchSize))
                    val safeY1 = y1.coerceIn(0, maxOf(0, maxRows - patchSize))
                    val roi = Rect(safeX1, safeY1, patchSize, patchSize)

                    if (roi.width == patchSize && roi.height == patchSize) {
                        val colorPatchMat = Mat(rectifiedMat, roi)

                        // 60x60pxパッチに対して推論を実行
                        probGrid[row][col] = runInferenceProbs(colorPatchMat)

                        colorPatchMat.release()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CnnStoneDetector", "確率予測中にエラーが発生しました", e)
        }
        return probGrid
    }

    // タイポ互換用
    fun predictProabilities(
        rectifiedMat: Mat,
        geometryGrid: Array<Array<Point>>
    ): Array<Array<FloatArray>> = predictProbabilities(rectifiedMat, geometryGrid)

    /**
     * 確率分布から最終的な盤面状態を決定する関数
     * Post Processing（光飛び補正・閾値変更）を行わず、CNNの確率（最大値）をそのまま採用します。
     */
    fun aggregateInferences(
        frameProbsList: List<Array<Array<FloatArray>>>,
        sessionId: String? = null
    ): Pair<List<List<StoneColor>>, List<List<Boolean>>> {
        val boardLayout = MutableList(19) { MutableList(19) { StoneColor.EMPTY } }
        val certaintyLayout = MutableList(19) { MutableList(19) { true } }

        if (frameProbsList.isEmpty()) {
            return Pair(boardLayout.map { it.toList() }, certaintyLayout.map { it.toList() })
        }

        val probsMap = frameProbsList[0]

        for (row in 0 until 19) {
            for (col in 0 until 19) {
                val probs = probsMap[row][col]

                var maxIndex = 0
                var maxVal = probs[0]
                for (i in 1 until 3) {
                    if (probs[i] > maxVal) {
                        maxVal = probs[i]
                        maxIndex = i
                    }
                }

                val predictedColor = when (maxIndex) {
                    INDEX_BLACK -> StoneColor.BLACK
                    INDEX_WHITE -> StoneColor.WHITE
                    else -> StoneColor.EMPTY
                }

                boardLayout[row][col] = predictedColor
                certaintyLayout[row][col] = true
            }
        }

        // CSVファイル出力の実行（DebugConfig.EXPORT_INFERENCE_SCORES_CSV が true の場合に保存）
        val boardArray = Array(19) { r -> Array(19) { c -> boardLayout[r][c] } }
        val certaintyArray = Array(19) { r -> Array(19) { c -> certaintyLayout[r][c] } }
        exportInferenceScoresToCsv(
            boardSize = 19,
            maxConfidencePerClassMap = probsMap,
            finalColors = boardArray,
            certaintyMap = certaintyArray,
            sessionId = sessionId
        )

        return Pair(boardLayout.map { it.toList() }, certaintyLayout.map { it.toList() })
    }

    /**
     * 単一パッチ（60x60px）に対するTFLite推論を実行（CLAHE前処理適用）
     */
    private fun runInferenceProbs(colorMat: Mat): FloatArray {
        val rgbMat = Mat()
        val ycrCbMat = Mat()
        val channels = ArrayList<Mat>()

        return try {
            Imgproc.cvtColor(colorMat, ycrCbMat, Imgproc.COLOR_BGR2YCrCb)
            Core.split(ycrCbMat, channels)

            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(channels[0], channels[0])

            Core.merge(channels, ycrCbMat)
            Imgproc.cvtColor(ycrCbMat, rgbMat, Imgproc.COLOR_YCrCb2RGB)

            val colorBuffer = ByteBuffer.allocateDirect(1 * 60 * 60 * 3 * 4).order(ByteOrder.nativeOrder())
            val colorBmp = Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbMat, colorBmp)

            val intValues = IntArray(60 * 60)
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
        geometryGrid: Array<Array<Point>>,
        sessionId: String? = null
    ): Pair<List<List<StoneColor>>, List<List<Boolean>>> {
        val singleProbs = predictProbabilities(rectifiedMat, geometryGrid)
        return aggregateInferences(listOf(singleProbs), sessionId)
    }

    /**
     * 全交点の推論結果・事後確率スコアを CSV ファイルに出力するデバッグ関数
     * （Download/StoneProb/ フォルダへ保存）
     */
    private fun exportInferenceScoresToCsv(
        boardSize: Int = 19,
        maxConfidencePerClassMap: Array<Array<FloatArray>>,
        finalColors: Array<Array<StoneColor>>,
        certaintyMap: Array<Array<Boolean>>,
        sessionId: String? = null
    ) {
        if (!DebugConfig.isEnabled || !DebugConfig.EXPORT_INFERENCE_SCORES_CSV) return

        try {
            val timeStamp = sessionId ?: "game_${System.currentTimeMillis()}"
            val fileName = "Goban_${timeStamp}_prob.csv"

            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val probDir = File(downloadDir, "StoneProb")
            if (!probDir.exists()) probDir.mkdirs()

            val file = File(probDir, fileName)

            file.bufferedWriter().use { writer ->
                writer.write("row,col,score_empty,score_black,score_white,final_color,is_certain\n")

                for (row in 0 until boardSize) {
                    for (col in 0 until boardSize) {
                        val scores = maxConfidencePerClassMap[row][col]
                        val emptyScore = scores[0]
                        val blackScore = scores[1]
                        val whiteScore = scores[2]
                        val finalColor = finalColors[row][col].name
                        val isCertain = certaintyMap[row][col]

                        writer.write("$row,$col,%.4f,%.4f,%.4f,$finalColor,$isCertain\n".format(
                            Locale.US, emptyScore, blackScore, whiteScore
                        ))
                    }
                }
            }
            Log.d("CnnStoneDetector", "Inference scores CSV exported to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("CnnStoneDetector", "Failed to export CSV: ${e.message}", e)
        }
    }
}
