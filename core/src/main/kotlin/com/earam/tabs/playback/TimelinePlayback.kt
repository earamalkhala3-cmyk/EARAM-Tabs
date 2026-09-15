package com.earam.tabs.playback

import com.earam.tabs.music.MusicalTimeline

/** Converts musical ticks to absolute audio deadlines. UI clocks never drive musical time. */
class TimelinePlayback(private val timeline: MusicalTimeline) {
    fun deadlineSeconds(tick: Long): Double = timeline.secondsAtTick(tick)
    fun durationSeconds(ticks: Long): Double = timeline.secondsAtTick(ticks) - timeline.secondsAtTick(0)
}
