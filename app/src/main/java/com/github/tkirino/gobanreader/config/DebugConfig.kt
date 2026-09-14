package com.github.tkirino.gobanreader.config

import com.github.tkirino.gobanreader.BuildConfig

object DebugConfig {
    // リリースビルド時は強制的にfalse（R8により最適化・削除対象となる）
     val isEnabled: Boolean get() = BuildConfig.DEBUG

    // 各機能のON/OFF
    // 碁石検出CNN用教師データ収集ルーチン
    const val EXPORT_DATASET_PAIR = true
    // 碁盤罫線の角（コーナー）の画像を出力（確認のため）
    const val EXPORT_CORNER_IMAGES = false

    // 碁盤の角検出の教師データ（640x640にresizeされている）
    const val EXPORT_CROPPED_RECT_IMAGE = false

    // 碁盤罫線の角（コーナー）検出のYOLO用教師データ（ガイドフレームで切り取ったもの.
    // resizeされていない　
    // カメラのレンズ　1.0xでは 1584x1648、0.8xでは 860x894
    const val EXPORT_ORIGINAL_BOARD_FOR_AUG = true
}
