@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package com.earam.tabs

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import alphaTab.AlphaTabView
import alphaTab.LayoutMode
import alphaTab.PlayerMode
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Bar
import alphaTab.model.Beat
import alphaTab.model.Duration
import alphaTab.model.MasterBar
import alphaTab.model.Note
import alphaTab.model.Score
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private var projectName = "Untitled"
    private var instrument = "Guitar"
    private var bpm = 120
    private var timeSig = "4/4"

    /**
     * PHASE 1 SOURCE OF TRUTH
     *
     * There is exactly one musical model in the UI: alphaTab.model.Score.
     * Imported GP3/4/5/GPX bytes are parsed directly by ScoreLoader into this Score.
     * AlphaTab renders that same Score through renderScore().
     *
     * The application has no second music representation and no custom score renderer.
     */
    private var currentScore: Score? = null
    private var alphaTabView: AlphaTabView? = null
    private var noteEditor: AlphaTabNoteEditor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openEditor()
    }

    override fun onDestroy() {
        try { alphaTabView?.api?.stop() } catch (_: Throwable) { }
        alphaTabView = null
        noteEditor = null
        currentScore = null
        super.onDestroy()
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun openEditor() {
        showAlphaTabEditor()
    }

    private fun showAlphaTabEditor() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        val title = TextView(this).apply {
            text = projectName
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(12f), 0)
            setBackgroundColor(0xFF191C1F.toInt())
        }

        val status = TextView(this).apply {
            text = "Open a GP3 / GP4 / GP5 / GPX file to load its Score."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(12f), 0)
            setBackgroundColor(0xFF25292D.toInt())
        }

        val controlsScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF202428.toInt())
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(3f), dp(4f), dp(3f))
        }

        fun control(label: String): Button = Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10f), 0, dp(10f), 0)
        }

        val file = control("FILE")
        val play = control("PLAY")
        val stop = control("STOP")
        val speed05 = control("0.5×")
        val speed075 = control("0.75×")
        val speed1 = control("1×")
        val speed125 = control("1.25×")
        val speed15 = control("1.5×")

        listOf(file, play, stop, speed05, speed075, speed1, speed125, speed15).forEach {
            controls.addView(it, LinearLayout.LayoutParams(dp(82f), dp(46f)))
        }
        controlsScroll.addView(controls, LinearLayout.LayoutParams(-2, dp(52f)))

        val score = AlphaTabView(this, null).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            settings.display.layoutMode = LayoutMode.Page
            settings.display.barsPerRow = 2.0
            settings.display.barCount = -1.0
            settings.display.startBar = 1.0
            settings.display.scale = 0.72
            settings.display.stretchForce = 0.0
            settings.core.includeNoteBounds = true
            settings.player.playerMode = PlayerMode.EnabledSynthesizer
            settings.player.enablePlayer = true
            settings.player.enableUserInteraction = true
            settings.player.enableCursor = true
            settings.player.enableElementHighlighting = true
            api.updateSettings()
        }

        alphaTabView = score

        val editor = AlphaTabNoteEditor(this, score, status)
        noteEditor = editor
        editor.attach()

        file.setOnClickListener { showFileMenu() }

        play.setOnClickListener {
            if (score.api.isReadyForPlayback) score.api.playPause()
            else status.text = "Playback is not ready for this Score."
        }
        stop.setOnClickListener { score.api.stop() }

        fun setSpeed(value: Double) {
            score.api.playbackSpeed = value
            status.text = "Playback speed • " +
                String.format(java.util.Locale.US, "%.0f%%", value * 100.0)
        }
        speed05.setOnClickListener { setSpeed(0.50) }
        speed075.setOnClickListener { setSpeed(0.75) }
        speed1.setOnClickListener { setSpeed(1.00) }
        speed125.setOnClickListener { setSpeed(1.25) }
        speed15.setOnClickListener { setSpeed(1.50) }

        score.api.scoreLoaded.on { loaded ->
            runOnUiThread {
                currentScore = loaded
                projectName = loaded.title.ifBlank { projectName }
                title.text = projectName
                status.text = "Score loaded • AlphaTab renderer"
            }
        }

        score.api.error.on { error ->
            runOnUiThread {
                status.text = "AlphaTab error: " + (error.message ?: "unknown")
            }
        }

        score.api.playerReady.on {
            runOnUiThread {
                status.text = "Sound ready • " + bpm + " BPM"
                play.isEnabled = true
            }
        }

        score.api.playerStateChanged.on {
            runOnUiThread {
                play.text =
                    if (score.api.playerState.toString().contains("Playing", true)) "PAUSE"
                    else "PLAY"
                if (score.api.isReadyForPlayback) score.api.scrollToCursor()
            }
        }

        root.addView(title, LinearLayout.LayoutParams(-1, dp(34f)))
        root.addView(status, LinearLayout.LayoutParams(-1, dp(30f)))
        root.addView(controlsScroll, LinearLayout.LayoutParams(-1, dp(52f)))
        root.addView(score, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        play.isEnabled = false
    }

    private fun showFileMenu() {
        val items = arrayOf(
            "New File",
            "Open File",
            "Save File",
            "Save File As…",
            "Import TAB",
            "Export File",
            "Home / Close",
            "Check for updates"
        )
        AlertDialog.Builder(this)
            .setTitle("FILE")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> newScore()
                    1, 4 -> importTab()
                    2, 3 -> Toast.makeText(this, "Score persistence will be added on the same AlphaTab Score model.", Toast.LENGTH_SHORT).show()
                    5 -> Toast.makeText(this, "Export will use the AlphaTab Score model.", Toast.LENGTH_SHORT).show()
                    6 -> finish()
                    7 -> Toast.makeText(this, "Update check is not part of Phase 1.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /**
     * Creates a blank Score directly. This is still the same AlphaTab model used by
     * rendering and editing; there is no textual intermediate representation.
     */
    private fun newScore() {
        try {
            val score = Score()
            val master = MasterBar().apply {
                timeSignatureNumerator = 4.0
                timeSignatureDenominator = 4.0
            }
            score.addMasterBar(master)

            val track = alphaTab.model.Track()
            val staff = alphaTab.model.Staff()
            track.addStaff(staff)
            score.addTrack(track)

            val bar = Bar()
            staff.addBar(bar)
            val voice = alphaTab.model.Voice()
            bar.addVoice(voice)
            repeat(4) {
                voice.addBeat(Beat().apply { duration = Duration.Quarter })
            }

            score.finish(alphaTabView?.settings ?: return)
            currentScore = score
            alphaTabView?.api?.renderScore(score)
            projectName = "Untitled"
            noteEditor?.resetSelection()
        } catch (t: Throwable) {
            showImportError("New Score", t)
        }
    }

    private fun importTab() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, 4107)
    }

    private fun importScore(uri: Uri, fileName: String) {
        val view = alphaTabView ?: throw IllegalStateException("AlphaTab view is not initialized")
        val inputBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot read selected file")
        if (inputBytes.isEmpty()) throw IllegalStateException("Selected TAB file is empty")

        val parsed = ScoreLoader.loadScoreFromBytes(
            Uint8Array(inputBytes.asUByteArray()),
            view.settings
        )

        val firstTrack = parsed.tracks.firstOrNull()
            ?: throw IllegalStateException("The imported file contains no tracks")

        // CRITICAL PHASE 1 PATH:
        // raw GP bytes -> ScoreLoader -> parsed Score -> AlphaTab renderScore(parsed)
        // The imported Score is rendered directly; no textual music representation is created.
        currentScore = parsed
        projectName = parsed.title.ifBlank { fileName.substringBeforeLast('.') }

        runOnUiThread {
            view.api.renderScore(parsed)
            noteEditor?.resetSelection()
        }

        loadSoundFontForCurrentScore(view)
    }

    private fun loadSoundFontForCurrentScore(view: AlphaTabView) {
        Thread {
            try {
                val sfUrl =
                    "https://cdn.jsdelivr.net/npm/@coderline/alphatab@1.8.4/dist/soundfont/sonivox.sf2"
                val connection = (URL(sfUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/octet-stream")
                }
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code while loading AlphaTab 1.8.4 SoundFont")
                }
                val sf = connection.inputStream.use { it.readBytes() }
                connection.disconnect()

                runOnUiThread {
                    try {
                        val accepted = view.api.loadSoundFont(sf, false)
                        if (!accepted) throw IllegalStateException("AlphaTab rejected the SoundFont")
                        view.api.loadMidiForScore()
                    } catch (t: Throwable) {
                        showImportError("SoundFont", t)
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread { showImportError("SoundFont", t) }
            }
        }.start()
    }

    private fun showImportError(stage: String, error: Throwable) {
        AlertDialog.Builder(this)
            .setTitle("$stage failed")
            .setMessage(error.message ?: error.javaClass.name)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 4107 || resultCode != RESULT_OK || data?.data == null) return

        val uri = data.data!!
        val name = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "IMPORT TAB"

        val statusMessage = "Importing $name directly into AlphaTab Score…"
        Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show()

        Thread {
            try {
                importScore(uri, name)
            } catch (t: Throwable) {
                runOnUiThread { showImportError("TAB import", t) }
            }
        }.start()
    }

    private object AlphaTabRhythmEngine {
        const val QUARTER_TICKS = 960L
        fun durationTicks(duration: Duration): Long = when (duration) {
            Duration.QuadrupleWhole -> QUARTER_TICKS * 16
            Duration.DoubleWhole -> QUARTER_TICKS * 8
            Duration.Whole -> QUARTER_TICKS * 4
            Duration.Half -> QUARTER_TICKS * 2
            Duration.Quarter -> QUARTER_TICKS
            Duration.Eighth -> QUARTER_TICKS / 2
            Duration.Sixteenth -> QUARTER_TICKS / 4
            Duration.ThirtySecond -> QUARTER_TICKS / 8
            Duration.SixtyFourth -> QUARTER_TICKS / 16
            Duration.OneHundredTwentyEighth -> QUARTER_TICKS / 32
            Duration.TwoHundredFiftySixth -> QUARTER_TICKS / 64
        }
        fun beatTicks(beat: Beat): Long {
            var ticks = durationTicks(beat.duration)
            when (beat.dots.toInt()) { 1 -> ticks += ticks / 2; 2 -> ticks += (ticks / 4) * 3 }
            if (beat.tupletNumerator >= 0 && beat.tupletDenominator > 0) ticks = (ticks * beat.tupletDenominator.toLong()) / beat.tupletNumerator.toLong()
            return ticks.coerceAtLeast(1L)
        }
        fun barCapacityTicks(bar: Bar): Long {
            val m = bar.masterBar
            return (m.timeSignatureNumerator.toLong().coerceAtLeast(1L) * QUARTER_TICKS * 4L) / m.timeSignatureDenominator.toLong().coerceAtLeast(1L)
        }
        fun barUsedTicks(bar: Bar, excluding: Beat? = null): Long = bar.voices.firstOrNull()?.beats?.toList()?.filter { it !== excluding }?.sumOf { beatTicks(it) } ?: 0L
        fun remainingTicks(bar: Bar, excluding: Beat? = null): Long = (barCapacityTicks(bar) - barUsedTicks(bar, excluding)).coerceAtLeast(0L)
        fun candidateTicks(duration: Duration, dots: Int, tupletNumerator: Int, tupletDenominator: Int): Long {
            var ticks = durationTicks(duration)
            when (dots.coerceIn(0, 2)) { 1 -> ticks += ticks / 2; 2 -> ticks += (ticks / 4) * 3 }
            if (tupletNumerator >= 0 && tupletDenominator > 0) ticks = (ticks * tupletDenominator.toLong()) / tupletNumerator.toLong()
            return ticks.coerceAtLeast(1L)
        }
        fun fits(bar: Bar, beat: Beat, duration: Duration, dots: Int, tupletNumerator: Int, tupletDenominator: Int): Boolean = candidateTicks(duration, dots, tupletNumerator, tupletDenominator) <= remainingTicks(bar, beat)
        fun apply(beat: Beat, duration: Duration, dots: Int = 0, tupletNumerator: Int = -1, tupletDenominator: Int = -1) {
            beat.duration = duration; beat.dots = dots.coerceIn(0, 2).toDouble(); beat.tupletNumerator = tupletNumerator.toDouble(); beat.tupletDenominator = tupletDenominator.toDouble()
        }
        fun nextBeat(bar: Bar, beat: Beat): Beat? {
            val beats = bar.voices.firstOrNull()?.beats?.toList() ?: return null
            val i = beats.indexOf(beat)
            return if (i >= 0 && i + 1 < beats.size) beats[i + 1] else null
        }
    }

    /** Phase 3: deterministic keyboard navigation over the real AlphaTab Score. */
    private class AlphaTabNoteEditor(
        private val activity: MainActivity,
        private val score: AlphaTabView,
        private val status: TextView
    ) {
        var currentBarIndex: Int = 0
            private set
        var currentBeatIndex: Int = 0
            private set
        var currentStringIndex: Int = 1
            private set

        fun resetSelection() {
            currentBarIndex = 0
            currentBeatIndex = 0
            currentStringIndex = 1
            armed = false
            pendingFret = ""
            updateCursor()
            updateStatus()
        }


        private var armed = false
        private var pendingFret: String = ""
        private var pendingAtMs: Long = 0L

        fun attach() {
            score.isFocusable = true
            score.isFocusableInTouchMode = true

            val keyHandler: (View, Int, android.view.KeyEvent) -> Boolean = { _, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN || event.repeatCount > 0) false
                else when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { moveBeat(-1); true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { moveBeat(1); true }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> { moveString(-1); true }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { moveString(1); true }
                    android.view.KeyEvent.KEYCODE_HOME -> { moveToEdge(false); true }
                    android.view.KeyEvent.KEYCODE_MOVE_END -> { moveToEdge(true); true }
                    android.view.KeyEvent.KEYCODE_DEL,
                    android.view.KeyEvent.KEYCODE_FORWARD_DEL -> { deleteCurrentNote(); true }
                    else -> {
                        val n = event.unicodeChar
                        if (n in '0'.code..'9'.code) { acceptDigit(n - '0'.code); true } else false
                    }
                }
            }

            score.setOnKeyListener(keyHandler)
            score.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) updateStatus() }

            score.api.noteMouseDown.on { note ->
                val track = score.api.score?.tracks?.firstOrNull() ?: return@on
                val staff = track.staves.firstOrNull() ?: return@on
                val barList = staff.bars.toList()
                for (bi in barList.indices) {
                    val beats = barList[bi].voices.firstOrNull()?.beats?.toList() ?: continue
                    val index = beats.indexOf(note.beat)
                    if (index >= 0) {
                        currentBarIndex = bi
                        currentBeatIndex = index
                        currentStringIndex = note.string.toInt().coerceIn(1, maxStringIndex())
                        armed = true
                        pendingFret = ""
                        updateCursor()
                        score.requestFocus()
                        break
                    }
                }
            }

            score.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    armed = true
                    score.requestFocus()
                }
                false
            }
        }

        private fun bars(): List<alphaTab.model.Bar>? =
            score.api.score?.tracks?.firstOrNull()?.staves?.firstOrNull()?.bars?.toList()

        private fun currentBeat(): alphaTab.model.Beat? =
            bars()?.getOrNull(currentBarIndex)?.voices?.firstOrNull()?.beats?.toList()?.getOrNull(currentBeatIndex)

        private fun maxStringIndex(): Int =
            score.api.score?.tracks?.firstOrNull()?.staves?.firstOrNull()?.tuning?.toList()?.size?.coerceAtLeast(1) ?: 6

        fun currentBeatDurationTicks(): Long = currentBeat()?.let { AlphaTabRhythmEngine.beatTicks(it) } ?: 0L
        fun currentBarCapacityTicks(): Long = bars()?.getOrNull(currentBarIndex)?.let { AlphaTabRhythmEngine.barCapacityTicks(it) } ?: 0L
        fun currentBarUsedTicks(): Long = bars()?.getOrNull(currentBarIndex)?.let { AlphaTabRhythmEngine.barUsedTicks(it) } ?: 0L

        fun setCurrentDuration(duration: Duration, dots: Int = 0, tupletNumerator: Int = -1, tupletDenominator: Int = -1): Boolean {
            val beat = currentBeat() ?: return false
            val bar = bars()?.getOrNull(currentBarIndex) ?: return false
            if (!AlphaTabRhythmEngine.fits(bar, beat, duration, dots, tupletNumerator, tupletDenominator)) {
                updateStatus("Duration does not fit • " + bar.masterBar.timeSignatureNumerator.toInt() + "/" + bar.masterBar.timeSignatureDenominator.toInt())
                return false
            }
            AlphaTabRhythmEngine.apply(beat, duration, dots, tupletNumerator, tupletDenominator)
            beat.finish(score.settings, null)
            score.api.score?.finish(score.settings)
            score.api.render()
            updateCursor()
            updateStatus("Duration " + duration.name)
            return true
        }

        private fun createNextMeasure(): Boolean {
            val song = score.api.score ?: return false
            val sourceBar = bars()?.lastOrNull() ?: return false
            val master = MasterBar().apply {
                timeSignatureNumerator = sourceBar.masterBar.timeSignatureNumerator
                timeSignatureDenominator = sourceBar.masterBar.timeSignatureDenominator
                timeSignatureCommon = sourceBar.masterBar.timeSignatureCommon
                keySignature = sourceBar.masterBar.keySignature
                keySignatureType = sourceBar.masterBar.keySignatureType
            }
            song.addMasterBar(master)
            for (track in song.tracks.toList()) for (sourceStaff in track.staves.toList()) {
                val newBar = Bar()
                sourceStaff.addBar(newBar)
                val voiceCount = sourceBar.voices.toList().size.coerceAtLeast(1)
                repeat(voiceCount) {
                    val voice = alphaTab.model.Voice()
                    newBar.addVoice(voice)
                    val numerator = master.timeSignatureNumerator.toInt().coerceAtLeast(1)
                    val denominator = master.timeSignatureDenominator.toInt().coerceAtLeast(1)
                    val unit = when (denominator) {
                        1 -> Duration.DoubleWhole
                        2 -> Duration.Half
                        4 -> Duration.Quarter
                        8 -> Duration.Eighth
                        16 -> Duration.Sixteenth
                        else -> Duration.Quarter
                    }
                    repeat(numerator) { voice.addBeat(Beat().apply { duration = unit }) }
                }
            }
            song.finish(score.settings)
            score.api.render()
            return true
        }

        private fun advanceAfterEntry() {
            val bs = bars() ?: return
            val bar = bs.getOrNull(currentBarIndex) ?: return
            val beat = currentBeat() ?: return
            if (AlphaTabRhythmEngine.nextBeat(bar, beat) != null) {
                currentBeatIndex++
                updateCursor()
                updateStatus()
                return
            }
            if (currentBarIndex == bs.lastIndex) {
                if (createNextMeasure()) {
                    currentBarIndex++
                    currentBeatIndex = 0
                    updateCursor()
                    updateStatus("New measure • " + (currentBarIndex + 1))
                }
            } else {
                currentBarIndex++
                currentBeatIndex = 0
                updateCursor()
                updateStatus()
            }
        }

        /** Arrow navigation never creates a measure. It only moves inside existing Score beats. */
        private fun moveBeat(delta: Int) {
            val bs = bars() ?: return
            if (bs.isEmpty()) return
            var b = currentBarIndex.coerceIn(0, bs.lastIndex)
            var beat = currentBeatIndex
            val step = if (delta < 0) -1 else 1
            repeat(kotlin.math.abs(delta)) {
                val count = bs[b].voices.firstOrNull()?.beats?.toList()?.size ?: 0
                if (count <= 0) return@repeat
                var candidate = beat + step
                if (candidate < 0) {
                    if (b == 0) return@repeat
                    b--
                    candidate = (bs[b].voices.firstOrNull()?.beats?.toList()?.size ?: 1) - 1
                } else if (candidate >= count) {
                    if (b == bs.lastIndex) return@repeat
                    b++
                    candidate = 0
                }
                beat = candidate.coerceAtLeast(0)
            }
            currentBarIndex = b
            currentBeatIndex = beat
            armed = true
            pendingFret = ""
            updateCursor()
            updateStatus()
        }

        private fun moveToEdge(end: Boolean) {
            val bs = bars() ?: return
            if (bs.isEmpty()) return
            currentBarIndex = if (end) bs.lastIndex else 0
            val beats = bs[currentBarIndex].voices.firstOrNull()?.beats?.toList().orEmpty()
            currentBeatIndex = if (end) (beats.size - 1).coerceAtLeast(0) else 0
            armed = true
            pendingFret = ""
            updateCursor()
            updateStatus()
        }

        /** Up/down changes the TAB string only; it never changes the rhythmic beat. */
        private fun moveString(delta: Int) {
            currentStringIndex = (currentStringIndex + delta).coerceIn(1, maxStringIndex())
            armed = true
            pendingFret = ""
            updateCursor()
            updateStatus()
        }

        private fun acceptDigit(digit: Int) {
            if (!armed) armed = true
            val now = android.os.SystemClock.uptimeMillis()
            if (now - pendingAtMs > 900L) pendingFret = ""
            pendingAtMs = now
            val candidate = (pendingFret + digit).take(2)
            val value = candidate.toIntOrNull() ?: return
            if (value > 24) {
                pendingFret = digit.toString()
                writeFret(digit)
                pendingAtMs = now
                return
            }
            pendingFret = candidate
            if (candidate.length == 2 || value == 0) {
                writeFret(value)
                pendingFret = ""
            } else {
                updateStatus("Fret $candidate…")
                activity.window.decorView.postDelayed({
                    val t = android.os.SystemClock.uptimeMillis()
                    if (t - pendingAtMs >= 850L && pendingFret == candidate) {
                        writeFret(value)
                        pendingFret = ""
                    }
                }, 900L)
            }
        }

        private fun writeFret(fret: Int) {
            if (fret !in 0..24) return
            val beat = currentBeat() ?: return
            val existing = beat.getNoteOnString(currentStringIndex.toDouble())
            if (existing != null) {
                existing.fret = fret.toDouble()
                existing.finish(score.settings, null)
            } else {
                val note = Note().apply {
                    string = currentStringIndex.toDouble()
                    this.fret = fret.toDouble()
                }
                beat.addNote(note)
            }
            score.api.score?.finish(score.settings)
            val column = currentBarIndex * 16 + currentBeatIndex
            score.api.render()
            updateCursor()
            updateStatus("Fret $fret • Bar " + (currentBarIndex + 1) + " • Beat " + (currentBeatIndex + 1) + " • String " + currentStringIndex)
            advanceAfterEntry()
        }

        private fun deleteCurrentNote() {
            val beat = currentBeat() ?: return
            val note = beat.getNoteOnString(currentStringIndex.toDouble()) ?: run {
                updateStatus("No note on current string")
                return
            }
            beat.removeNote(note)
            score.api.score?.finish(score.settings)
            val column = currentBarIndex * 16 + currentBeatIndex
            score.api.render()
            updateCursor()
            updateStatus("Note deleted")
        }

        /**
         * Uses AlphaTab's own playback-range highlight as the visual editor cursor.
         * The cursor is therefore anchored to the real rendered Beat, not a fake grid.
         */
        private fun updateCursor() {
            val beat = currentBeat() ?: return
            try {
                score.api.highlightPlaybackRange(beat, beat)
            } catch (_: Throwable) { }
        }

        private fun updateStatus(message: String? = null) {
            val text = message ?: ("EDIT • Bar ${currentBarIndex + 1} • Beat ${currentBeatIndex + 1} • String $currentStringIndex")
            activity.runOnUiThread { status.text = text }
        }
    }


}
