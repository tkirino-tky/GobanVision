package com.github.tkirino.gobanreader.utility

import android.content.Context
import android.util.Log
import com.github.tkirino.gobanreader.model.GameRecord
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SgfWriter(private val context: Context) {

    companion object {
        private const val TAG = "SgfWriter"
    }

    /**
     * GameRecord から標準的な SGF 形式の文字列を生成する
     */
    fun generateSgfString(gameRecord: GameRecord): StringBuilder {
        val sb = StringBuilder()
        sb.append("(;\n")
        sb.append("FF[4]C[GobanReader Auto-generated SGF]GM[1]SZ[${gameRecord.boardSize}]\n")

        // 日付やプレイヤー情報など（必要に応じて）
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        sb.append("DT[${dateFormat.format(Date())}]\n")

        // 初期配置の黒石
        if (gameRecord.initialBlackStones.isNotEmpty()) {
            sb.append("AB")
            for (stone in gameRecord.initialBlackStones) {
                sb.append(coordinatesToSgf(stone.first, stone.second))
            }
            sb.append("\n")
        }

        // 初期配置の白石
        if (gameRecord.initialWhiteStones.isNotEmpty()) {
            sb.append("AW")
            for (stone in gameRecord.initialWhiteStones) {
                sb.append(coordinatesToSgf(stone.first, stone.second))
            }
            sb.append("\n")
        }

        sb.append(")")
        return sb
    }

    /**
     * 碁盤の座標 (x, y) を SGF 形式のアルファベット座標（例: "ab", "jj" など）に変換する
     */
    private fun coordinatesToSgf(x: Int, y: Int): String {
        // SGFの座標は a=0, b=1, c=2 ...
        val colChar = ('a' + x)
        val rowChar = ('a' + y)
        return "[$colChar$rowChar]"
    }

    /**
     * SGF文字列を自動命名でキャッシュディレクトリに保存する
     */
    fun saveSgfFileAutoNamed(sgfContent: StringBuilder): Result<File> {
        return try {
            // FileProviderがアクセスできる cacheDir を使用
            val cacheDir = context.cacheDir
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val fileName = "Goban_${dateFormat.format(Date())}.sgf"
            val file = File(cacheDir, fileName)

            file.writeText(sgfContent.toString(), Charsets.UTF_8)
            Log.d(TAG, "SGFファイル保存成功: ${file.absolutePath}")
            Result.success(file)
        } catch (e: IOException) {
            Log.e(TAG, "SGFファイル保存失敗", e)
            Result.failure(e)
        }
    }
}
