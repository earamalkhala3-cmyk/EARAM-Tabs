@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package com.earam.tabs

import androidx.lifecycle.ViewModel
import alphaTab.model.Score

/**
 * Holds the editor session independently from Activity instances.
 *
 * Rotation recreates MainActivity, but the musical Score, source URI and
 * playback/view state remain here so the new AlphaTabView can bind to the
 * same session instead of creating a new empty score.
 */
class EditorSessionViewModel : ViewModel() {
    var score: Score? = null

    var sourceUri: String? = null
    var sourceName: String? = null

    var projectName: String = "Music Home"
    var bpm: Int = 120
    var timeSignature: String = "4/4"

    var zoom: Double = 0.72
    var playbackSpeed: Double = 1.0
    var tickPosition: Double = 0.0
    var wasPlaying: Boolean = false

    var selectedTrack: Int = 0
    var selectedVoice: Int = 0
    var selectedBar: Int = 0
    var selectedBeat: Int = 0
    var selectedString: Int = 1
}
