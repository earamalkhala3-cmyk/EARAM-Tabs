package com.earam.tabs

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.earam.tabs.music.MusicalTimeline
import com.earam.tabs.music.Tempo
import com.earam.tabs.music.TimeSignature

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val timeline = MusicalTimeline(Tempo(120.0), TimeSignature(4,4))
        val view = TextView(this)
        view.text = "EARAM Tabs\n\nTempo: 120 BPM\nTime: 4/4\nPPQ: 960\n\nMaster musical timeline ready."
        view.textSize = 20f
        view.setPadding(32, 64, 32, 32)
        setContentView(view)
    }
}
