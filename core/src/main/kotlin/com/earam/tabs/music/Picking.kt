package com.earam.tabs.music

enum class PickingMode { MANUAL, ALTERNATE, STRUM }

enum class StrokeDirection { DOWN, UP }

data class StrumPattern(val directions: List<StrokeDirection>) {
    init { require(directions.isNotEmpty()) }

    companion object {
        fun fromText(text: String): StrumPattern {
            val tokens = text.trim().split(Regex("\\s+"))
            val directions = tokens.map { token ->
                when (token) {
                    "↓", "D", "d", "DOWN" -> StrokeDirection.DOWN
                    "↑", "U", "u", "UP" -> StrokeDirection.UP
                    else -> error("Unsupported strum direction: $token")
                }
            }
            return StrumPattern(directions)
        }
    }

    fun at(index: Int): StrokeDirection = directions[index.mod(directions.size)]
}

object PickingEngine {
    fun alternate(count: Int, first: StrokeDirection = StrokeDirection.DOWN): List<StrokeDirection> {
        require(count >= 0)
        return List(count) { index ->
            if (index % 2 == 0) first else if (first == StrokeDirection.DOWN) StrokeDirection.UP else StrokeDirection.DOWN
        }
    }

    fun applyPattern(pattern: StrumPattern, count: Int): List<StrokeDirection> {
        require(count >= 0)
        return List(count) { pattern.at(it) }
    }
}
