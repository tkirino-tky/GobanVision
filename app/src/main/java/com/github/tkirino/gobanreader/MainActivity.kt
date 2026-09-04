package com.github.tkirino.gobanreader

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.github.tkirino.gobanreader.ui.theme.GobanReaderTheme
import org.opencv.android.OpenCVLoader
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager


class MainActivity : ComponentActivity() {

    // ViewModelをActivity側でも共有して保持する
    private val viewModel: MainViewModel by viewModels()

    // 現在カメラ画面にいるかどうかを保持するフラグ（App.ktやNavHostから更新する、または簡易的に保持）
    var isInCameraScreen: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (OpenCVLoader.initDebug()) {
            Log.d("GobanReader", "OpenCV loaded successfully! 準備完了です。")
        } else {
            Log.e("GobanReader", "OpenCV load failed. ライブラリの読み込みに失敗しました。")
        }

        // ★ここでアプリ起動時に一回必ずプローブを実行する
        probeCameraCharacteristics(applicationContext)

        enableEdgeToEdge()
        setContent {
            GobanReaderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Scaffoldから提供されるinnerPaddingをBoxで適用し、コンテンツの重なりを防ぐ
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        App(viewModel = viewModel, onCameraScreenChanged = { inCamera ->
                            isInCameraScreen = inCamera
                        })
                    }
                }
            }
        }
    }

    // ★最上流でキーイベントを横取り（ここで音量スライダーの暴発を防ぐ）
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isInCameraScreen) {
            val keyCode = event.keyCode
            val action = event.action

            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (action == KeyEvent.ACTION_DOWN) {
                    // カメラ画面にいる時は、ViewModel経由でシャッターを切るよう指示する
                    viewModel.triggerRemoteShutter()
                }
                // trueを返すことで、OSへのイベント伝播を止め、音量スライダーの表示を完全に防ぐ
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

fun probeCameraCharacteristics(context: Context) {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    for (id in cameraManager.cameraIdList) {
        val chars = cameraManager.getCameraCharacteristics(id)
        val physicalIds = chars.physicalCameraIds
        val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val facing = chars.get(CameraCharacteristics.LENS_FACING)

        Log.d("CameraProbe", "=== Logical ID: $id ===")
        Log.d("CameraProbe", "  facing: $facing")
        Log.d("CameraProbe", "  focalLengths: ${focalLengths?.joinToString()}")
        Log.d("CameraProbe", "  zoomRatioRange: $zoomRange")
        Log.d("CameraProbe", "  physicalCameraIds: $physicalIds")

        physicalIds.forEach { physId ->
            val physChars = cameraManager.getCameraCharacteristics(physId)
            val physFocal = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            Log.d("CameraProbe", "    physical $physId focalLengths: ${physFocal?.joinToString()}")
        }
    }
}

@Preview
@Composable
fun Show() {
    Text("Hello")
}
