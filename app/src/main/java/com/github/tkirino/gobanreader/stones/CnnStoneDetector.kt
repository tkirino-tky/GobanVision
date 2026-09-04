package com.github.tkirino.gobanreader.stones

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.github.tkirino.gobanreader.model.StoneColor
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream

class CnnStoneDetector(private val context: Context) {

    private var torchModule: Module? = null
    private val patchRadius = 20

    init {
        try {
            // Assetsから直接ロード（SIGBUSクラッシュを避けるための標準的な方法）
            torchModule = LiteModuleLoader.load(assetFilePath(context, "goban_model.ptl"))
            Log.d("CnnStoneDetector", "石検出用モデルの読み込みに成功しました")
        } catch (e: Exception) {
            Log.e("CnnStoneDetector", "石検出用モデルの読み込みに失敗しました", e)
        }
    }

    private fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        // 常に最新をコピーし直し、SIGBUS（配置エラー）を確実に防ぐ
        context.assets.open(assetName).use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file.absolutePath
    }

    fun detectStones(
        rectifiedMat: Mat,
        edgeMat: Mat,
        geometryGrid: Array<Array<Point>>
    ): List<List<StoneColor>> {
        val boardLayout = MutableList(19) { MutableList(19) { StoneColor.EMPTY } }

        val module = torchModule
        if (module == null) {
            Log.e("CnnStoneDetector", "PyTorchモジュールが初期化されていません")
            return boardLayout.map { it.toList() }
        }

        val maxCols = rectifiedMat.cols()
        val maxRows = rectifiedMat.rows()
        val edgeCols = edgeMat.cols()
        val edgeRows = edgeMat.rows()

        Log.d("CnnDebug", "Dimensions -> rectified: ${maxCols}x${maxRows}, edge: ${edgeCols}x${edgeRows}")

        var skippedCount = 0
        var executedCount = 0

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

                    if (w > 0 && h > 0 && x1 + w <= maxCols && y1 + h <= maxRows &&
                        x1 + w <= edgeCols && y1 + h <= edgeRows) {

                        executedCount++
                        val roi = Rect(x1, y1, w, h)

                        val colorPatchMat = rectifiedMat.submat(roi)
                        val edgePatchMat = edgeMat.submat(roi)

                        val resizedColor = Mat()
                        val resizedEdge = Mat()
                        Imgproc.resize(colorPatchMat, resizedColor, Size(40.0, 40.0))
                        Imgproc.resize(edgePatchMat, resizedEdge, Size(40.0, 40.0))

                        val predictedColor = runInference(module, resizedColor, resizedEdge)
                        boardLayout[row][col] = predictedColor

                        colorPatchMat.release()
                        edgePatchMat.release()
                        resizedColor.release()
                        resizedEdge.release()
                    } else {
                        skippedCount++
                    }
                }
            }
            Log.d("CnnDebug", "Loop finished. Executed: $executedCount, Skipped: $skippedCount")
            Log.d("CnnStoneDetector", "361箇所の碁石認識が完了しました。")
        } catch (e: Exception) {
            Log.e("CnnStoneDetector", "碁石の検出処理中にエラーが発生しました", e)
        }

        return boardLayout.map { it.toList() }
    }

    private fun runInference(module: Module, colorMat: Mat, edgeMat: Mat): StoneColor {
        val rgbMat = Mat()
        try {
            // OpenCVのデフォルトである BGR から、PyTorchが前提とする RGB へ変換
            Imgproc.cvtColor(colorMat, rgbMat, Imgproc.COLOR_BGR2RGB)

            // 1. カラー画像 (3ch, 0.0〜1.0) のテンソル化
            val colorBmp = Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbMat, colorBmp)
            val colorTensor = TensorImageUtils.bitmapToFloat32Tensor(
                colorBmp,
                floatArrayOf(0.0f, 0.0f, 0.0f),
                floatArrayOf(1.0f, 1.0f, 1.0f)
            )

            // 2. エッジ画像 (1ch, 0.0〜1.0) のテンソル化
            val floatArray = FloatArray(40 * 40)
            val buff = ByteArray(40 * 40)
            edgeMat.get(0, 0, buff)
            for (i in 0 until 40 * 40) {
                floatArray[i] = (buff[i].toInt() and 0xFF) / 255.0f
            }
            val edgeTensor = Tensor.fromBlob(
                floatArray,
                longArrayOf(1, 1, 40, 40)
            )

            // 3. 推論の実行
            val outputTensor = module.forward(IValue.from(colorTensor), IValue.from(edgeTensor)).toTensor()
            val scores = outputTensor.dataAsFloatArray

            var maxIndex = 0
            var maxVal = scores[0]
            for (i in 1 until scores.size) {
                if (scores[i] > maxVal) {
                    maxVal = scores[i]
                    maxIndex = i
                }
            }

            return when (maxIndex) {
                1 -> StoneColor.BLACK
                2 -> StoneColor.WHITE
                else -> StoneColor.EMPTY
            }
        } catch (e: Exception) {
            Log.e("CnnStoneDetector", "推論実行エラー", e)
            return StoneColor.EMPTY
        } finally {
            rgbMat.release()
        }
    }
}
