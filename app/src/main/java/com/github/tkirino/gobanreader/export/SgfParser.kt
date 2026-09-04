package com.github.tkirino.gobanreader.export

import com.github.tkirino.gobanreader.model.GameRecord
import com.github.tkirino.gobanreader.model.Move
import com.github.tkirino.gobanreader.model.StoneColor

class SgfParser {

    /**
     * SGFテキストを解析し、GameRecordオブジェクトに変換して返す
     */
    fun parse(sgfText: String): GameRecord {
        val cleanText = sgfText.replace("\n", "").replace("\r", "")

        val blackPlayer = extractTag(cleanText, "PB")
        val whitePlayer = extractTag(cleanText, "PW")
        val gameResult = extractTag(cleanText, "RE")
        val komi = extractTag(cleanText, "KM").toFloatOrNull() ?: 6.5f
        val handicap = extractTag(cleanText, "HA").toIntOrNull() ?: 0
        val boardSize = extractTag(cleanText, "SZ").toIntOrNull() ?: 19

        // 【新設】AB[pd][qp] や AW[cd][dd] から初期配置の座標リストを抽出
        val initialBlackStones = extractMultiValues(cleanText, "AB")
        val initialWhiteStones = extractMultiValues(cleanText, "AW")

        val moves = mutableListOf<Move>()
        val moveRegex = """;([BW])\[([a-s]{2})\]""".toRegex()
        val matches = moveRegex.findAll(cleanText)

        for ((index, match) in matches.withIndex()) {
            val colorStr = match.groupValues[1]       // "B" または "W"
            val coordStr = match.groupValues[2]       // "aa" 〜 "ss"

            val color = if (colorStr == "B") StoneColor.BLACK else StoneColor.WHITE
            val (x, y) = convertFromSgfCoordinate(coordStr)

            moves.add(
                Move(
                    color = color,
                    x = x,
                    y = y,
                    moveNumber = index + 1
                )
            )
        }

        return GameRecord(
            boardSize = boardSize,
            komi = komi,
            handicap = handicap,
            blackPlayer = blackPlayer,
            whitePlayer = whitePlayer,
            gameResult = gameResult,
            initialBlackStones = initialBlackStones, // 【追加】
            initialWhiteStones = initialWhiteStones, // 【追加】
            moveHistory = moves
        )
    }

    private fun extractTag(text: String, tag: String): String {
        val regex = """$tag\[(.*?)\]""".toRegex()
        val match = regex.find(text)
        return match?.groupValues?.get(1) ?: ""
    }

    /**
     * AB[pd][qp] などのように、同一タグに連続する複数の値を解析して、座標のリストを返す
     */
    private fun extractMultiValues(text: String, tag: String): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        // タグの後に [xx] が連続する部分を捉える正規表現
        val tagRegex = """$tag((?:\[[a-s]{2}\])+)(?=[A-Z;]|\)|$)""".toRegex()
        val tagMatch = tagRegex.find(text)

        if (tagMatch != null) {
            val bracketsText = tagMatch.groupValues[1] // "[pd][qp]" のような文字列
            val coordRegex = """\[([a-s]{2})\]""".toRegex()
            val coordMatches = coordRegex.findAll(bracketsText)

            for (match in coordMatches) {
                val coordStr = match.groupValues[1]
                result.add(convertFromSgfCoordinate(coordStr))
            }
        }
        return result
    }

    private fun convertFromSgfCoordinate(coord: String): Pair<Int, Int> {
        if (coord.length < 2) return Pair(-1, -1)
        val x = coord[0].code - 'a'.code
        val y = coord[1].code - 'a'.code
        return Pair(x, y)
    }
}
