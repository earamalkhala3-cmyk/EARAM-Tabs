package com.earam.tabs.music

const val PPQ = 960L

data class Tempo(val bpm: Double) { init { require(bpm > 0.0) } }
data class TimeSignature(val numerator: Int, val denominator: Int) { init { require(numerator > 0 && denominator > 0 && (denominator and (denominator - 1)) == 0) } }
data class Duration(val ticks: Long) { init { require(ticks > 0) } companion object { val Whole=Duration(PPQ*4); val Half=Duration(PPQ*2); val Quarter=Duration(PPQ); val Eighth=Duration(PPQ/2); val Sixteenth=Duration(PPQ/4) } }

data class Event(val startTick: Long, val duration: Duration, val pitch: Int? = null, val rest: Boolean = false, val stroke: Stroke? = null)
enum class Stroke { DOWN, UP }

class MusicalTimeline(var tempo: Tempo = Tempo(120.0), var timeSignature: TimeSignature = TimeSignature(4,4)) {
    val events = mutableListOf<Event>()
    fun add(event: Event) { require(event.startTick >= 0); events += event }
    fun ticksPerBeat(): Long = PPQ * 4 / timeSignature.denominator
    fun ticksPerMeasure(): Long = ticksPerBeat() * timeSignature.numerator
    fun secondsAtTick(tick: Long): Double = tick * 60.0 / (PPQ * tempo.bpm)
    fun measureOf(tick: Long): Long = tick / ticksPerMeasure()
    fun validateMeasures(): Boolean = events.groupBy { measureOf(it.startTick) }.all { (_, es) -> es.all { it.startTick + it.duration.ticks <= (measureOf(it.startTick)+1)*ticksPerMeasure() } }
}

fun midiToFrequency(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
