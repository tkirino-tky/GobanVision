package com.github.tkirino.gobanreader.export

import android.content.Context
import android.os.Environment
import com.github.tkirino.gobanreader.model.GameRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SgfWriter(private val context: Context) {

    /**
     * ユーザー入力の文字をエスケープ・クレンジングする拡張関数
     * SGFの構造を破壊する [ と ] を ( と ) に置換する
     */
    private fun String.sanitizeSgfText(): String {
        return this.replace("[", "(").replace("]", ")")
    }

    /**
     * GameRecordからSGF形式の文字列を生成する
     */
    fun generateSgfString(gameRecord: GameRecord): String {
        val sb = StringBuilder()

        // SGFヘッダーの生成
        sb.append("(;GM[1]FF[4]CA[UTF-8]")
        sb.append("AP[GobanReader:1.0]")
        sb.append("SZ[${gameRecord.boardSize}]")

        if (gameRecord.komi != 0f) sb.append("KM[${gameRecord.komi}]")
        if (gameRecord.handicap > 0) sb.append("HA[${gameRecord.handicap}]")

        // プレイヤー名はエスケープ処理を挟む
        if (gameRecord.blackPlayer.isNotEmpty()) sb.append("PB[${gameRecord.blackPlayer.sanitizeSgfText()}]")
        if (gameRecord.whitePlayer.isNotEmpty()) sb.append("PW[${gameRecord.whitePlayer.sanitizeSgfText()}]")
        if (gameRecord.gameResult.isNotEmpty()) sb.append("RE[${gameRecord.gameResult}]")

        // 次の手番を明示的に指定
        if (gameRecord.nextPlayer.isNotEmpty()) {
            sb.append("PL[${gameRecord.nextPlayer}]")
        }
        val currentDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        sb.append("DT[$currentDate]")

        // 初期配置（黒石）の書き出し
        if (gameRecord.initialBlackStones.isNotEmpty()) {
            sb.append("AB")
            for (stone in gameRecord.initialBlackStones) {
                val sgfCoord = convertToSgfCoordinate(stone.first, stone.second)
                sb.append("[$sgfCoord]")
            }
        }

        // 初期配置（白石）の書き出し
        if (gameRecord.initialWhiteStones.isNotEmpty()) {
            sb.append("AW")
            for (stone in gameRecord.initialWhiteStones) {
                val sgfCoord = convertToSgfCoordinate(stone.first, stone.second)
                sb.append("[$sgfCoord]")
            }
        }

        // 着手履歴の追加
        for (move in gameRecord.moveHistory) {
            val color = if (move.isBlack) "B" else "W"
            val sgfCoord = convertToSgfCoordinate(move.x, move.y)
            sb.append(";$color[$sgfCoord]")
        }

        sb.append(")")
        return sb.toString()
    }

    private fun convertToSgfCoordinate(x: Int, y: Int): String {
        val xChar = ('a'.code + x).toChar()
        val yChar = ('a'.code + y).toChar()
        return "$xChar$yChar"
    }

    /**
     * 指定されたファイル名で SGF ファイルを Download/SgfFiles フォルダに保存する
     */
    suspend fun saveSgfFile(sgfContent: String, fileName: String): Result<File> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "SgfFiles")
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }

                val targetFile = File(targetDir, fileName)

                FileOutputStream(targetFile).use { output ->
                    output.write(sgfContent.toByteArray(Charsets.UTF_8))
                }

                targetFile
            }
        }
    }

    /**
     * SGFファイルを Download/SgfFiles フォルダに自動命名（タイムスタンプ）で保存する
     */
    suspend fun saveSgfFileAutoNamed(sgfContent: String): Result<File> {
        val current = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val formattedDate = current.format(formatter)
        val fileName = "GobanReader-$formattedDate.sgf"

        return saveSgfFile(sgfContent, fileName)
    }
}
