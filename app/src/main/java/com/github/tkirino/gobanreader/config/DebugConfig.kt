package com.github.tkirino.gobanreader.config

import com.github.tkirino.gobanreader.BuildConfig

object DebugConfig {
    // リリースビルド時は強制的にfalse（R8により最適化・削除対象となる）
    val isEnabled: Boolean get() = BuildConfig.DEBUG

    // 碁盤罫線の角（コーナー）検出のYOLO用教師データ
    const val YOLO_TRAINING_DATA_EXPORT = true

    // 碁石検出CNN用教師データ収集ルーチン
    // Oldは前の40ｘ40の画像セットをエクスポートする
    const val CNN_TRAINING_DATA_EXPORT_OLD = false
    const val CNN_TRAINING_DATA_EXPORT = true

    // 解析ごとの全交点推論スコア（確率評価値）をCSV出力するフラグ
    const val EXPORT_INFERENCE_SCORES_CSV = false
}