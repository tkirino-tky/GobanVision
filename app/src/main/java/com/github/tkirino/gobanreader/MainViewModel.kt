package com.github.tkirino.gobanreader

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.tkirino.gobanreader.config.DebugConfig
import com.github.tkirino.gobanreader.export.SgfParser
import com.github.tkirino.gobanreader.export.SgfWriter
import com.github.tkirino.gobanreader.model.GameRecord
import com.github.tkirino.gobanreader.model.ReaderUiState
import com.github.tkirino.gobanreader.model.StoneColor
import com.github.tkirino.gobanreader.stones.CnnStoneDetector
import com.github.tkirino.gobanreader.utility.GeometryUtils
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
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()
    var toastMessage by mutableStateOf<String?>(null)
    private var lastSourceMat: Mat? = null

    // 撮影時から出力時までセッションIDを保持するための変数
    private var currentSessionId: String? = null

    private var yoloCornerDetector: YoloCornerDetector? = null
    private var cornerInterpreter: Interpreter? = null
    private var stoneInterpreter: Interpreter? = null

    var cornerQualityMessage by mutableStateOf("")
        private set
    var isCornerQualityGood by mutableStateOf(true)
        private set

    init {
        // コーナー検出用 TensorFlow Lite モデルのロード
        cornerInterpreter = loadModelInterpreter("board_corner_model.tflite")
        cornerInterpreter?.let {
            yoloCornerDetector = YoloCornerDetector(it)
        }

        // 碁石認識用 TensorFlow Lite モデルのロード
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
            val mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            Log.d("MainViewModel", "$assetName のロードに成功しました")
            return Interpreter(mappedByteBuffer)
        } catch (e: Exception) {
            Log.e("MainViewModel", "$assetName のロードに失敗しました", e)
            return null
        }
    }

    fun updateHandicap(handicap: Int) {
        _uiState.value = _uiState.value.copy(gameRecord = _uiState.value.gameRecord.copy(handicap = handicap))
    }

    fun updateKomi(komi: Float) {
        _uiState.value = _uiState.value.copy(gameRecord = _uiState.value.gameRecord.copy(komi = komi))
    }

    var remoteShutterTrigger by mutableStateOf(0)
        private set

    fun triggerRemoteShutter() {
        remoteShutterTrigger++
    }

    fun loadPhotoForAdjustment(photoPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. オリジナル画像の読み込み
                val fullSrc = Imgcodecs.imread(photoPath)
                if (fullSrc.empty()) {
                    Log.e("MainViewModel", "画像の読み込みに失敗しました: $photoPath")
                    return@launch
                }
                lastSourceMat?.release()
                lastSourceMat = fullSrc.clone()

                val imgCols = fullSrc.cols().toDouble()
                val imgRows = fullSrc.rows().toDouble()

                // 2. CameraScreenの全幅正方形領域に相当する正方形(1:1)のクロップ範囲を算出
                val squareSize = kotlin.math.min(imgCols, imgRows)
                val startX = (imgCols - squareSize) / 2.0
                val startY = (imgRows - squareSize) / 2.0

                val guideRect = Rect(
                    startX.toInt().coerceIn(0, fullSrc.cols() - 1),
                    startY.toInt().coerceIn(0, fullSrc.rows() - 1),
                    squareSize.toInt().coerceAtMost(fullSrc.cols() - startX.toInt()),
                    squareSize.toInt().coerceAtMost(fullSrc.rows() - startY.toInt())
                )

                // 3. YOLO学習用・デバッグ用画像の切り出しと保存
                if (DebugConfig.EXPORT_ORIGINAL_BOARD_FOR_AUG && currentSessionId != null) {
                    if (guideRect.width > 0 && guideRect.height > 0) {
                        val croppedBoard = Mat(fullSrc, guideRect)
                        saveCroppedBoardWithSessionId(croppedBoard, currentSessionId!!)
                        croppedBoard.release()
                    }
                }

                // 4. YoloCornerDetectorによるコーナー検出の実行
                val detector = yoloCornerDetector
                val detectionResult = detector?.detectCorners(fullSrc, guideRect)

                withContext(Dispatchers.Main) {
                    if (detectionResult != null && detectionResult.found) {
                        // 検出できた場合は、コーナー座標を保持してUI状態を更新
                        isCornerQualityGood = true
                        cornerQualityMessage = "コーナーが正常に検出されました"
                        _uiState.update { it.copy(initialCorners = detectionResult.corners) }
                    } else {
                        // 検出失敗時のエラーハンドリング
                        isCornerQualityGood = false
                        cornerQualityMessage = detectionResult?.errorMessage ?: "YOLOモデルが初期化されていません"
                        _uiState.update { it.copy(initialCorners = emptyList()) }
                    }
                }

                fullSrc.release()

            } catch (e: Exception) {
                Log.e("MainViewModel", "loadPhotoForAdjustmentエラー", e)
            }
        }
    }

    private fun isCornerQualityHigh(corners: List<org.opencv.core.Point>, imgWidth: Double, imgHeight: Double): Boolean {
        if (corners.size != 4) return false
        val p0 = corners[0]; val p1 = corners[1]; val p2 = corners[2]; val p3 = corners[3]
        val topWidth = Math.hypot(p1.x - p0.x, p1.y - p0.y)
        val bottomWidth = Math.hypot(p2.x - p3.x, p2.y - p3.y)
        val leftHeight = Math.hypot(p3.x - p0.x, p3.y - p0.y)
        val rightHeight = Math.hypot(p2.x - p1.x, p2.y - p1.y)

        if (topWidth < 100.0 || bottomWidth < 100.0 || leftHeight < 100.0 || rightHeight < 100.0) return false
        if (topWidth > imgWidth || bottomWidth > imgWidth || leftHeight > imgHeight || rightHeight > imgHeight) return false
        val widthRatio = kotlin.math.max(topWidth, bottomWidth) / kotlin.math.min(topWidth, bottomWidth)
        val heightRatio = kotlin.math.max(leftHeight, rightHeight) / kotlin.math.min(leftHeight, rightHeight)
        return widthRatio <= 1.6 && heightRatio <= 1.6
    }

    private fun rotateBitmapIfNeeded(imagePath: String, bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        try {
            val exif = android.media.ExifInterface(imagePath)
            val orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            val matrix = android.graphics.Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }
            return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it != bitmap) bitmap.recycle() }
        } catch (e: Exception) { return bitmap }
    }

    private fun createArithmeticGrid(width: Double, height: Double, boardMat: Mat? = null): Array<Array<org.opencv.core.Point>> {
        val stepX = width / 19.0
        val stepY = height / 19.0
        val centerIndex = 9.0
        val baseCenterX = (centerIndex + 0.5) * stepX
        val baseCenterY = (centerIndex + 0.5) * stepY
        return Array(19) { r -> Array(19) { c -> org.opencv.core.Point(baseCenterX + (c - centerIndex) * stepX, baseCenterY + (r - centerIndex) * stepY) } }
    }

    fun processWithCorners(corners: List<org.opencv.core.Point>) {
        val src = lastSourceMat?.clone() ?: return

        viewModelScope.launch(Dispatchers.Default) {
            try {
                _uiState.update { it.copy(initialCorners = corners, isLoading = true) }
                val rectifiedMat = BoardRectifier.rectify(src, corners)
                src.release()

                val geometryGrid = createArithmeticGrid(rectifiedMat.cols().toDouble(), rectifiedMat.rows().toDouble())

                val stoneInterpreterInstance = stoneInterpreter
                if (stoneInterpreterInstance != null) {
                    val cnnDetector = CnnStoneDetector(stoneInterpreterInstance)
                    val stoneResult = cnnDetector.detectStones(rectifiedMat, geometryGrid)

                    // CNN訓練データの出力処理 (確実に実行)
                    if (DebugConfig.isEnabled && DebugConfig.EXPORT_DATASET_PAIR) {
                        exportDatasetPair(rectifiedMat, geometryGrid, stoneResult)
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

    fun processCapturedPhoto(file: File) { loadPhotoForAdjustment(file.absolutePath) }
    fun updateBlackPlayer(name: String) { _uiState.update { it.copy(gameRecord = it.gameRecord.copy(blackPlayer = name)) } }
    fun updateWhitePlayer(name: String) { _uiState.update { it.copy(gameRecord = it.gameRecord.copy(whitePlayer = name)) } }
    fun updateNextPlayer(nextPlayer: String) { _uiState.update { it.copy(gameRecord = it.gameRecord.copy(nextPlayer = nextPlayer)) } }

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

    fun loadDummySgf() {
        val dummySgfText = "(;GM[1]FF[4]AP[Zenith:7.0]SZ[19]HA[0]KM[6.5]CA[UTF-8]AB[pd][qp][cc][dc][ec][fc][gb][hb][ge][gf][fg][fi][dh][cg][cj][bs][br][cq][cp][co][cn][do][bm][ep][eq][fp][fo][fn][go][ho][io][hm][hl]AW[cd][dd][ed][fd][gd][gc][hc][ic][ff][ci][di][ej][gk][fm][gm][gn][dl][dm][dn][en][eo][bl][bp][dp][dq][dr][ds][cr][er][fq][gq][hp][jq][op])".trimIndent()
        val parser = SgfParser()
        val record = parser.parse(dummySgfText)
        val matrix = MutableList(19) { MutableList(19) { StoneColor.EMPTY } }
        for (coord in record.initialBlackStones) { matrix[coord.second][coord.first] = StoneColor.BLACK }
        for (coord in record.initialWhiteStones) { matrix[coord.second][coord.first] = StoneColor.WHITE }
        _uiState.update { it.copy(gameRecord = record, boardLayout = matrix.map { it.toList() }) }
    }

    /**
     * CNN訓練データ出力用関数
     */
    private fun exportDatasetPair(
        rectifiedMat: Mat,
        geometryGrid: Array<Array<Point>>,
        boardLayout: List<List<StoneColor>>
    ) {
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
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

            val csvFile = File(gameFolder, "labels.csv")
            csvFile.writeText(csvContent.toString())

            Log.d("DatasetExport", "labels.csv の書き込みが完了しました。サイズ: ${csvFile.length()} バイト")

        } catch (e: Exception) {
            Log.e("DatasetExport", "データセット出力中にエラーが発生しました", e)
        }
    }

    fun exportSgf(context: android.content.Context, gameRecord: GameRecord, recipientEmail: String, onFileSaved: (File) -> Unit) {
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

    companion object {
        fun exportCroppedRectImage(edgeMat: Mat) {
            if (!DebugConfig.EXPORT_CROPPED_RECT_IMAGE) return
            try {
                val baseDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Cropped_Rect")
                val sampleDir = File(baseDir, "sample_${System.currentTimeMillis()}").apply { if (!exists()) mkdirs() }
                val resizedMat = Mat()
                Imgproc.resize(edgeMat, resizedMat, org.opencv.core.Size(256.0, 256.0))
                Imgcodecs.imwrite(File(sampleDir, "board_binary.png").absolutePath, resizedMat)
                resizedMat.release()
            } catch (e: Exception) { Log.e("CroppedRectExport", "エラー", e) }
        }

        /**
         * 最初に撮影した生画像をガイドフレームで切り取ったものを受け取り、
         * YOLO_Boards/<sessionId>/board_orig.png として保存する
         */
        fun saveCroppedBoardWithSessionId(mat: Mat, sessionId: String) {
            try {
                val baseDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "YOLO_Boards")
                val sampleDir = File(baseDir, sessionId)
                if (!sampleDir.exists()) {
                    sampleDir.mkdirs()
                }

                val rgbMat = Mat()
                if (mat.channels() == 3) {
                    Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_BGR2RGB)
                } else if (mat.channels() == 4) {
                    Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_BGRA2RGBA)
                } else {
                    mat.copyTo(rgbMat)
                }

                val bmp = android.graphics.Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), android.graphics.Bitmap.Config.ARGB_8888)
                org.opencv.android.Utils.matToBitmap(rgbMat, bmp)
                rgbMat.release()

                val file = File(sampleDir, "board_orig.png")
                FileOutputStream(file).use { stream ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                }
                bmp.recycle()
            } catch (e: Exception) {
                Log.e("OriginalBoardExport", "エラー", e)
            }
        }

        @Deprecated("Replaced by saveCroppedBoardWithSessionId")
        fun saveCroppedBoardToDownload(mat: Mat) {
            // 互換性のためのプレースホルダー
        }
    }

    fun exportCornerImages(originalMat: Mat, corners: List<org.opencv.core.Point>, gridSpacing: Double) {
        try {
            val baseDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Cropped_Corners")
            val sampleDir = File(baseDir, "corners_${System.currentTimeMillis()}").apply { if (!exists()) mkdirs() }
            val margin = (gridSpacing * 1.5).toInt()
            corners.forEachIndexed { i, pt ->
                val roi = Rect((pt.x - margin).toInt().coerceAtLeast(0), (pt.y - margin).toInt().coerceAtLeast(0), margin * 2, margin * 2)
                val cropped = originalMat.submat(roi)
                Imgcodecs.imwrite(File(sampleDir, "corner_${i}.png").absolutePath, cropped)
                cropped.release()
            }
        } catch (e: Exception) { Log.e("CornerExport", "エラー", e) }
    }
}
