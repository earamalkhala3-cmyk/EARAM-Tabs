package com.earam.tabs.music

data class GuitarTuning(val midiByString: List<Int>) { init { require(midiByString.size in 1..8) } companion object { val standard = GuitarTuning(listOf(40,45,50,55,59,64)) } }
data class TabPosition(val string: Int, val fret: Int)

object TabGenerator {
    fun findPosition(midi: Int, tuning: GuitarTuning, maxFret: Int = 36): TabPosition? {
        tuning.midiByString.withIndex().forEach { (i, open) -> val fret=midi-open; if (fret in 0..maxFret) return TabPosition(i,fret) }
        return null
    }
}
