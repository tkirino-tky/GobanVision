package com.github.tkirino.gobanreader.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object SgfActionHelper {

    /**
     * SGFファイルをメールで送信する
     */
    fun sendSgfByEmail(context: Context, sgfFile: File, emailAddress: String) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            sgfFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(emailAddress))
            putExtra(Intent.EXTRA_SUBJECT, "【GobanReader】対局棋譜(SGF)")
            putExtra(Intent.EXTRA_TEXT, "GobanReaderで出力したSGFファイルを添付します。")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(Intent.createChooser(intent, "メールアプリを選択"))
        } catch (e: Exception) {
            Toast.makeText(context, "メールアプリの起動に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * SGFファイルを外部の棋譜閲覧アプリ（ビューア）で開く
     */
    fun openSgfWithViewer(context: Context, sgfFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            sgfFile
        )

        // 1. まず標準的な SGF MIME タイプで試行
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/x-go-sgf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // 2. MIMEタイプに対応していないアプリ（汎用テキスト等）向けに text/plain で再試行
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "text/plain")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (ex: ActivityNotFoundException) {
                // 3. 対応するアプリが端末内に存在しない場合
                Toast.makeText(context, "棋譜閲覧アプリがありません", Toast.LENGTH_LONG).show()
            }
        }
    }
}
