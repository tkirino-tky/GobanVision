package com.github.tkirino.gobanreader.display

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tkirino.gobanreader.MainViewModel
import com.github.tkirino.gobanreader.model.StoneColor
import com.github.tkirino.gobanreader.utility.PreferencesManager

// 修正モード用の列挙型
enum class EditMode {
    BLACK, WHITE, EMPTY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(viewModel.toastMessage) {
        viewModel.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.toastMessage = null
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showEmailDialog by remember { mutableStateOf(false) }
    var emailInput by remember { mutableStateOf("") }

    // 【追加】現在選択中の手動修正モード（初期値は黒石）
    var editMode by remember { mutableStateOf(EditMode.BLACK) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Goban Reader - 解析結果表示") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .navigationBarsPadding()
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val statusText = if (uiState.isLoading) {
                "画像を解析中..."
            } else {
                "解析完了（タップして石を修正できます）"
            }

            Text(
                text = statusText,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().height(30.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 【追加】手動修正用のモード切替ボタン群
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("修正ツール:", fontSize = 13.sp)
                FilterChip(
                    selected = editMode == EditMode.BLACK,
                    onClick = { editMode = EditMode.BLACK },
                    label = { Text("● 黒石") }
                )
                FilterChip(
                    selected = editMode == EditMode.WHITE,
                    onClick = { editMode = EditMode.WHITE },
                    label = { Text("○ 白石") }
                )
                FilterChip(
                    selected = editMode == EditMode.EMPTY,
                    onClick = { editMode = EditMode.EMPTY },
                    label = { Text("× 消去") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // GoBoard にタップ時のコールバック（viewModel.updateStone）を渡す
            GoBoard(
                boardMatrix = uiState.boardLayout,
                onIntersectionClick = { row, col ->
                    val colorToSet = when (editMode) {
                        EditMode.BLACK -> StoneColor.BLACK
                        EditMode.WHITE -> StoneColor.WHITE
                        EditMode.EMPTY -> StoneColor.EMPTY
                    }
                    viewModel.updateStone(row, col, colorToSet)
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { viewModel.rotateLeft() }) { Text("左90°回転") }
                Button(onClick = { viewModel.rotateRight() }) { Text("右90°回転") }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {
                    emailInput = PreferencesManager.getSavedEmail(context)
                    showEmailDialog = true
                }) { Text("SGF出力 & メール") }
                Button(onClick = onBackClick) { Text("戻る") }
            }
        }
    }

    if (showEmailDialog) {
        AlertDialog(
            onDismissRequest = { showEmailDialog = false },
            title = { Text("SGF出力とメール送信") },
            text = {
                Column {
                    Text("送信先メールアドレスを入力してください。\n(空欄の場合は端末への保存のみ行います)")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("メールアドレス") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEmailDialog = false
                        // ViewModelに保存を依頼し、保存されたファイルを受け取ってUI側のActivityからメールを確実に起動する
                        viewModel.exportSgf(context, uiState.gameRecord, emailInput) { savedFile ->
                            try {
                                val authority = "${context.packageName}.fileprovider"
                                val uri: android.net.Uri = androidx.core.content.FileProvider.getUriForFile(context, authority, savedFile)

                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(emailInput))
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "GobanReader: ${savedFile.name}")
                                    putExtra(android.content.Intent.EXTRA_TEXT, "碁盤の解析結果（SGFファイル）を添付します。")
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    clipData = android.content.ClipData.newUri(context.contentResolver, "SGF File", uri)
                                }

                                val chooser = android.content.Intent.createChooser(intent, "メールアプリを選択")
                                context.startActivity(chooser)
                            } catch (e: Exception) {
                                Log.e("GobanEmail", "メール送信インテントの起動に失敗しました", e)
                                Toast.makeText(context, "メール起動エラー: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("実行")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmailDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}
