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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.github.tkirino.gobanreader.export.SgfActionHelper
import com.github.tkirino.gobanreader.export.SgfExportDialog
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

    // 現在選択中の手動修正モード（初期値は黒石）
    var editMode by remember { mutableStateOf(EditMode.BLACK) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. メッセージ表示
            val statusText = if (uiState.isLoading) {
                "画像を解析中..."
            } else {
                "解析完了（タップして石を修正できます）"
            }

            Text(
                text = statusText,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 2. 修正ツール
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

            Spacer(modifier = Modifier.height(4.dp))

            // 3. 回転ボタン（上部に配置）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { viewModel.rotateLeft() }) { Text("左90°回転") }
                Button(onClick = { viewModel.rotateRight() }) { Text("右90°回転") }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 4. 碁盤表示領域
            // DisplayScreen.kt 内の GoBoard 呼び出し箇所
            GoBoard(
                boardMatrix = uiState.boardLayout,
                certaintyMatrix = uiState.certaintyLayout, // ★これを追加
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

            // 5. 最下部のアクションボタン
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = {
                    emailInput = PreferencesManager.getSavedEmail(context)
                    showEmailDialog = true
                }) { Text("SGF出力 & 共有") } // ラベルを少し分かりやすく変更
                Button(onClick = onBackClick) { Text("戻る") }
            }
        }
    }

    // ★ 新しい SGF出力・共有ダイアログの呼び出し
    if (showEmailDialog) {
        SgfExportDialog(
            initialEmail = emailInput,
            onDismissRequest = { showEmailDialog = false },
            onConfirm = { email, openViewer ->
                showEmailDialog = false
                viewModel.exportSgf(context, uiState.gameRecord, email) { savedFile ->
                    Toast.makeText(context, "SGFファイルを保存しました", Toast.LENGTH_SHORT).show()

                    // 1. メールアドレスが入力されている場合はメール共有を起動
                    if (email.isNotEmpty()) {
                        SgfActionHelper.sendSgfByEmail(context, savedFile, email)
                    }

                    // 2. 「棋譜ビューアで開く」にチェックがある場合はビューア起動
                    if (openViewer) {
                        SgfActionHelper.openSgfWithViewer(context, savedFile)
                    }
                }
            }
        )
    }
}
