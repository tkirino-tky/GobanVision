package com.github.tkirino.gobanreader.camera

class CameraZoomManager {
    var defaultZoomRatio: Float = 1.0f

    companion object {
        val SUPPORTED_ZOOMS = listOf(0.6f, 1.0f, 2.0f)
    }
}
