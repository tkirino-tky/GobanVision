package com.github.tkirino.gobanreader.vision

import android.graphics.Bitmap
import android.util.Log
import com.github.tkirino.gobanreader.MainViewModel
import com.github.tkirino.gobanreader.config.DebugConfig
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloCornerDetector(private val interpreter: Interpreter) {

    enum class QualityLevel {
        GREEN,  // 高品質
        YELLOW, // 要確認
        RED     // 要修正・検出数不足
    }

    data class DetectionResult(
        val corners: List<Point>,
        val found: Boolean,
        val peakValues: List<Float> = emptyList(),
        val qualityLevel: QualityLevel = QualityLevel.RED,
        val errorMessage: String? = null
    )

    fun detectCorners(fullSrc: Mat, guideRect: Rect): DetectionResult {
        Log.d("YoloCornerDetector", "detectCorners TFLite CALLED")

        try {
            val croppedBoard = Mat(fullSrc, guideRect)
            val resizedBoard = Mat()
            Imgproc.resize(croppedBoard, resizedBoard, Size(640.0, 640.0))
            croppedBoard.release()

            if (DebugConfig.EXPORT_CROPPED_RECT_IMAGE) {
                MainViewModel.exportCroppedRectImage(resizedBoard)
            }

            // 入力バッファの作成 (1, 640, 640, 3) Float32 [0.0 - 1.0]
            val inputBuffer = convertMatToByteBuffer(resizedBoard)
            resizedBoard.release()

            // 出力バッファの形状確認
            val outputShape = interpreter.getOutputTensor(0).shape()
            val outputSize = outputShape.fold(1) { acc, i -> acc * i }
            val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4).order(ByteOrder.nativeOrder())

            // 推論の実行
            interpreter.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            val outputFloatArray = FloatArray(outputSize)
            outputBuffer.asFloatBuffer().get(outputFloatArray)

            // 出力のパース処理
            val (corners, confidences) = parseYoloOutput(outputFloatArray, outputShape, guideRect)

            val found = corners.size == 4
            val quality = if (found) QualityLevel.GREEN else QualityLevel.RED

            return DetectionResult(
                corners = corners,
                found = found,
                peakValues = confidences,
                qualityLevel = quality,
                errorMessage = if (found) null else "コーナーが4つ検出されませんでした"
            )

        } catch (e: Throwable) {
            Log.e("YoloCornerDetector", "CRASH in detectCorners: ${e.message}", e)
            return DetectionResult(
                corners = emptyList(),
                found = false,
                qualityLevel = QualityLevel.RED,
                errorMessage = "致命的エラー: ${e.localizedMessage}"
            )
        }
    }

    private fun convertMatToByteBuffer(mat: Mat): ByteBuffer {
        val rgbMat = Mat()
        if (mat.channels() == 1) {
            Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_GRAY2RGB)
        } else {
            Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_BGR2RGB)
        }

        val bitmap = Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgbMat, bitmap)
        rgbMat.release()

        val byteBuffer = ByteBuffer.allocateDirect(1 * 640 * 640 * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(640 * 640)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixelValue in intValues) {
            // 元の正しいチャンネル順に戻す
            val r = ((pixelValue shr 16) and 0xFF) / 255.0f
            val g = ((pixelValue shr 8) and 0xFF) / 255.0f
            val b = (pixelValue and 0xFF) / 255.0f

            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }
        bitmap.recycle()
        return byteBuffer
    }

    private fun parseYoloOutput(
        data: FloatArray,
        shape: IntArray,
        guideRect: Rect
    ): Pair<List<Point>, List<Float>> {
        val channels = shape[1]
        val numElements = shape[2]

        val bestDetections = mutableMapOf<Int, Triple<Float, Float, Float>>()
        var debugCount = 0

        for (i in 0 until numElements) {
            var maxClassScore = 0f
            var bestClassId = -1

            for (c in 4 until channels) {
                val score = data[c * numElements + i]
                if (score > maxClassScore) {
                    maxClassScore = score
                    bestClassId = c - 4
                }
            }

            if (maxClassScore > 0.2f && debugCount < 5) {
                val xc_raw = data[0 * numElements + i]
                val yc_raw = data[1 * numElements + i]
                Log.d("YoloDebug", "Raw Detection [$i]: class=$bestClassId, score=$maxClassScore, xc=$xc_raw, yc=$yc_raw")
                debugCount++
            }

            // ★ 閾値を 0.5f から 0.35f に引き下げて、環境変化によるスコア低下を救う
            if (maxClassScore > 0.35f && bestClassId != -1) {
                val xc = data[0 * numElements + i]
                val yc = data[1 * numElements + i]
                if (!bestDetections.containsKey(bestClassId) || bestDetections[bestClassId]!!.third < maxClassScore) {
                    bestDetections[bestClassId] = Triple(xc, yc, maxClassScore)
                }
            }
        }

        Log.d("YoloDebug", "Total valid classes detected: ${bestDetections.keys}")

        val rawPoints = mutableListOf<Point>()
        val confidences = mutableListOf<Float>()

        val scaleX = guideRect.width.toDouble() / 640.0
        val scaleY = guideRect.height.toDouble() / 640.0

        for ((classId, triple) in bestDetections) {
            val (xc, yc, conf) = triple
            val absX = guideRect.x + xc * scaleX
            val absY = guideRect.y + yc * scaleY
            rawPoints.add(Point(absX, absY))
            confidences.add(conf)
        }

        val paired = rawPoints.zip(confidences)
        val sortedPaired = sortCornersWithPeaks(paired)

        return Pair(sortedPaired.map { it.first }, sortedPaired.map { it.second })
    }

    private fun sortCornersWithPeaks(paired: List<Pair<Point, Float>>): List<Pair<Point, Float>> {
        if (paired.size != 4) return paired

        val corners = paired.map { it.first }
        val cx = corners.map { it.x }.average()
        val cy = corners.map { it.y }.average()

        val top_left_p = paired.minByOrNull { it.first.x + it.first.y } ?: paired[0]
        val bottom_right_p = paired.maxByOrNull { it.first.x + it.first.y } ?: paired[3]

        val remaining = paired.filter { it != top_left_p && it != bottom_right_p }
        if (remaining.size != 2) {
            return paired.sortedBy { Math.atan2(it.first.y - cy, it.first.x - cx) }
        }

        val p1 = remaining[0]
        val p2 = remaining[1]

        val top_right_p: Pair<Point, Float>
        val bottom_left_p: Pair<Point, Float>
        if (p1.first.x - p1.first.y > p2.first.x - p2.first.y) {
            top_right_p = p1
            bottom_left_p = p2
        } else {
            top_right_p = p2
            bottom_left_p = p1
        }

        return listOf(top_left_p, top_right_p, bottom_right_p, bottom_left_p)
    }
}
