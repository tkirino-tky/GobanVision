package com.github.tkirino.gobanreader

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.tkirino.gobanreader.config.DebugConfig
import com.github.tkirino.gobanreader.export.SgfWriter
import com.github.tkirino.gobanreader.model.GameRecord
import com.github.tkirino.gobanreader.model.ReaderUiState
import com.github.tkirino.gobanreader.model.StoneColor
import com.github.tkirino.gobanreader.stones.CnnStoneDetector
import com.github.tkirino.gobanreader.utility.PreferencesManager
import com.github.tkirino.gobanreader.vision.BoardRectifier
import com.github.tkirino.gobanreader.vision.YoloCornerDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()
    var toastMessage by mutableStateOf<String?>(null)
    private var lastSourceMat: Mat? = null

    // 撮影時から出力時までセッションIDを保持（NullPointer防止のため初期値を割り当て）
    private var currentSessionId: String? = null

    private var yoloCornerDetector: YoloCornerDetector? = null
    private var cornerInterpreter: Interpreter? = null
    private var stoneInterpreter: Interpreter? = null

    var cornerQualityMessage by mutableStateOf("")
        private set
    var isCornerQualityGood by mutableStateOf(true)
        private set

    init {
        cornerInterpreter = loadModelInterpreter("board_corner_model.tflite")
        cornerInterpreter?.let {
            yoloCornerDetector = YoloCornerDetector(it)
        }
        stoneInterpreter = loadModelInterpreter("goban_stone_model.tflite")
    }

    private fun loadModelInterpreter(assetName: String): Interpreter? {
        try {
            val context = getApplication<Application>()
            val assetFileDescriptor = context.assets.openFd(assetName)
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val mappedByteBuffer =
                fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            Log.d("MainViewModel", "$assetName のロードに成功しました")
            return Interpreter(mappedByteBuffer)
        } catch (e: Exception) {
            Log.e("MainViewModel", "$assetName のロードに失敗しました", e)
            return null
        }
    }

    fun updateHandicap(handicap: Int) {
        _uiState.value =
            _uiState.value.copy(gameRecord = _uiState.value.gameRecord.copy(handicap = handicap))
    }

    fun updateKomi(komi: Float) {
        _uiState.value =
            _uiState.value.copy(gameRecord = _uiState.value.gameRecord.copy(komi = komi))
    }

    var remoteShutterTrigger by mutableStateOf(0)
        private set

    fun triggerRemoteShutter() {
        remoteShutterTrigger++
    }

    private var lastYoloExportTime = 0L

    fun loadPhotoForAdjustment(photoPath: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rawSrc = Imgcodecs.imread(photoPath)
                if (rawSrc.empty()) {
                    Log.e("MainViewModel", "画像の読み込みに失敗しました: $photoPath")
                    withContext(Dispatchers.Main) { onResult(false) }
                    return@launch
                }

                val imgCols = rawSrc.cols()
                val imgRows = rawSrc.rows()

                // ★根本解決: 縦長画像の中央から正方形(1:1)を正確に切り出す
                val squareSize = minOf(imgCols, imgRows)
                val startX = (imgCols - squareSize) / 2
                val startY = (imgRows - squareSize) / 2 // 上端(0)ではなく「中央」から切る

                val guideRect = Rect(startX, startY, squareSize, squareSize)

                // 完全にカメラ表示と同じ「中央正方形」の Mat を生成
                val fullSrc = Mat(rawSrc, guideRect).clone()
                rawSrc.release()

                lastSourceMat?.release()
                lastSourceMat = fullSrc.clone()

                // 画面表示用 Bitmap も「中央正方形 Mat」から作成
                val rgbMat = Mat()
                Imgproc.cvtColor(fullSrc, rgbMat, Imgproc.COLOR_BGR2RGB)
                val bmp = android.graphics.Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), android.graphics.Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(rgbMat, bmp)
                rgbMat.release()

                _uiState.update { it.copy(adjustmentBitmap = bmp) }

                // ⚠️ 【聖域・削除変更厳禁】YOLO訓練データ出力
                val currentTime = System.currentTimeMillis()

                if (DebugConfig.YOLO_TRAINING_DATA_EXPORT) {
                    if (currentTime - lastYoloExportTime > 1500L) {
                        lastYoloExportTime = currentTime
                        currentSessionId = "session_$currentTime"

                        exportYOLOTrainingData(fullSrc, currentSessionId!!)
                        Log.d("MainViewModel", "本物の正方形(1:1)YOLO訓練データを正常出力しました: $currentSessionId")
                    }
                }

                val detector = yoloCornerDetector
                val detectionResult = detector?.detectCorners(fullSrc, Rect(0, 0, squareSize, squareSize))

                val sizeD = squareSize.toDouble()
                val margin = sizeD * 0.08

                val defaultCorners = listOf(
                    Point(margin, margin),
                    Point(sizeD - margin, margin),
                    Point(sizeD - margin, sizeD - margin),
                    Point(margin, sizeD - margin)
                )

                withContext(Dispatchers.Main) {
                    isCornerQualityGood = false
                    cornerQualityMessage = "手動でコーナーの位置を補正してください"

                    val initialCorners = if (detectionResult != null && detectionResult.corners.size == 4) {
                        detectionResult.corners
                    } else {
                        defaultCorners
                    }
                    _uiState.update { it.copy(initialCorners = initialCorners) }

                    onResult(false)
                }

                fullSrc.release()

            } catch (e: Exception) {
                Log.e("MainViewModel", "loadPhotoForAdjustment致命的エラー", e)
                withContext(Dispatchers.Main) {
                    isCornerQualityGood = false
                    cornerQualityMessage = "処理中にエラーが発生しました"
                    _uiState.update { it.copy(initialCorners = emptyList()) }
                    onResult(false)
                }
            }
        }
    }

    private fun createArithmeticGrid(width: Double, height: Double): Array<Array<Point>> {
        val stepX = width / 19.0
        val stepY = height / 19.0
        val centerIndex = 9.0
        val baseCenterX = (centerIndex + 0.5) * stepX
        val baseCenterY = (centerIndex + 0.5) * stepY
        return Array(19) { r ->
            Array(19) { c ->
                Point(
                    baseCenterX + (c - centerIndex) * stepX,
                    baseCenterY + (r - centerIndex) * stepY
                )
            }
        }
    }

    fun processWithCorners(corners: List<Point>) {
        val src = lastSourceMat?.clone() ?: return

        viewModelScope.launch(Dispatchers.Default) {
            try {
                _uiState.update { it.copy(initialCorners = corners, isLoading = true) }
                val rectifiedMat = BoardRectifier.rectify(src, corners)
                src.release()

                val geometryGrid = createArithmeticGrid(
                    rectifiedMat.cols().toDouble(),
                    rectifiedMat.rows().toDouble()
                )

                val stoneInterpreterInstance = stoneInterpreter
                if (stoneInterpreterInstance != null) {
                    val cnnDetector = CnnStoneDetector(stoneInterpreterInstance)
                    val stoneResult = cnnDetector.detectStones(rectifiedMat, geometryGrid)

                    // ⚠️ 【聖域・削除変更厳禁】CNN訓練データ出力
                    if (DebugConfig.isEnabled && DebugConfig.CNN_TRAINING_DATA_EXPORT) {
                        exportCNNTrainingData(rectifiedMat, geometryGrid, stoneResult)
                    }

                    _uiState.update { it.copy(isLoading = false, boardLayout = stoneResult) }
                    toastMessage = "碁盤の解析が完了しました"
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                    toastMessage = "碁石認識モデルが初期化されていません"
                }

                rectifiedMat.release()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                toastMessage = "解析エラー: ${e.localizedMessage}"
            }
        }
    }

    fun processCapturedPhoto(file: File) {
        loadPhotoForAdjustment(file.absolutePath)
    }

    fun updateBlackPlayer(name: String) {
        _uiState.update { it.copy(gameRecord = it.gameRecord.copy(blackPlayer = name)) }
    }

    fun updateWhitePlayer(name: String) {
        _uiState.update { it.copy(gameRecord = it.gameRecord.copy(whitePlayer = name)) }
    }

    fun updateNextPlayer(nextPlayer: String) {
        _uiState.update { it.copy(gameRecord = it.gameRecord.copy(nextPlayer = nextPlayer)) }
    }

    fun updateStone(row: Int, col: Int, color: StoneColor) {
        _uiState.update { state ->
            val newLayout = state.boardLayout.mapIndexed { r, list ->
                if (r == row) {
                    list.mapIndexed { c, current -> if (c == col) color else current }
                } else {
                    list
                }
            }
            state.copy(boardLayout = newLayout)
        }
    }

    fun rotateLeft() {
        _uiState.update { state ->
            val current = state.boardLayout
            val size = current.size
            val newLayout = List(size) { r -> List(size) { c -> current[c][size - 1 - r] } }
            state.copy(boardLayout = newLayout)
        }
    }

    fun rotateRight() {
        _uiState.update { state ->
            val current = state.boardLayout
            val size = current.size
            val newLayout = List(size) { r -> List(size) { c -> current[size - 1 - c][r] } }
            state.copy(boardLayout = newLayout)
        }
    }

    override fun onCleared() {
        super.onCleared()
        lastSourceMat?.release()
        cornerInterpreter?.close()
        stoneInterpreter?.close()
    }

    fun exportSgf(
        context: android.content.Context,
        gameRecord: GameRecord,
        recipientEmail: String,
        onFileSaved: (File) -> Unit
    ) {
        viewModelScope.launch {
            PreferencesManager.saveEmail(context, recipientEmail)
            val currentLayout = _uiState.value.boardLayout
            val blackStones = mutableListOf<Pair<Int, Int>>()
            val whiteStones = mutableListOf<Pair<Int, Int>>()

            for (r in 0 until 19) {
                for (c in 0 until 19) {
                    when (currentLayout[r][c]) {
                        StoneColor.BLACK -> blackStones.add(Pair(c, r))
                        StoneColor.WHITE -> whiteStones.add(Pair(c, r))
                        else -> {}
                    }
                }
            }

            val updatedGameRecord = gameRecord.copy(
                initialBlackStones = blackStones,
                initialWhiteStones = whiteStones
            )

            val sgfWriter = SgfWriter(context)
            val sgfString = sgfWriter.generateSgfString(updatedGameRecord)
            val result = sgfWriter.saveSgfFileAutoNamed(sgfString)

            result.onSuccess { savedFile ->
                if (recipientEmail.isNotBlank()) onFileSaved(savedFile)
            }
        }
    }

    // ⚠️ 【聖域・削除変更厳禁】YOLO訓練データ出力
    // ⚠️ 【聖域・削除変更厳禁】YOLO訓練データ出力
    private fun exportYOLOTrainingData(mat: Mat, sessionId: String) {
        try {
            val baseDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "YOLO_Boards"
            )
            val sampleDir = File(baseDir, sessionId).apply { if (!exists()) mkdirs() }

            // ★修正: Bitmapへの変換を行わず、OpenCVで正方形Matを直接PNG出力（潰れ・歪みを完全に防止）
            val file = File(sampleDir, "board_orig.png")
            val success = Imgcodecs.imwrite(file.absolutePath, mat)

            if (success) {
                Log.d("MainViewModel", "YOLO訓練データ(PNG)を正方形で正常出力しました: ${file.absolutePath}")
            } else {
                Log.e("MainViewModel", "YOLO訓練データの保存に失敗しました: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("OriginalBoardExport", "エラー", e)
        }
    }

    // ⚠️ 【聖域・削除変更厳禁】CNN訓練データ出力
    private fun exportCNNTrainingData(
        rectifiedMat: Mat,
        geometryGrid: Array<Array<Point>>,
        boardLayout: List<List<StoneColor>>
    ) {
        try {
            val downloadsDir =
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val baseDir = File(downloadsDir, "goban_dataset")
            val gameId = "game_${System.currentTimeMillis()}"
            val gameFolder = File(baseDir, gameId)
            if (!gameFolder.exists()) {
                gameFolder.mkdirs()
            }

            val patchSize = 40
            val csvContent = StringBuilder()
            csvContent.append("filename_base,row,col,label\n")

            for (r in 0 until 19) {
                for (c in 0 until 19) {
                    val center = geometryGrid[r][c]
                    val x = center.x.toInt()
                    val y = center.y.toInt()

                    val half = patchSize / 2
                    val x1 = (x - half).coerceIn(0, rectifiedMat.cols() - patchSize)
                    val y1 = (y - half).coerceIn(0, rectifiedMat.rows() - patchSize)
                    val rect = Rect(x1, y1, patchSize, patchSize)

                    if (rect.width > 0 && rect.height > 0) {
                        val colorPatch = Mat(rectifiedMat, rect)
                        val filenameBase = "r${r}_c${c}"
                        val colorFile = File(gameFolder, "${filenameBase}_color.png")
                        Imgcodecs.imwrite(colorFile.absolutePath, colorPatch)

                        val labelNum = when (boardLayout[r][c]) {
                            StoneColor.EMPTY -> 0
                            StoneColor.BLACK -> 1
                            StoneColor.WHITE -> 2
                        }
                        csvContent.append("$filenameBase,$r,$c,$labelNum\n")
                        colorPatch.release()
                    }
                }
            }

            File(gameFolder, "labels.csv").writeText(csvContent.toString())
        } catch (e: Exception) {
            Log.e("DatasetExport", "データセット出力エラー", e)
        }
    }
}
