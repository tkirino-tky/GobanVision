package com.github.tkirino.gobanreader.config

import com.github.tkirino.gobanreader.BuildConfig

object DebugConfig {
    // リリースビルド時は強制的にfalse（R8により最適化・削除対象となる）
    val isEnabled: Boolean get() = BuildConfig.DEBUG

    // 碁盤罫線の角（コーナー）検出のYOLO用教師データ
    const val YOLO_TRAINING_DATA_EXPORT = false

    // 碁石検出CNN用教師データ収集ルーチン（60x60px）
    const val CNN_TRAINING_DATA_EXPORT = false

    // 解析ごとの全交点推論スコア（確率評価値）をCSV出力するフラグ
    const val EXPORT_INFERENCE_SCORES_CSV = false
}
