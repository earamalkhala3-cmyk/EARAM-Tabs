package com.earam.tabs.music

import kotlin.test.Test
import kotlin.test.assertEquals

class PickingTest {
    @Test fun alternateDownStartsDown() {
        assertEquals(listOf(StrokeDirection.DOWN, StrokeDirection.UP, StrokeDirection.DOWN, StrokeDirection.UP), PickingEngine.alternate(4))
    }

    @Test fun alternateUpStartsUp() {
        assertEquals(listOf(StrokeDirection.UP, StrokeDirection.DOWN, StrokeDirection.UP), PickingEngine.alternate(3, StrokeDirection.UP))
    }

    @Test fun strumPatternRepeats() {
        val pattern = StrumPattern.fromText("↓ ↓ ↑ ↑ ↓ ↑")
        assertEquals(listOf(StrokeDirection.DOWN, StrokeDirection.DOWN, StrokeDirection.UP, StrokeDirection.UP, StrokeDirection.DOWN, StrokeDirection.UP, StrokeDirection.DOWN), PickingEngine.applyPattern(pattern, 7))
    }
}
