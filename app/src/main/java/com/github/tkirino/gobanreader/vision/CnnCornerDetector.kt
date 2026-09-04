package com.github.tkirino.gobanreader.vision

import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import com.github.tkirino.gobanreader.MainViewModel
import com.github.tkirino.gobanreader.config.DebugConfig

class CnnCornerDetector(private val torchModule: Module) {

    // Qualityシグナルの定義用
    enum class QualityLevel {
        GREEN,  // 高品質（自動確定ライン：例 0.75以上）
        YELLOW, // 要確認（少し疑わしいライン：例 0.5 〜 0.75）
        RED     // 要修正（低品質・または検出数不足：例 0.5未満）
    }

    data class DetectionResult(
        val corners: List<Point>,
        val found: Boolean,
        val peakValues: List<Float> = emptyList(), // 各コーナーのピーク値
        val qualityLevel: QualityLevel = QualityLevel.RED, // 信号（緑・黄色・赤）
        val errorMessage: String? = null
    )

    fun detectCorners(fullSrc: Mat, guideRect: Rect): DetectionResult {
        try {
            val croppedBoard = Mat(fullSrc, guideRect)

            // 【追加・修正】原画保存フラグが有効な場合のみ呼び出す
            if (DebugConfig.EXPORT_ORIGINAL_BOARD_FOR_AUG) {
                MainViewModel.saveCroppedBoardToDownload(croppedBoard)
            }

            val edgeMat = generateEdgeImage(croppedBoard)
            croppedBoard.release()

            val resizedBoard = Mat()
            Imgproc.resize(edgeMat, resizedBoard, Size(256.0, 256.0))
            edgeMat.release()

            // 【修正】エッジ画像保存フラグが有効な場合のみ呼び出す
            if (DebugConfig.EXPORT_CROPPED_RECT_IMAGE) {
                MainViewModel.exportCroppedRectImage(resizedBoard)
            }

            val inputTensor = tensorImageFromMat(resizedBoard)
            resizedBoard.release()

            val outputTuple = torchModule.forward(IValue.from(inputTensor))
            val outputTensor = outputTuple.toTensor()
            val shape = outputTensor.shape()
            val data = outputTensor.dataAsFloatArray

            // ピーク値も一緒に抽出するメソッドを呼び出し
            val (corners, peakValues) = extractCornersAndPeaksFromHeatmap(data, shape, guideRect)

            if (corners.size == 4) {
                // 最低のピーク値を基準にQualityLevel（緑・黄色・赤）を判定
                val minPeak = peakValues.minOrNull() ?: 0f
                val quality = when {
                    minPeak >= 0.75f -> QualityLevel.GREEN
                    minPeak >= 0.50f -> QualityLevel.YELLOW
                    else -> QualityLevel.RED
                }

                return DetectionResult(
                    corners = corners,
                    found = true,
                    peakValues = peakValues,
                    qualityLevel = quality
                )
            } else {
                return DetectionResult(
                    corners = emptyList(),
                    found = false,
                    qualityLevel = QualityLevel.RED,
                    errorMessage = "コーナーの検出数不足です"
                )
            }
        } catch (e: Exception) {
            Log.e("CnnCornerDetector", "コーナー検出中にエラーが発生しました", e)
            return DetectionResult(
                corners = emptyList(),
                found = false,
                qualityLevel = QualityLevel.RED,
                errorMessage = e.localizedMessage
            )
        }
    }

    /**
     * ヒートマップからコーナー座標と、その時の最大ピーク値（信頼度）を同時に抽出する
     */
    private fun extractCornersAndPeaksFromHeatmap(
        data: FloatArray,
        shape: LongArray,
        guideRect: Rect
    ): Pair<List<Point>, List<Float>> {
        val channels = shape[1].toInt()
        val h = shape[2].toInt()
        val w = shape[3].toInt()
        val corners = mutableListOf<Point>()
        val peaks = mutableListOf<Float>()

        for (c in 0 until channels) {
            var maxVal = -Float.MAX_VALUE
            var maxRow = 0
            var maxCol = 0

            val channelOffset = c * h * w
            for (i in 0 until h) {
                for (j in 0 until w) {
                    val idx = channelOffset + i * w + j
                    val v = data[idx]
                    if (v > maxVal) {
                        maxVal = v
                        maxRow = i
                        maxCol = j
                    }
                }
            }

            peaks.add(maxVal)

            val scaleX = guideRect.width.toDouble() / w.toDouble()
            val scaleY = guideRect.height.toDouble() / h.toDouble()

            val absX = guideRect.x + maxCol * scaleX
            val absY = guideRect.y + maxRow * scaleY
            corners.add(Point(absX, absY))
        }

        // ソートに伴いピーク値の並びも対応させる必要があるため、
        // 座標とピークをペアにしてソートします
        val paired = corners.zip(peaks)
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

    private fun tensorImageFromMat(mat: Mat): Tensor {
        val resized = Mat()
        if (mat.cols() != 256 || mat.rows() != 256) {
            Imgproc.resize(mat, resized, Size(256.0, 256.0))
        } else {
            mat.copyTo(resized)
        }

        val gray = Mat()
        if (resized.channels() > 1) {
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            resized.copyTo(gray)
        }
        resized.release()

        val width = 256
        val height = 256
        val floatArray = FloatArray(1 * 1 * height * width)

        for (i in 0 until height) {
            for (j in 0 until width) {
                val pixel = gray.get(i, j)
                val value = if (pixel != null && pixel.isNotEmpty()) pixel[0].toFloat() / 255.0f else 0f
                floatArray[i * width + j] = value
            }
        }
        gray.release()

        return Tensor.fromBlob(floatArray, longArrayOf(1, 1, height.toLong(), width.toLong()))
    }

    fun generateEdgeImage(src: Mat): Mat {
        val gray = Mat()
        if (src.channels() == 1) {
            src.copyTo(gray)
        } else {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        }

        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        gray.release()

        val edged = Mat()
        Imgproc.Canny(blurred, edged, 50.0, 150.0)
        blurred.release()

        Imgproc.dilate(edged, edged, Mat(), Point(-1.0, -1.0), 2)

        return edged
    }
}
