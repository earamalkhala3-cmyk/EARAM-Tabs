package com.earam.tabs.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicalTimelineTest {
    @Test fun quarterAt120IsHalfSecond() { assertEquals(0.5, MusicalTimeline(Tempo(120.0), TimeSignature(4,4)).secondsAtTick(PPQ), 1e-12) }
    @Test fun measureValidation() { val t=MusicalTimeline(); t.add(Event(0, Duration.Whole)); assertTrue(t.validateMeasures()) }
    @Test fun eightStringLimit() { assertEquals(8, GuitarTuning(List(8){40}).midiByString.size) }
}
