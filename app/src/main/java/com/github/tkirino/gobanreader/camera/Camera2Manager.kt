package com.github.tkirino.gobanreader.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Arrays

/**
 * Camera2 APIを使用してカメラのオープン、プレビュー表示、写真撮影を制御するクラス
 */
class Camera2Manager(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val backgroundHandler = Handler(Looper.getMainLooper())

    private var currentPhotoFile: File? = null
    private var onImageSavedCallback: ((File) -> Unit)? = null

    companion object {
        private const val TAG = "Camera2Manager"
        private const val PREVIEW_WIDTH = 1080
        private const val PREVIEW_HEIGHT = 1440
    }

    fun openCamera(textureView: TextureView, targetZoomRatio: Float, onOpened: () -> Unit) {
        closeCamera()
        val selectedCameraId = selectBestCameraId(targetZoomRatio)
        Log.d(TAG, "選択されたカメラID: $selectedCameraId (要求倍率: $targetZoomRatio)")

        try {
            cameraManager.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession(textureView, targetZoomRatio, onOpened)
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
            }, backgroundHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "カメラ権限がありません: ${e.message}")
        }
    }

    private fun selectBestCameraId(targetZoomRatio: Float): String {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                // 1.0未満（広角・超広角）を要求された場合、物理カメラから焦点距離の短いものを探す
                if (targetZoomRatio < 1.0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val physicalIds = chars.physicalCameraIds
                    for (pId in physicalIds) {
                        val pChars = cameraManager.getCameraCharacteristics(pId)
                        val focalLengths = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        if (focalLengths != null && focalLengths.any { it < 3.5f }) {
                            return pId // 超広角の物理カメラID
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "カメラID選択中のエラー: ${e.message}")
        }
        return "0" // 標準メインカメラ
    }

    private fun createCameraPreviewSession(textureView: TextureView, targetZoomRatio: Float, onOpened: () -> Unit) {
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
        val previewSurface = Surface(texture)

        imageReader = ImageReader.newInstance(PREVIEW_WIDTH, PREVIEW_HEIGHT, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                image?.let {
                    val buffer: ByteBuffer = it.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    it.close()

                    currentPhotoFile?.let { file ->
                        try {
                            FileOutputStream(file).use { output ->
                                output.write(bytes)
                            }
                            backgroundHandler.post {
                                onImageSavedCallback?.invoke(file)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "画像保存失敗: ${e.message}")
                        }
                    }
                }
            }, backgroundHandler)
        }

        val captureSurface = imageReader?.surface ?: return

        try {
            val device = cameraDevice ?: return
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)

                // 1.0x以上でデジタルズーム対応端末の場合
                if (targetZoomRatio >= 1.0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val chars = cameraManager.getCameraCharacteristics(device.id)
                    val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    if (zoomRange != null && targetZoomRatio in zoomRange.lower..zoomRange.upper) {
                        set(CaptureRequest.CONTROL_ZOOM_RATIO, targetZoomRatio)
                    }
                }
            }

            device.createCaptureSession(
                Arrays.asList(previewSurface, captureSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                            onOpened()
                        } catch (e: Exception) {
                            Log.e(TAG, "プレビューリクエスト失敗: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "カメラセッション設定失敗")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "プレビューセッション作成エラー: ${e.message}")
        }
    }

    fun takePicture(outputFile: File, onSaved: (File) -> Unit) {
        currentPhotoFile = outputFile
        onImageSavedCallback = onSaved

        try {
            val device = cameraDevice ?: return
            val captureSurface = imageReader?.surface ?: return
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(captureSurface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                // 縦持ち撮影時に画像が90度回転してしまう現象を防ぐため、JPEGの向きを90度に固定
                set(CaptureRequest.JPEG_ORIENTATION, 90)
            }

            captureSession?.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "静止画撮影エラー: ${e.message}")
        }
    }

    fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
    }
}
