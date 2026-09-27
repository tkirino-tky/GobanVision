package com.github.tkirino.gobanreader.vision

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * YOLOv8/v11 囲碁盤4隅コーナー検出器 (復元版)
 */
class YoloCornerDetector(private val interpreter: Interpreter) {

    private val inputSize = 640
    private val confThreshold = 0.25f
    private val mergeDistance = 30.0f

    data class DetectionCandidate(
        val cx: Float,
        val cy: Float,
        val conf: Float
    )

    data class DetectionResult(
        val corners: List<Point>
    )

    fun detectCorners(fullSrcMat: Mat, guideRect: Rect): DetectionResult? {
        if (fullSrcMat.empty()) return null

        // 1. OpenCV Mat -> Bitmap (640x640 ARGB_8888) 変換
        val rgbMat = Mat()
        Imgproc.cvtColor(fullSrcMat, rgbMat, Imgproc.COLOR_BGR2RGB)

        val resizedMat = Mat()
        Imgproc.resize(rgbMat, resizedMat, Size(inputSize.toDouble(), inputSize.toDouble()))
        rgbMat.release()

        val bitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(resizedMat, bitmap)
        resizedMat.release()

        // 2. Bitmap -> Float ByteBuffer (1, 640, 640, 3) 変換
        val inputBuffer = convertBitmapToByteBuffer(bitmap)

        // 3. TFLite 推論実行 [1, 8, 8400]
        val outputArray = Array(1) { Array(8) { FloatArray(8400) } }

        try {
            interpreter.run(inputBuffer, outputArray)
        } catch (e: Exception) {
            Log.e("YoloCornerDetector", "TFLite 推論実行エラー", e)
            return null
        }

        // 4. 生出力の解析
        val predictions = outputArray[0]
        val rawCandidates = mutableListOf<DetectionCandidate>()

        for (i in 0 until 8400) {
            var maxClassConf = 0.0f
            for (c in 4 until 8) {
                val score = predictions[c][i]
                if (score > maxClassConf) {
                    maxClassConf = score
                }
            }

            if (maxClassConf >= confThreshold) {
                val cx = predictions[0][i]
                val cy = predictions[1][i]
                rawCandidates.add(DetectionCandidate(cx, cy, maxClassConf))
            }
        }

        if (rawCandidates.isEmpty()) return null

        // 5. 重複除去 (NMS)
        rawCandidates.sortByDescending { it.conf }
        val mergedPoints = mutableListOf<DetectionCandidate>()

        for (cand in rawCandidates) {
            var isDuplicate = false
            for (mPt in mergedPoints) {
                val dist = sqrt((cand.cx - mPt.cx).pow(2) + (cand.cy - mPt.cy).pow(2))
                if (dist < mergeDistance) {
                    isDuplicate = true
                    break
                }
            }
            if (!isDuplicate) {
                mergedPoints.add(cand)
            }
        }

        if (mergedPoints.size < 4) return null
        val top4Points = mergedPoints.take(4)

        // 6. スケール復元
        val scaleX = fullSrcMat.cols().toFloat() / inputSize.toFloat()
        val scaleY = fullSrcMat.rows().toFloat() / inputSize.toFloat()

        val pts = top4Points.map {
            Point((it.cx * scaleX).toDouble(), (it.cy * scaleY).toDouble())
        }

        // 7. 幾何判定による 4隅決定 (TL, TR, BR, BL)
        val sumPts = pts.map { it.x + it.y }
        val diffPts = pts.map { it.x - it.y }

        val tlIdx = sumPts.indices.minByOrNull { sumPts[it] } ?: 0
        val brIdx = sumPts.indices.maxByOrNull { sumPts[it] } ?: 0
        val trIdx = diffPts.indices.maxByOrNull { diffPts[it] } ?: 0
        val blIdx = diffPts.indices.minByOrNull { diffPts[it] } ?: 0

        return DetectionResult(
            corners = listOf(pts[tlIdx], pts[trIdx], pts[brIdx], pts[blIdx])
        )
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val valPixel = intValues[pixel++]
                byteBuffer.putFloat(((valPixel shr 16) and 0xFF) / 255.0f) // R
                byteBuffer.putFloat(((valPixel shr 8) and 0xFF) / 255.0f)  // G
                byteBuffer.putFloat((valPixel and 0xFF) / 255.0f)         // B
            }
        }
        return byteBuffer
    }
}
