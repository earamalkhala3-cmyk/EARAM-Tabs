package com.earam.core

/**
 * Earam's platform-neutral score model.
 *
 * This is the foundation for the full professional editor: one score model
 * can later be rendered by Android, desktop, notation, TAB, piano-roll and
 * audio/MIDI engines without duplicating musical data.
 */
data class Score(
    val title: String = "Untitled",
    val tempo: Double = 120.0,
    val timeSignature: TimeSignature = TimeSignature(4, 4),
    val keySignature: KeySignature = KeySignature.C,
    val tracks: List<Track> = emptyList()
)

data class TimeSignature(val numerator: Int, val denominator: Int) {
    init {
        require(numerator > 0)
        require(denominator > 0 && denominator and (denominator - 1) == 0)
    }
}

enum class KeySignature { C, G, D, A, E, B, F_SHARP, C_SHARP, F, B_FLAT, E_FLAT, A_FLAT, D_FLAT, G_FLAT }

data class Track(
    val id: String,
    val name: String,
    val instrument: Instrument,
    val tuning: Tuning? = null,
    val capo: Int = 0,
    val voices: List<Voice> = listOf(Voice(1)),
    val volume: Float = 1f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val solo: Boolean = false
)

data class Voice(val number: Int, val measures: List<Measure> = emptyList())

data class Measure(
    val index: Int,
    val beats: List<Beat> = emptyList(),
    val repeatStart: Boolean = false,
    val repeatEnd: Int? = null,
    val alternateEnding: Int? = null
)

data class Beat(
    val duration: Duration,
    val notes: List<Note> = emptyList(),
    val rest: Boolean = false,
    val dynamics: Dynamics = Dynamics.MF,
    val picking: Picking = Picking.NONE
)

data class Note(
    val pitch: Int,
    val string: Int? = null,
    val fret: Int? = null,
    val velocity: Int = 96,
    val techniques: Set<Technique> = emptySet(),
    val bend: Bend? = null,
    val harmonic: Harmonic? = null
)

enum class Duration(val denominator: Int) {
    WHOLE(1), HALF(2), QUARTER(4), EIGHTH(8), SIXTEENTH(16),
    THIRTY_SECOND(32), SIXTY_FOURTH(64)
}

enum class Dynamics { PPP, PP, P, MP, MF, F, FF, FFF }

enum class Picking { NONE, DOWN, UP, ALTERNATE }

enum class Technique {
    HAMMER_ON, PULL_OFF, SLIDE, LEGATO_SLIDE, PALM_MUTE, DEAD_NOTE,
    GHOST_NOTE, LET_RING, VIBRATO, WIDE_VIBRATO, TREMolo,
    TREMOLO_PICKING, TAPPING, TRILL, NATURAL_HARMONIC, ARTIFICIAL_HARMONIC,
    PINCH_HARMONIC, GRACE_NOTE, ACCENT, STACCATO, TENUTO, ARPEGGIO,
    RASGUEADO, PICK_SCRAPE, WHAMMY, SLAP, POP, FINGERSTYLE
}

data class Bend(
    val type: BendType = BendType.FULL,
    val semitones: Double = 2.0
)

enum class BendType { QUARTER, HALF, FULL, ONE_AND_HALF, DOUBLE }

data class Harmonic(val semitones: Int = 12)

data class Tuning(
    val strings: List<Int>,
    val name: String = "Custom"
) {
    init { require(strings.size in 1..10) }
}

enum class Instrument {
    ELECTRIC_GUITAR, ACOUSTIC_GUITAR, CLASSICAL_GUITAR,
    BASS, FRETLESS_BASS, UKULELE, BANJO, MANDOLIN,
    PIANO, KEYS, ORGAN, SYNTH, VIOLIN, VIOLA, CELLO,
    CONTRABASS, TRUMPET, TROMBONE, SAXOPHONE, FLUTE,
    VOICE, DRUMS, PERCUSSION, OTHER
}

object EaramCapabilities {
    const val MAX_STRINGS = 10
    const val MIN_STRINGS = 3
    const val MAX_VOICES_PER_TRACK = 4
    const val MAX_MIDI_PORTS = 16

    val supportedImports = listOf(
        "EARAM", "GP", "GPX", "GP5", "GP4", "GP3", "GTP",
        "MIDI", "MUSICXML", "ASCII", "TABLEDIT", "POWERTAB", "MP3", "WAV"
    )

    val supportedExports = listOf(
        "EARAM", "MIDI", "MUSICXML", "ASCII", "PDF", "PNG", "SVG",
        "MP3", "WAV", "FLAC", "OGG", "AIFF"
    )
}
