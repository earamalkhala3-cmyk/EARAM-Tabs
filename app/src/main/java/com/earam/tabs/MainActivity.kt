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
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import alphaTab.AlphaTabView
import alphaTab.LayoutMode
import alphaTab.PlayerMode
import alphaTab.StaveProfile
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
    private var projectName = "Music Home"
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
    private var statusView: TextView? = null
    private var soundFontLoaded = false
    private var soundFontLoading = false
    private var playerEngineReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openEditor()
        // Do not start on an empty AlphaTabView. Create and render the real AlphaTab Score after layout.
        window.decorView.post {
            newScore()
        }
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
            setBackgroundColor(0xFFD7D9DC.toInt())
        }

        val title = TextView(this).apply {
            text = projectName
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), 0, dp(12f), 0)
            setBackgroundColor(0xFF191C1F.toInt())
        }

        // Internal Earam branding: text is deliberately rendered as Android text so the
        // exact spelling and orange center r remain visible on every density/device.
        val brandBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF191C1F.toInt())
            setPadding(dp(10f), 0, dp(10f), 0)
        }
        val brand = TextView(this).apply {
            text = "Ea"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 23f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val brandR = TextView(this).apply {
            text = "r"
            setTextColor(0xFFFF8A00.toInt())
            textSize = 23f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val brandEnd = TextView(this).apply {
            text = "am"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 23f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        brandBar.addView(brand, LinearLayout.LayoutParams(-2, dp(50f)))
        brandBar.addView(brandR, LinearLayout.LayoutParams(-2, dp(50f)))
        brandBar.addView(brandEnd, LinearLayout.LayoutParams(-2, dp(50f)))
        brandBar.addView(title, LinearLayout.LayoutParams(0, dp(50f), 1f))

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
            setPadding(dp(5f), 0, dp(5f), 0)
            textSize = 11f
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
            controls.addView(it, LinearLayout.LayoutParams(dp(66f), dp(40f)))
        }
        controlsScroll.addView(controls, LinearLayout.LayoutParams(-2, dp(52f)))

        val score = AlphaTabView(this, null).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            settings.display.layoutMode = LayoutMode.Page
            settings.display.staveProfile = StaveProfile.ScoreTab
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
        statusView = status

        val scoreLayer = FrameLayout(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        val editorOverlay = TabEditOverlayView(this)
        scoreLayer.addView(score, FrameLayout.LayoutParams(-1, -1))
        scoreLayer.addView(editorOverlay, FrameLayout.LayoutParams(dp(44f), dp(44f)))
        val editor = AlphaTabNoteEditor(this, score, status, editorOverlay)
        noteEditor = editor
        editor.attach()

        val durationScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF30353A.toInt())
        }
        val durations = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(3f), dp(4f), dp(3f))
        }
        fun durationButton(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(5f), 0, dp(5f), 0)
            textSize = 10f
            setOnClickListener { action() }
        }
        val durationButtons = listOf(
            durationButton("WHOLE") { editor.setCurrentDuration(Duration.Whole) },
            durationButton("HALF") { editor.setCurrentDuration(Duration.Half) },
            durationButton("QUARTER") { editor.setCurrentDuration(Duration.Quarter) },
            durationButton("EIGHTH") { editor.setCurrentDuration(Duration.Eighth) },
            durationButton("16TH") { editor.setCurrentDuration(Duration.Sixteenth) },
            durationButton("32ND") { editor.setCurrentDuration(Duration.ThirtySecond) },
            durationButton("DOT") { editor.setCurrentDuration(editor.currentBeatDuration().first, (editor.currentBeatDuration().second + 1).coerceAtMost(2)) },
            durationButton("TRIPLET") { editor.setCurrentDuration(editor.currentBeatDuration().first, 0, 3, 2) }
        )
        durationButtons.forEach { durations.addView(it, LinearLayout.LayoutParams(dp(78f), dp(40f))) }
        durationScroll.addView(durations, LinearLayout.LayoutParams(-2, dp(52f)))

        val editScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF25292D.toInt())
        }
        val editTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(3f), dp(4f), dp(3f))
        }
        fun editTool(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(4f), 0, dp(4f), 0)
            textSize = 11f
            setOnClickListener { action() }
        }
        // Compact numeric fret row: tap 0–9 directly to enter a fret on the
        // currently selected AlphaTab Beat/string. Two taps in succession can form 10–24.
        val numberScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF25292D.toInt())
        }
        val numbers = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
        }
        for (digit in 0..9) {
            val button = Button(this).apply {
                text = digit.toString()
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                textSize = 15f
                setPadding(0, 0, 0, 0)
                setOnClickListener { editor.enterDigitFromUi(digit) }
            }
            numbers.addView(button, LinearLayout.LayoutParams(dp(40f), dp(38f)))
        }
        numberScroll.addView(numbers, LinearLayout.LayoutParams(-2, dp(42f)))

        val prev = editTool("‹") { editor.moveBeatFromUi(-1) }
        val next = editTool("›") { editor.moveBeatFromUi(1) }
        val up = editTool("↑") { editor.moveStringFromUi(-1) }
        val down = editTool("↓") { editor.moveStringFromUi(1) }
        val del = editTool("DEL") { editor.deleteCurrentNoteFromUi() }

        listOf(prev, next, up, down, del).forEach {
            editTools.addView(it, LinearLayout.LayoutParams(dp(54f), dp(38f)))
        }
        editScroll.addView(editTools, LinearLayout.LayoutParams(-2, dp(42f)))

        file.setOnClickListener { showFileMenu() }

        score.api.renderFinished.on { runOnUiThread { editor.refreshVisualCursor() } }
        score.api.postRenderFinished.on {
            runOnUiThread {
                editor.refreshVisualCursor()
                editor.logRenderState()
            }
        }

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
                play.isEnabled = soundFontLoaded
                if (!soundFontLoaded && !soundFontLoading) {
                    status.text = "Player ready • waiting for SoundFont"
                }
            }
        }

        score.api.soundFontLoaded.on {
            soundFontLoaded = true
            soundFontLoading = false
            runOnUiThread {
                try {
                    score.api.loadMidiForScore()
                    play.isEnabled = true
                    status.text = "Sound ready • " + bpm + " BPM"
                } catch (t: Throwable) {
                    soundFontLoaded = false
                    play.isEnabled = false
                    showImportError("MIDI", t)
                }
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

        root.addView(brandBar, LinearLayout.LayoutParams(-1, dp(50f)))
        root.addView(status, LinearLayout.LayoutParams(-1, dp(30f)))
        root.addView(controlsScroll, LinearLayout.LayoutParams(-1, dp(44f)))
        root.addView(durationScroll, LinearLayout.LayoutParams(-1, dp(44f)))
        root.addView(editScroll, LinearLayout.LayoutParams(-1, dp(44f)))
        root.addView(numberScroll, LinearLayout.LayoutParams(-1, dp(44f)))
        root.addView(scoreLayer, LinearLayout.LayoutParams(-1, 0, 1f))
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
    private fun addQuarterRestBeats(voice: alphaTab.model.Voice) {
        repeat(4) {
            voice.addBeat(Beat().apply {
                duration = Duration.Quarter
                dots = 0.0
                tupletNumerator = -1.0
                tupletDenominator = -1.0
                isEmpty = true
            })
        }
    }

    private fun newScore() {
        try {
            val score = Score()
            val firstMaster = MasterBar().apply {
                timeSignatureNumerator = 4.0
                timeSignatureDenominator = 4.0
            }
            score.addMasterBar(firstMaster)

            val track = alphaTab.model.Track()
            val staff = alphaTab.model.Staff()
            staff.showStandardNotation = true
            staff.showTablature = true
            staff.stringTuning = alphaTab.model.Tuning.getDefaultTuningFor(6.0)
                ?: throw IllegalStateException("AlphaTab has no default 6-string tuning")
            staff.stringTuning.finish()
            track.addStaff(staff)
            score.addTrack(track)

            // Every new 4/4 measure starts with exactly four real quarter Empty Beats.
            repeat(4) { barNumber ->
                if (barNumber > 0) {
                    val master = MasterBar().apply {
                        timeSignatureNumerator = 4.0
                        timeSignatureDenominator = 4.0
                    }
                    score.addMasterBar(master)
                }
                val bar = Bar()
                staff.addBar(bar)
                val voice = alphaTab.model.Voice()
                bar.addVoice(voice)
                addQuarterRestBeats(voice)
            }

            val settings = alphaTabView?.settings ?: return
            score.finish(settings)
            currentScore = score
            alphaTabView?.api?.renderScore(score)
            alphaTabView?.api?.render()
            projectName = "Music Home"
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

        val trackCount = parsed.tracks.toList().size
        val masterBarCount = parsed.masterBars.toList().size
        parsed.tracks.firstOrNull()
            ?: throw IllegalStateException(
                "The imported file contains no tracks (tracks=$trackCount, masterBars=$masterBarCount)"
            )

        currentScore = parsed
        projectName = parsed.title.ifBlank { fileName.substringBeforeLast('.') }

        runOnUiThread {
            try {
                val width = view.width
                val height = view.height
                if (width <= 0 || height <= 0) {
                    throw IllegalStateException(
                        "AlphaTabView has invalid size: " + width + "x" + height
                    )
                }

                // GP bytes -> one AlphaTab Score -> AlphaTab renderer.
                // Do not bind only the first track through view.tracks.
                view.api.renderScore(parsed)
                noteEditor?.resetSelection()
                statusView?.text =
                    "Parsed OK • tracks=$trackCount • masterBars=$masterBarCount • view=" +
                    width + "x" + height + " • renderScore() called"
            } catch (t: Throwable) {
                showImportError("AlphaTab render", t)
            }
        }

        loadSoundFontForCurrentScore(view)
    }

    private fun loadSoundFontForCurrentScore(view: AlphaTabView) {
        if (soundFontLoaded) {
            runOnUiThread {
                try {
                    view.api.loadMidiForScore()
                } catch (t: Throwable) {
                    showImportError("MIDI", t)
                }
            }
            return
        }
        if (soundFontLoading) return
        soundFontLoading = true
        runOnUiThread { statusView?.text = "Loading sound • AlphaTab 1.8.4 SoundFont…" }
        Thread {
            var connection: HttpURLConnection? = null
            try {
                // AlphaTab 1.8.4 ships this exact SONiVOX SoundFont as SF2.
                // It is fetched as binary data; it is NOT an Android asset and is NOT base64/text.
                val sfUrl =
                    "https://cdn.jsdelivr.net/npm/@coderline/alphatab@1.8.4/dist/soundfont/sonivox.sf2"
                connection = (URL(sfUrl).openConnection() as HttpURLConnection).apply {
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
                val expectedLength = connection.contentLengthLong
                val sf = connection.inputStream.use { it.readBytes() }
                val actualLength = sf.size.toLong()
                if (actualLength < 12L) {
                    throw IllegalStateException("SoundFont is truncated: $actualLength bytes")
                }

                // SF2 is a RIFF/WAVE-style container: bytes 0..3 = RIFF, 8..11 = sfbk.
                val riff = sf.copyOfRange(0, 4).toString(Charsets.US_ASCII)
                val form = sf.copyOfRange(8, 12).toString(Charsets.US_ASCII)
                if (riff != "RIFF" || form != "sfbk") {
                    throw IllegalStateException(
                        "Invalid SF2 header: first12=" +
                            sf.copyOfRange(0, 12).joinToString("") { "%02X".format(it) } +
                            " (expected RIFF........sfbk)"
                    )
                }
                if (expectedLength >= 0L && expectedLength != actualLength) {
                    throw IllegalStateException(
                        "SoundFont truncated: HTTP Content-Length=$expectedLength, received=$actualLength"
                    )
                }

                val headerInfo = "SF2 • $actualLength bytes • RIFF/sfbk"
                runOnUiThread { statusView?.text = "Validated $headerInfo • loading into AlphaTab…" }

                // Android AlphaTab accepts a native InputStream/byte container. Use an InputStream
                // here rather than passing the network ByteArray through any text/base64 conversion.
                // The load is asynchronous; success is confirmed only by soundFontLoaded.
                runOnUiThread {
                    try {
                        val accepted = view.api.loadSoundFont(ByteArrayInputStream(sf), false)
                        if (!accepted) {
                            soundFontLoading = false
                            throw IllegalStateException("AlphaTab rejected validated SoundFont ($headerInfo)")
                        }
                    } catch (t: Throwable) {
                        soundFontLoading = false
                        showImportError("SoundFont", t)
                    }
                }
            } catch (t: Throwable) {
                soundFontLoading = false
                runOnUiThread { showImportError("SoundFont", t) }
            } finally {
                connection?.disconnect()
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

    private class TabEditOverlayView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * resources.displayMetrics.density
            color = 0xFFFF6D00.toInt()
        }
        private val rect = RectF()
        private var cursorWidth = 44f
        private var cursorHeight = 44f

        fun showCursor(left: Float, top: Float, width: Float, height: Float) {
            cursorWidth = width
            cursorHeight = height
            layoutParams = (layoutParams ?: FrameLayout.LayoutParams(1, 1)).apply {
                this.width = width.toInt().coerceAtLeast(1)
                this.height = height.toInt().coerceAtLeast(1)
            }
            translationX = left
            translationY = top
            rect.set(3f, 3f, maxOf(4f, width - 3f), maxOf(4f, height - 3f))
            visibility = VISIBLE
            invalidate()
        }

        fun hideCursor() { visibility = INVISIBLE }

        override fun onDraw(canvas: Canvas) {
            if (visibility == VISIBLE) canvas.drawRoundRect(rect, 5f, 5f, paint)
        }
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
            return barCapacityTicksForMaster(bar.masterBar)
        }
        fun barCapacityTicksForMaster(m: MasterBar): Long {
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
        fun largestEmptyBeatSpec(ticks: Long): Quintuple? {
            val candidates = listOf(
                Quintuple(Duration.Whole, 0, -1, -1, QUARTER_TICKS * 4),
                Quintuple(Duration.Half, 1, -1, -1, QUARTER_TICKS * 3),
                Quintuple(Duration.DoubleWhole, 0, -1, -1, QUARTER_TICKS * 8),
                Quintuple(Duration.Half, 0, -1, -1, QUARTER_TICKS * 2),
                Quintuple(Duration.Quarter, 1, -1, -1, QUARTER_TICKS * 3 / 2),
                Quintuple(Duration.Quarter, 0, -1, -1, QUARTER_TICKS),
                Quintuple(Duration.Eighth, 1, -1, -1, QUARTER_TICKS * 3 / 4),
                Quintuple(Duration.Eighth, 0, -1, -1, QUARTER_TICKS / 2),
                Quintuple(Duration.Sixteenth, 1, -1, -1, QUARTER_TICKS * 3 / 8),
                Quintuple(Duration.Sixteenth, 0, -1, -1, QUARTER_TICKS / 4),
                Quintuple(Duration.ThirtySecond, 0, -1, -1, QUARTER_TICKS / 8),
                Quintuple(Duration.SixtyFourth, 0, -1, -1, QUARTER_TICKS / 16),
                Quintuple(Duration.Eighth, 0, 3, 2, QUARTER_TICKS / 3),
                Quintuple(Duration.Sixteenth, 0, 3, 2, QUARTER_TICKS / 6),
                Quintuple(Duration.ThirtySecond, 0, 3, 2, QUARTER_TICKS / 12)
            ).sortedByDescending { it.fifth }
            return candidates.firstOrNull { it.fifth <= ticks }
        }

        data class Quintuple(
            val first: Duration, val second: Int, val third: Int, val fourth: Int, val fifth: Long
        )
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
        private val status: TextView,
        private val overlay: TabEditOverlayView
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
        private var inputGeneration: Long = 0L

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
                        // UI numbering is top-to-bottom (1 = thin/high E). AlphaTab is bottom-to-top.
                        currentStringIndex = (7 - note.string.toInt()).coerceIn(1, maxStringIndex())
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
        fun currentBeatDuration(): Pair<Duration, Int> = currentBeat()?.let { Pair(it.duration, it.dots.toInt().coerceIn(0, 2)) } ?: Pair(Duration.Quarter, 0)
        fun currentBarCapacityTicks(): Long = bars()?.getOrNull(currentBarIndex)?.let { AlphaTabRhythmEngine.barCapacityTicks(it) } ?: 0L
        fun currentBarUsedTicks(): Long = bars()?.getOrNull(currentBarIndex)?.let { AlphaTabRhythmEngine.barUsedTicks(it) } ?: 0L

        private fun currentSelectedDuration(): Duration = currentBeatDuration().first
        private fun currentSelectedDots(): Int = currentBeatDuration().second
        private fun currentSelectedTupletNumerator(): Int =
            currentBeat()?.let { if (it.tupletNumerator >= 0 && it.tupletDenominator > 0) it.tupletNumerator.toInt() else -1 } ?: -1
        private fun currentSelectedTupletDenominator(): Int =
            currentBeat()?.let { if (it.tupletNumerator >= 0 && it.tupletDenominator > 0) it.tupletDenominator.toInt() else -1 } ?: -1

        fun setCurrentDuration(duration: Duration, dots: Int = 0, tupletNumerator: Int = -1, tupletDenominator: Int = -1): Boolean {
            val beat = currentBeat() ?: return false
            val bar = bars()?.getOrNull(currentBarIndex) ?: return false
            if (!AlphaTabRhythmEngine.fits(bar, beat, duration, dots, tupletNumerator, tupletDenominator)) {
                updateStatus("Duration does not fit • " + bar.masterBar.timeSignatureNumerator.toInt() + "/" + bar.masterBar.timeSignatureDenominator.toInt())
                return false
            }
            AlphaTabRhythmEngine.apply(beat, duration, dots, tupletNumerator, tupletDenominator)
            if (beat.notes.toList().isEmpty()) beat.isEmpty = true
            score.api.score?.finish(score.settings)
            renderAndLog("duration")
            updateCursor()
            updateStatus("Duration " + duration.name)
            return true
        }

        private fun createNextMeasures(count: Int = 4): Boolean {
            val song = score.api.score ?: return false
            val sourceBar = bars()?.lastOrNull() ?: return false
            repeat(count.coerceAtLeast(1)) {
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
                        addQuarterRestBeats(voice)
                    }
                }
            }
            song.finish(score.settings)
            renderAndLog("create-next-measures")
            return true
        }

        private fun ensureTrailingMeasures(currentIndex: Int): Boolean {
            val bs = bars() ?: return false
            if (bs.isEmpty() || currentIndex < bs.lastIndex) return true
            return createNextMeasures(4)
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

            // The current measure is complete. Crossing its last beat always moves
            // to beat 1 of the next measure. If this was the trailing measure,
            // append four more Empty-Beat measures first.
            if (currentBarIndex == bs.lastIndex) {
                if (!createNextMeasures(4)) return
            }
            currentBarIndex++
            currentBeatIndex = 0
            updateCursor()
            updateStatus("Measure " + (currentBarIndex + 1) + " • Beat 1")
        }

        /** Arrow navigation never creates a measure. It only moves inside existing Score beats. */
        fun moveBeatFromUi(delta: Int) = moveBeat(delta)
        fun moveStringFromUi(delta: Int) = moveString(delta)
        fun enterDigitFromUi(digit: Int) = acceptDigit(digit)
        fun writeFretFromUi(fret: Int) = writeFret(fret)
        fun deleteCurrentNoteFromUi() = deleteCurrentNote()

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
                    if (b == bs.lastIndex) {
                        // Reaching the trailing measure expands the score by four
                        // Empty-Beat measures before the move continues.
                        if (!createNextMeasures(4)) return@repeat
                    }
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
            val generation = ++inputGeneration
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
                    if (generation == inputGeneration && t - pendingAtMs >= 850L && pendingFret == candidate) {
                        writeFret(value)
                        pendingFret = ""
                    }
                }, 900L)
            }
        }

        /** UI String 1 is the thin/high E; AlphaTab string 1 is the lowest/bottom string. */
        private fun alphaTabString(uiString: Int): Int =
            (7 - uiString).coerceIn(1, maxStringIndex())

        private fun writeFret(fret: Int) {
            if (fret !in 0..24) return
            try {
                val song = score.api.score ?: return
                val beat = currentBeat() ?: return
                val alphaTabString = alphaTabString(currentStringIndex)
                val existing = beat.getNoteOnString(alphaTabString.toDouble())
                if (existing != null) {
                    existing.fret = fret.toDouble()
                    existing.finish(score.settings, null)
                } else {
                    val note = Note().apply {
                        string = alphaTabString.toDouble()
                        this.fret = fret.toDouble()
                    }
                    beat.addNote(note)
                    note.finish(score.settings, null)
                }
                beat.isEmpty = beat.notes.toList().isEmpty()
                beat.finish(score.settings, null)
                song.finish(score.settings)
                renderAndLog("fret=" + fret)
                updateCursor()
                updateStatus("Fret " + fret + " • Bar " + (currentBarIndex + 1) + " • Beat " + (currentBeatIndex + 1) + " • String " + currentStringIndex)
                advanceAfterEntry()
            } catch (t: Throwable) {
                pendingFret = ""
                inputGeneration++
                activity.runOnUiThread { status.text = "Note entry error • " + (t.message ?: t.javaClass.simpleName) }
            }
        }

        private var lastRenderReason: String = "unspecified"

        private fun renderAndLog(reason: String) {
            lastRenderReason = reason
            score.api.score?.finish(score.settings)
            score.api.render()
        }

        fun logRenderState() {
            val song = score.api.score
            val staff = song?.tracks?.firstOrNull()?.staves?.firstOrNull()
            val barCounts = staff?.bars?.toList()?.mapIndexed { index, bar ->
                "bar=" + (index + 1) + ":beats=" +
                    (bar.voices.firstOrNull()?.beats?.toList()?.size ?: 0)
            }?.joinToString(", ") ?: "no-score"
            val beat = currentBeat()
            val lookup = score.api.renderer.boundsLookup
            val currentBounds = beat?.let { lookup?.findBeat(it) }
            val fret = beat?.getNoteOnString(alphaTabString(currentStringIndex).toDouble())?.fret?.toInt()
            val boundsSummary = currentBounds?.let {
                "real=" + it.realBounds.x + "," + it.realBounds.y + "," +
                    it.realBounds.w + "," + it.realBounds.h +
                    " visual=" + it.visualBounds.x + "," + it.visualBounds.y + "," +
                    it.visualBounds.w + "," + it.visualBounds.h +
                    " onNotesX=" + it.onNotesX
            } ?: "NONE"
            android.util.Log.d(
                "EARAM_RENDER",
                "reason=" + lastRenderReason +
                    " | " + barCounts +
                    " | current=bar/" + (currentBarIndex + 1) +
                    " beat/" + (currentBeatIndex + 1) +
                    " string/" + currentStringIndex +
                    " fret/" + (fret ?: "-") +
                    " | boundsLookup=" + boundsSummary
            )
        }

        private fun deleteCurrentNote() {
            val beat = currentBeat() ?: return
            val alphaTabString = alphaTabString(currentStringIndex)
            val note = beat.getNoteOnString(alphaTabString.toDouble()) ?: run {
                beat.isEmpty = beat.notes.toList().isEmpty()
                score.api.score?.finish(score.settings)
                renderAndLog("delete-empty")
                updateCursor()
                updateStatus("No note on current string")
                return
            }
            beat.removeNote(note)
            beat.isEmpty = beat.notes.toList().isEmpty()
            beat.finish(score.settings, null)
            score.api.score?.finish(score.settings)
            renderAndLog("delete")
            updateCursor()
            updateStatus(if (beat.isEmpty) "Beat cleared" else "Note deleted")
        }

        /**
         * Uses AlphaTab's own playback-range highlight as the visual editor cursor.
         * The cursor is therefore anchored to the real rendered Beat, not a fake grid.
         */
        fun refreshVisualCursor() {
            val beat = currentBeat() ?: run { overlay.hideCursor(); return }
            try {
                val lookup = score.api.renderer.boundsLookup ?: run {
                    overlay.hideCursor()
                    return
                }
                val bounds = lookup.findBeat(beat) ?: run {
                    overlay.hideCursor()
                    return
                }

                val alphaString = alphaTabString(currentStringIndex)
                val x = if (bounds.onNotesX.isFinite() && bounds.onNotesX > 0.0) {
                    bounds.onNotesX
                } else {
                    bounds.realBounds.x + bounds.realBounds.w / 2.0
                }

                // Anchor Y to the actual TAB staff line, not the system origin and not
                // a note head. The bar bounds are the rendered staff region for this track.
                val scale = score.settings.display.scale
                val oneStaffSpace =
                    score.settings.display.resources.engravingSettings.oneStaffSpace * scale
                val tabLineSpacing =
                    score.settings.display.resources.engravingSettings.tabLineSpacing * scale
                val barReal = bounds.barBounds.realBounds
                val tabFirstLineY = barReal.y + (oneStaffSpace * 4.0) + tabLineSpacing
                val y = tabFirstLineY + (currentStringIndex - 1) * tabLineSpacing

                val cursorSize = (tabLineSpacing * 0.95).coerceAtLeast(18.0)
                val left = x - cursorSize / 2.0
                val top = y - cursorSize / 2.0

                overlay.showCursor(
                    (left - score.scrollX).toFloat(),
                    (top - score.scrollY).toFloat(),
                    cursorSize.toFloat(),
                    cursorSize.toFloat()
                )

                android.util.Log.d(
                    "EARAM_CURSOR",
                    "bar=" + (currentBarIndex + 1) +
                        " beat=" + (currentBeatIndex + 1) +
                        " string=" + currentStringIndex +
                        " alphaString=" + alphaString +
                        " x=" + x + " y=" + y +
                        " tabFirstLineY=" + tabFirstLineY +
                        " spacing=" + tabLineSpacing
                )
            } catch (t: Throwable) {
                android.util.Log.e("EARAM_CURSOR", "cursor calculation failed", t)
                overlay.hideCursor()
            }
        }

        private fun updateCursor() {
            val beat = currentBeat() ?: return
            // Playback cursor remains AlphaTab's native cursor and is driven only by playback.
            // Editor navigation uses the orange overlay and never changes the playback range.
            refreshVisualCursor()
        }

        private fun currentStringLabel(): String {
            return when (currentStringIndex) {
                1 -> "HIGH E"
                2 -> "B"
                3 -> "G"
                4 -> "D"
                5 -> "A"
                6 -> "LOW E"
                else -> "STRING $currentStringIndex"
            }
        }

        private fun currentFretLabel(): String {
            val beat = currentBeat() ?: return "—"
            val alphaString = alphaTabString(currentStringIndex)
            val note = beat.getNoteOnString(alphaString.toDouble()) ?: return "—"
            return note.fret.toInt().toString()
        }

        private fun updateStatus(message: String? = null) {
            val text = message ?: (
                "EDIT • BAR ${currentBarIndex + 1} • BEAT ${currentBeatIndex + 1}" +
                "  |  STRING $currentStringIndex (${currentStringLabel()})" +
                "  |  FRET ${currentFretLabel()}"
            )
            activity.runOnUiThread { status.text = text }
        }
    }


}
