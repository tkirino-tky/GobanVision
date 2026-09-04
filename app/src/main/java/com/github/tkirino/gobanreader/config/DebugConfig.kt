package com.github.tkirino.gobanreader.config

import com.github.tkirino.gobanreader.BuildConfig

object DebugConfig {
    // リリースビルド時は強制的にfalse（R8により最適化・削除対象となる）
     val isEnabled: Boolean get() = BuildConfig.DEBUG

    // 各機能のON/OFF
    // 教師データ収集ルーチン（必要な時だけここを true にする）
    const val EXPORT_DATASET_PAIR = true
    const val EXPORT_CORNER_IMAGES = false

    // 教師データ（ヒートマップ用） - 必要になるまで普段は false にしておく
    const val EXPORT_CROPPED_RECT_IMAGE = false

    // 新しく追加する回転オーグメンテーション用の原画保存フラグ
    const val EXPORT_ORIGINAL_BOARD_FOR_AUG = true
}
