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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

                var winningClass = maxIndex

                // ★【光飛び対策安全フィルタ：偽白石防止】
                // 白石(INDEX_WHITE)と判定されても確率が0.80未満の場合はノイズとみなしEMPTY（空白）へ変換
                if (winningClass == INDEX_WHITE && maxVal < confidenceThreshold) {
                    winningClass = INDEX_EMPTY
                }

                val predictedColor = when (winningClass) {
                    INDEX_BLACK -> StoneColor.BLACK
                    INDEX_WHITE -> StoneColor.WHITE
                    else -> StoneColor.EMPTY
                }

                // ★【光飛び対策安全フィルタ：黒石ハイライト補正】
                // 黒石と判定され、確率が 0.80 未満であっても、白石の確率が極めて低い（0.05未満）場合は
                // 表面テカリ（ハイライト）によるスコア下落とみなし確信度 true（？なし）とする
                val isCertain = when (winningClass) {
                    INDEX_EMPTY -> true
                    INDEX_BLACK -> {
                        val whiteScore = probs[INDEX_WHITE]
                        (maxVal >= confidenceThreshold) || (whiteScore < 0.05f)
                    }
                    else -> (maxVal >= confidenceThreshold)
                }

                boardLayout[row][col] = predictedColor
                certaintyLayout[row][col] = isCertain
            }
        }

        // ★ CSVファイル出力の実行（Download/DebugLogs/ へ出力）
        val boardArray = Array(19) { r -> Array(19) { c -> boardLayout[r][c] } }
        val certaintyArray = Array(19) { r -> Array(19) { c -> certaintyLayout[r][c] } }
        exportInferenceScoresToCsv(
            boardSize = 19,
            maxConfidencePerClassMap = probsMap,
            finalColors = boardArray,
            certaintyMap = certaintyArray
        )

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

    /**
     * 全交点の推論結果・事後確率スコアをCSVファイルに出力するデバッグ関数
     * （Context不要で Download/DebugLogs フォルダへ直接書き出し）
     */
    private fun exportInferenceScoresToCsv(
        boardSize: Int = 19,
        maxConfidencePerClassMap: Array<Array<FloatArray>>, // [row][col][0:EMPTY, 1:BLACK, 2:WHITE]
        finalColors: Array<Array<StoneColor>>,
        certaintyMap: Array<Array<Boolean>>
    ) {
        if (!DebugConfig.isEnabled || !DebugConfig.EXPORT_INFERENCE_SCORES_CSV) return

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "inference_scores_$timeStamp.csv"

            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val debugLogsDir = File(downloadDir, "DebugLogs")
            if (!debugLogsDir.exists()) debugLogsDir.mkdirs()

            val file = File(debugLogsDir, fileName)

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
