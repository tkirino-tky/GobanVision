package com.github.tkirino.gobanreader.model

data class GameRecord(
    val boardSize: Int = 19,
    val komi: Float = 6.5f,
    val handicap: Int = 0,
    val blackPlayer: String = "",
    val whitePlayer: String = "",
    val gameResult: String = "",
    // 【追加】初期配置（手順なしの石）を保持するプロパティ
    val nextPlayer: String = "B", // "B" または "W"、指定がなければ空文字
    val initialBlackStones: List<Pair<Int, Int>> = emptyList(),
    val initialWhiteStones: List<Pair<Int, Int>> = emptyList(),
    val moveHistory: List<Move> = emptyList()
)
