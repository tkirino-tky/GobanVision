// GobanUtils.kt
package com.github.tkirino.gobanreader.utility

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream

object GobanUtils {

    /**
     * デバッグ用：Matをアプリ専用ディレクトリにPNGとして保存
     */
    fun saveMatDebug(context: Context, mat: Mat, fileName: String) {
        if (mat.empty()) {
            Log.e("GobanUtils", "Mat is empty. Cannot save.")
            return
        }

        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)

        val file = File(context.getExternalFilesDir(null), fileName)
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d("GobanUtils", "Saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("GobanUtils", "Failed to save debug image", e)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 今後の拡張：座標変換ユーティリティなど
     * (例: UIのRectをOpenCVのRectに変換するなど)
     */
}
