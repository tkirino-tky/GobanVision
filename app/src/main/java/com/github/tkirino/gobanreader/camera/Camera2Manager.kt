package com.github.tkirino.gobanreader.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView

class Camera2Manager(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    var previewSize: Size = Size(1080, 1440)
        private set

    companion object {
        private const val TAG = "Camera2Manager"
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "バックグラウンドスレッド停止エラー: ${e.message}")
        }
    }

    fun openCamera(textureView: TextureView, targetZoomRatio: Float, onPreviewSizeDetermined: (Size) -> Unit) {
        closeCamera()
        startBackgroundThread()

        val selectedCameraId = selectBestCameraId(targetZoomRatio)
        Log.d(TAG, "選択カメラID: $selectedCameraId (ズーム: $targetZoomRatio)")

        try {
            previewSize = chooseOptimalPreviewSize(selectedCameraId)
            onPreviewSizeDetermined(previewSize)

            cameraManager.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession(textureView, targetZoomRatio)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "カメラオープンエラー: $error")
                }
            }, backgroundHandler ?: Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            Log.e(TAG, "カメラ権限エラー: ${e.message}")
        }
    }

    private fun selectBestCameraId(targetZoomRatio: Float): String {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                if (targetZoomRatio < 1.0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val physicalIds = chars.physicalCameraIds
                    for (pId in physicalIds) {
                        val pChars = cameraManager.getCameraCharacteristics(pId)
                        val focalLengths = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        if (focalLengths != null && focalLengths.any { it < 3.5f }) {
                            return pId
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "カメラID選択エラー: ${e.message}")
        }
        return "0"
    }

    private fun chooseOptimalPreviewSize(cameraId: String): Size {
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val choices = map?.getOutputSizes(SurfaceTexture::class.java)

            if (!choices.isNullOrEmpty()) {
                val targetRatio = 4.0 / 3.0
                return choices.sortedByDescending { it.width * it.height }
                    .firstOrNull { size ->
                        val ratio = size.width.toDouble() / size.height.toDouble()
                        Math.abs(ratio - targetRatio) < 0.1 && size.width <= 1920
                    } ?: choices[0]
            }
        } catch (e: Exception) {
            Log.e(TAG, "解像度取得エラー: ${e.message}")
        }
        return Size(1080, 1440)
    }

    private fun createPreviewSession(textureView: TextureView, targetZoomRatio: Float) {
        val texture: SurfaceTexture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(texture)

        try {
            val device = cameraDevice ?: return
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)

                if (targetZoomRatio >= 1.0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val chars = cameraManager.getCameraCharacteristics(device.id)
                    val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    if (zoomRange != null && targetZoomRatio in zoomRange.lower..zoomRange.upper) {
                        set(CaptureRequest.CONTROL_ZOOM_RATIO, targetZoomRatio)
                    }
                }
            }

            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(
                                requestBuilder.build(),
                                null,
                                backgroundHandler ?: Handler(Looper.getMainLooper())
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "プレビューリクエスト失敗: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "セッション設定失敗")
                    }
                },
                backgroundHandler ?: Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            Log.e(TAG, "プレビュー作成エラー: ${e.message}")
        }
    }

    fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        stopBackgroundThread()
    }
}
