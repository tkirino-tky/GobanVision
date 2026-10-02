package com.github.tkirino.gobanreader.export

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SgfExportDialog(
    initialEmail: String = "",
    onDismissRequest: () -> Unit,
    onConfirm: (email: String, openViewer: Boolean) -> Unit
) {
    var email by remember { mutableStateOf(initialEmail) }
    var openViewer by remember { mutableStateOf(true) } // デフォルトでビューア起動ON

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = "SGF出力・共有")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 上段: メール送信（任意）
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "メールで送る (任意)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("メールアドレス") },
                        placeholder = { Text("（空欄の場合は保存のみ）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Divider()

                // 下段: 棋譜ビューア起動
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = openViewer,
                        onCheckedChange = { openViewer = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "出力後に棋譜ビューアで開く",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(email.trim(), openViewer) }
            ) {
                Text("実行")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("キャンセル")
            }
        }
    )
}
