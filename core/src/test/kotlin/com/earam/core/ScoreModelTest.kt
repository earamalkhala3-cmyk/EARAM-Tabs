package com.earam.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoreModelTest {
    @Test
    fun supportsProfessionalStringedInstrumentRange() {
        assertEquals(3, EaramCapabilities.MIN_STRINGS)
        assertEquals(10, EaramCapabilities.MAX_STRINGS)
        assertTrue(Instrument.entries.contains(Instrument.ELECTRIC_GUITAR))
        assertTrue(Instrument.entries.contains(Instrument.DRUMS))
        assertTrue(Technique.entries.contains(Technique.HAMMER_ON))
        assertTrue(Technique.entries.contains(Technique.WHAMMY))
    }

    @Test
    fun supportsMultiVoiceScoreStructure() {
        val score = Score(
            tempo = 145.0,
            timeSignature = TimeSignature(7, 8),
            tracks = listOf(
                Track(
                    id = "guitar",
                    name = "Lead Guitar",
                    instrument = Instrument.ELECTRIC_GUITAR,
                    tuning = Tuning(listOf(40, 45, 50, 55, 59, 64, 69), "7-string")
                )
            )
        )
        assertEquals(145.0, score.tempo)
        assertEquals(7, score.timeSignature.numerator)
        assertEquals(7, score.tracks.first().tuning!!.strings.size)
    }
}
