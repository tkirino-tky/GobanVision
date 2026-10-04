package com.github.tkirino.gobanreader.export

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 共有アクションの種類を定義する列挙型
 */
enum class SgfExportAction {
    OPEN_VIEWER, // 棋譜ビューアーで開く
    SEND_EMAIL,  // メールで送信する
    SAVE_AND_EXIT // 保存してアプリを終了
}

@Composable
fun SgfExportDialog(
    initialEmail: String = "",
    onDismissRequest: () -> Unit,
    onActionSelected: (action: SgfExportAction, email: String) -> Unit
) {
    var email by remember { mutableStateOf(initialEmail) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = "SGF出力・共有",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "SGFファイルを保存しました。\n次に実行する操作を選択してください。",
                    style = MaterialTheme.typography.bodyMedium
                )

                // メールアドレス入力欄（メール送信時に使用）
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("送信先メールアドレス (任意)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // アクションボタン群（明確な縦並び配置）
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // ① 棋譜ビューアーで開く（メインアクション）
                    Button(
                        onClick = { onActionSelected(SgfExportAction.OPEN_VIEWER, email.trim()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "棋譜ビューアーで開く",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // ② メールで送信する
                    OutlinedButton(
                        onClick = { onActionSelected(SgfExportAction.SEND_EMAIL, email.trim()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "メールで送信する",
                            fontSize = 15.sp
                        )
                    }

                    // ③ 保存して終了（メールもビューアーも不要な場合）
                    OutlinedButton(
                        onClick = { onActionSelected(SgfExportAction.SAVE_AND_EXIT, email.trim()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "保存して終了",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {}, // 各ボタンが直接アクションを実行するため、標準のOK/キャンセルボタンは不使用
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("キャンセル")
            }
        }
    )
}
