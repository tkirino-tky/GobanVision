package com.github.tkirino.gobanreader.display

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tkirino.gobanreader.MainViewModel
import com.github.tkirino.gobanreader.export.SgfExportAction
import com.github.tkirino.gobanreader.export.SgfExportDialog
import com.github.tkirino.gobanreader.model.StoneColor
import com.github.tkirino.gobanreader.utility.PreferencesManager

@Composable
fun DisplayScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit // ★ 追加: 設定画面への遷移イベント
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showExportDialog by remember { mutableStateOf(false) }

    // 編集ツール状態（0: 黒石, 1: 白石, 2: 消去）
    var selectedTool by remember { mutableIntStateOf(0) }

    // 端末に保存されているデフォルトのメールアドレスを取得
    val initialEmail = remember { PreferencesManager.getSavedEmail(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "解析完了（タップして石を修正できます）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // 修正ツール選択ボタン群
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("修正ツール:", style = MaterialTheme.typography.bodyMedium)

                FilterChip(
                    selected = selectedTool == 0,
                    onClick = { selectedTool = 0 },
                    label = { Text("● 黒石") }
                )
                FilterChip(
                    selected = selectedTool == 1,
                    onClick = { selectedTool = 1 },
                    label = { Text("○ 白石") }
                )
                FilterChip(
                    selected = selectedTool == 2,
                    onClick = { selectedTool = 2 },
                    label = { Text("× 消去") }
                )
            }

            // 回転ボタン群 ＋ 右端の控えめな「設定」ボタン
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.rotateLeft() }) {
                        Text("左90°回転")
                    }
                    Button(onClick = { viewModel.rotateRight() }) {
                        Text("右90°回転")
                    }
                }

                // 碁盤の右下に置く控えめな設定ボタン
                OutlinedButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.height(38.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    border = BorderStroke(1.dp, Color.Gray)
                ) {
                    Text(
                        text = "設定",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 19x19 碁盤・碁石描画エリア (Canvas実装)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val size = size.width.toFloat()
                                val cellSize = size / 19f
                                val col = (offset.x / cellSize).toInt().coerceIn(0, 18)
                                val row = (offset.y / cellSize).toInt().coerceIn(0, 18)

                                val newColor = when (selectedTool) {
                                    0 -> StoneColor.BLACK
                                    1 -> StoneColor.WHITE
                                    else -> StoneColor.EMPTY
                                }
                                viewModel.updateStone(row, col, newColor)
                            }
                        }
                ) {
                    val boardSize = size.width
                    val cellSize = boardSize / 19f

                    // 1. 碁盤の地色を描画
                    drawRect(color = Color(0xFFDCB35C))

                    // 2. 罫線を描画 (19x19) - 繊細な墨色・1.0dpの太さ
                    val lineStrokePx = 1.0f.dp.toPx()
                    val lineColor = Color(0xFF111111)

                    for (i in 0 until 19) {
                        val pos = cellSize * i + cellSize / 2f
                        // 横線
                        drawLine(
                            color = lineColor,
                            start = Offset(cellSize / 2f, pos),
                            end = Offset(boardSize - cellSize / 2f, pos),
                            strokeWidth = lineStrokePx
                        )
                        // 縦線
                        drawLine(
                            color = lineColor,
                            start = Offset(pos, cellSize / 2f),
                            end = Offset(pos, boardSize - cellSize / 2f),
                            strokeWidth = lineStrokePx
                        )
                    }

                    // 3. 星（目印の点）を描画
                    val starRadiusPx = 2.5f.dp.toPx()
                    val starIndices = listOf(3, 9, 15)
                    for (r in starIndices) {
                        for (c in starIndices) {
                            val cx = cellSize * c + cellSize / 2f
                            val cy = cellSize * r + cellSize / 2f
                            drawCircle(
                                color = lineColor,
                                radius = starRadiusPx,
                                center = Offset(cx, cy)
                            )
                        }
                    }

                    // 4. 碁石の描画
                    val boardLayout = uiState.boardLayout
                    val stoneRadius = cellSize * 0.45f
                    val whiteBorderPx = 0.8f.dp.toPx()

                    for (r in 0 until 19) {
                        for (c in 0 until 19) {
                            if (r < boardLayout.size && c < boardLayout[r].size) {
                                val color = boardLayout[r][c]
                                val cx = cellSize * c + cellSize / 2f
                                val cy = cellSize * r + cellSize / 2f

                                if (color == StoneColor.BLACK) {
                                    drawCircle(
                                        color = Color.Black,
                                        radius = stoneRadius,
                                        center = Offset(cx, cy)
                                    )
                                } else if (color == StoneColor.WHITE) {
                                    drawCircle(
                                        color = Color.White,
                                        radius = stoneRadius,
                                        center = Offset(cx, cy)
                                    )
                                    drawCircle(
                                        color = Color.Black,
                                        radius = stoneRadius,
                                        center = Offset(cx, cy),
                                        style = Stroke(width = whiteBorderPx)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 下部アクションボタンエリア
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { showExportDialog = true },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .padding(end = 8.dp)
            ) {
                Text("SGF出力 & 共有")
            }

            OutlinedButton(
                onClick = onBackClick,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .padding(start = 8.dp)
            ) {
                Text("戻る")
            }
        }
    }

    // ★ SGF出力・共有ダイアログの表示処理
    if (showExportDialog) {
        val activity = context as? Activity

        SgfExportDialog(
            initialEmail = initialEmail,
            onDismissRequest = { showExportDialog = false },
            onActionSelected = { action, email ->
                showExportDialog = false

                viewModel.exportSgf(context, uiState.gameRecord, email) { savedFile ->
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        savedFile
                    )

                    when (action) {
                        SgfExportAction.OPEN_VIEWER -> {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/x-go-sgf")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "棋譜ビューアーを選択"))
                            activity?.finishAffinity()
                        }

                        SgfExportAction.SEND_EMAIL -> {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "message/rfc822"
                                putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                                putExtra(Intent.EXTRA_SUBJECT, "GobanReader 棋譜データ")
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "メールソフトを選択"))
                            activity?.finishAffinity()
                        }

                        SgfExportAction.SAVE_AND_EXIT -> {
                            Toast.makeText(context, "SGFファイルを保存しました", Toast.LENGTH_SHORT).show()
                            activity?.finishAffinity()
                        }
                    }
                }
            }
        )
    }
}
