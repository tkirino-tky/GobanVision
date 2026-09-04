package com.github.tkirino.gobanreader.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tkirino.gobanreader.MainViewModel
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Alignment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    viewModel: MainViewModel,
    onBlackPlayerChanged: (String) -> Unit,
    onWhitePlayerChanged: (String) -> Unit,
    onGetGobanClick: () -> Unit,
    onHistoryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val nextPlayer = uiState.gameRecord.nextPlayer.ifEmpty { "B" }
    val keyboardController = LocalSoftwareKeyboardController.current

    var isHandicapExpanded by remember { mutableStateOf(false) }
    var isKomiExpanded by remember { mutableStateOf(false) }

    val handicapOptions = listOf(0, 2, 3, 4, 5, 6, 7, 8, 9)
    val komiOptions = listOf(
        -10.5f, -9.5f, -8.5f, -7.5f, -6.5f, -5.5f, -4.5f, -3.5f, -2.5f, -1.5f, -0.5f,
        0.5f, 1.5f, 2.5f, 3.5f, 4.5f, 5.5f, 6.5f, 7.5f, 8.5f, 9.5f, 10.5f
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // 【改善】サイズを大きくし、中央配置に修正
        Text(
            text = "GobanReader",
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "対局情報の入力",
            style = MaterialTheme.typography.headlineMedium
        )

        // 置き石とコミの左右配置 ＆ ドロップダウン
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 置き石
            ExposedDropdownMenuBox(
                expanded = isHandicapExpanded,
                onExpandedChange = { isHandicapExpanded = it },
                modifier = Modifier.weight(1f)
            ) {
                val currentHandicapText = if (uiState.gameRecord.handicap == 0) "互先 (0)" else "${uiState.gameRecord.handicap}子"
                OutlinedTextField(
                    value = currentHandicapText,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("置き石") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isHandicapExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = isHandicapExpanded,
                    onDismissRequest = { isHandicapExpanded = false }
                ) {
                    handicapOptions.forEach { h ->
                        DropdownMenuItem(
                            text = { Text(if (h == 0) "互先 (0)" else "${h}子") },
                            onClick = {
                                viewModel.updateHandicap(h)
                                isHandicapExpanded = false
                            }
                        )
                    }
                }
            }

            // コミ
            ExposedDropdownMenuBox(
                expanded = isKomiExpanded,
                onExpandedChange = { isKomiExpanded = it },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = uiState.gameRecord.komi.toString(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("コミ") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isKomiExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = isKomiExpanded,
                    onDismissRequest = { isKomiExpanded = false }
                ) {
                    komiOptions.forEach { k ->
                        DropdownMenuItem(
                            text = { Text(k.toString()) },
                            onClick = {
                                viewModel.updateKomi(k)
                                isKomiExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // 次の手番選択
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "次の手番 (必須)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (nextPlayer == "B") {
                    Button(
                        onClick = { viewModel.updateNextPlayer("B") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("黒番 (先手)")
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.updateNextPlayer("B") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("黒番 (先手)")
                    }
                }

                if (nextPlayer == "W") {
                    Button(
                        onClick = { viewModel.updateNextPlayer("W") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("白番")
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.updateNextPlayer("W") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("白番")
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // 対局者名（黒）
        OutlinedTextField(
            value = uiState.gameRecord.blackPlayer,
            onValueChange = { onBlackPlayerChanged(it) },
            label = { Text("黒番の対局者名 (省略可)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { keyboardController?.hide() }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // 対局者名（白）
        OutlinedTextField(
            value = uiState.gameRecord.whitePlayer,
            onValueChange = { onWhitePlayerChanged(it) },
            label = { Text("白番の対局者名 (省略可)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { keyboardController?.hide() }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // カメラ画面への遷移ボタン
        Button(
            onClick = onGetGobanClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("碁盤を撮影する", style = MaterialTheme.typography.titleMedium)
        }
    }
}
