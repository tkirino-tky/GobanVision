package com.github.tkirino.gobanreader.model

enum class StoneColor {
    EMPTY, BLACK, WHITE
}

data class Move(
    val color: StoneColor,
    val x: Int,
    val y: Int,
    val moveNumber: Int
) {
    // 先ほどの SgfWriter で「isBlack」を使っていた場合、以下のような便利なプロパティを足しておくとスムーズです
    val isBlack: Boolean get() = color == StoneColor.BLACK
}