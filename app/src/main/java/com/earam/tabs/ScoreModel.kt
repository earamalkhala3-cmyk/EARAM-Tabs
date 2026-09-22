package com.earam.tabs

import com.earam.tabs.music.StrokeDirection

enum class NoteDuration(val ticks: Int) {
    WHOLE(3840), HALF(1920), QUARTER(960), EIGHTH(480), SIXTEENTH(240), THIRTY_SECOND(120)
}

enum class Articulation {
    HAMMER_ON, PULL_OFF, SLIDE, BEND, VIBRATO, TREMOLO,
    PALM_MUTE, LET_RING, DEAD_NOTE, GHOST_NOTE, STACCATO,
    ACCENT, HEAVY_ACCENT, HARMONIC, ARTIFICIAL_HARMONIC,
    TAP, SLAP, POP, PICK_SCRAPE, TRILL
}

enum class Clef { TREBLE, BASS, ALTO, TENOR, PERCUSSION }

data class BendPoint(val position: Int, val cents: Int)

data class NoteEvent(
    val midi: Int,
    val string: Int? = null,
    val fret: Int? = null,
    val velocity: Int = 100,
    val duration: NoteDuration = NoteDuration.QUARTER,
    val dotted: Boolean = false,
    val tieStart: Boolean = false,
    val tieStop: Boolean = false,
    val articulations: Set<Articulation> = emptySet(),
    val bend: List<BendPoint> = emptyList(),
    val picking: StrokeDirection? = null
)

data class VoiceBeat(
    val notes: List<NoteEvent> = emptyList(),
    val rest: Boolean = false
)

data class ScoreBeat(
    val startTick: Int,
    val durationTicks: Int = NoteDuration.QUARTER.ticks,
    val voices: List<VoiceBeat> = listOf(VoiceBeat())
)

data class ScoreMeasure(
    val number: Int,
    val numerator: Int = 4,
    val denominator: Int = 4,
    val beats: MutableList<ScoreBeat> = mutableListOf(),
    val repeatStart: Boolean = false,
    val repeatEnd: Boolean = false,
    val alternateEnding: Int? = null
)

data class ScoreTrack(
    val name: String,
    val instrument: String,
    val stringCount: Int = 6,
    val tuningMidi: List<Int> = listOf(64, 59, 55, 50, 45, 40),
    val clef: Clef = Clef.TREBLE,
    val measures: MutableList<ScoreMeasure> = mutableListOf(),
    var volume: Int = 100,
    var pan: Int = 0,
    var muted: Boolean = false,
    var solo: Boolean = false
)

data class ScoreProject(
    var name: String = "UNTITLED",
    var bpm: Int = 120,
    var key: String = "C",
    var timeSignature: String = "4/4",
    val tracks: MutableList<ScoreTrack> = mutableListOf()
) {
    fun ensureMeasures(count: Int) {
        tracks.forEach { track ->
            while (track.measures.size < count) {
                val n = track.measures.size + 1
                val parts = timeSignature.split("/")
                track.measures += ScoreMeasure(
                    number = n,
                    numerator = parts.getOrNull(0)?.toIntOrNull() ?: 4,
                    denominator = parts.getOrNull(1)?.toIntOrNull() ?: 4
                )
            }
        }
    }
}
