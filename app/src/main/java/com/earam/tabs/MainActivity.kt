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
import alphaTab.model.Automation
import alphaTab.model.KeySignature
import alphaTab.model.KeySignatureType

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
    private var titleView: TextView? = null
    private var soundFontLoaded = false
    private var soundFontLoading = false
    private var playerEngineReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openEditor()
        // Do not start on an empty AlphaTabView. Create and render the real AlphaTab Score after layout.
        window.decorView.post {
            newScore()
            // alphaTab Android 1.8.4 ships with SONiVOX and loads its default SoundFont
            // automatically when the synthesizer player is enabled.
            handleIncomingFileIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        window.decorView.post { handleIncomingFileIntent(intent) }
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
            settings.player.enableAnimatedBeatCursor = true
            settings.player.enableElementHighlighting = true
            settings.player.bufferTimeInMilliseconds = 1000.0
            api.masterVolume = 0.70
            api.updateSettings()
        }

        alphaTabView = score
        statusView = status
        titleView = title

        val scoreLayer = FrameLayout(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        val editorOverlay = TabEditOverlayView(this).apply {
            isEnabled = false
            isClickable = false
            isFocusable = false
        }
        scoreLayer.addView(score, FrameLayout.LayoutParams(-1, -1))
        // Full-page painter only; disabled so AlphaTab receives all touch gestures.
        scoreLayer.addView(editorOverlay, FrameLayout.LayoutParams(-1, -1))
        val editor = AlphaTabNoteEditor(this, score, status, editorOverlay)
        noteEditor = editor
        editor.attach()

        // PROFESSIONAL UI LAYOUT
        // Keep playback visible; move editing features into category windows like FILE.
        val topMenuScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF202428.toInt())
        }
        val topMenus = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(3f), dp(4f), dp(3f))
        }

        fun control(label: String): Button = Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(6f), 0, dp(6f), 0)
            textSize = 11f
        }

        fun menuButton(icon: String, label: String): Button = control(icon + "  " + label).apply {
            textSize = 10f
            contentDescription = label
        }

        val file = menuButton("☰", "FILE")
        val playbackMenu = menuButton("▶", "PLAYBACK")
        val editMenu = menuButton("✎", "EDIT")
        val trackMenu = menuButton("♫", "TRACK")
        val barMenu = menuButton("▣", "BAR")
        val noteMenu = menuButton("♪", "NOTE")
        val beatMenu = menuButton("♬", "BEAT")
        val viewMenu = menuButton("◉", "VIEW")
        listOf(file, playbackMenu, editMenu, trackMenu, barMenu, noteMenu, beatMenu, viewMenu).forEach {
            topMenus.addView(it, LinearLayout.LayoutParams(dp(74f), dp(38f)))
        }
        topMenuScroll.addView(topMenus, LinearLayout.LayoutParams(-2, dp(46f)))

        val transportScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF25292D.toInt())
        }
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
        }
        val play = control("▶").apply {
            textSize = 19f
            contentDescription = "Play / Pause"
            setPadding(0, 0, 0, 0)
        }
        val stop = control("■").apply {
            textSize = 16f
            contentDescription = "Stop"
            setPadding(0, 0, 0, 0)
        }
        val speedButton = control("1×").apply {
            textSize = 12f
            contentDescription = "Playback speed"
            setPadding(0, 0, 0, 0)
        }
        val selectedBarButton = control("B1").apply {
            textSize = 11f
            contentDescription = "Selected bar"
        }
        transport.addView(play, LinearLayout.LayoutParams(dp(46f), dp(38f)))
        transport.addView(stop, LinearLayout.LayoutParams(dp(46f), dp(38f)))
        transport.addView(speedButton, LinearLayout.LayoutParams(dp(54f), dp(38f)))
        transport.addView(selectedBarButton, LinearLayout.LayoutParams(dp(66f), dp(38f)))
        transportScroll.addView(transport, LinearLayout.LayoutParams(-2, dp(42f)))

        val contextScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF30353A.toInt())
        }
        val contextTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
        }
        val trackContext = control("♫1").apply {
            textSize = 13f
            contentDescription = "Select track"
        }
        val voiceContext = control("V1").apply {
            textSize = 12f
            contentDescription = "Select voice"
        }
        val selectionContext = TextView(this).apply {
            text = "B1 · S1 · F—"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), 0, dp(10f), 0)
            setBackgroundColor(0xFF30353A.toInt())
        }
        contextTools.addView(trackContext, LinearLayout.LayoutParams(dp(48f), dp(36f)))
        contextTools.addView(voiceContext, LinearLayout.LayoutParams(dp(46f), dp(36f)))
        contextTools.addView(selectionContext, LinearLayout.LayoutParams(dp(132f), dp(36f)))
        contextScroll.addView(contextTools, LinearLayout.LayoutParams(-2, dp(40f)))

        val navigationScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF30353A.toInt())
        }
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
        }
        fun navButton(label: String, action: () -> Unit): Button =
            Button(this).apply {
                text = label
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                textSize = 14f
                setPadding(0, 0, 0, 0)
                setOnClickListener { action() }
            }
        navigation.addView(navButton("←") { editor.moveBeatFromUi(-1) }, LinearLayout.LayoutParams(dp(40f), dp(36f)))
        navigation.addView(navButton("↑") { editor.moveStringFromUi(-1) }, LinearLayout.LayoutParams(dp(40f), dp(36f)))
        navigation.addView(navButton("↓") { editor.moveStringFromUi(1) }, LinearLayout.LayoutParams(dp(40f), dp(36f)))
        navigation.addView(navButton("→") { editor.moveBeatFromUi(1) }, LinearLayout.LayoutParams(dp(40f), dp(36f)))
        navigationScroll.addView(navigation, LinearLayout.LayoutParams(-2, dp(40f)))

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
            numbers.addView(button, LinearLayout.LayoutParams(dp(36f), dp(36f)))
        }
        numberScroll.addView(numbers, LinearLayout.LayoutParams(-2, dp(40f)))

        fun showMenu(titleText: String, labels: Array<String>, action: (Int) -> Unit) {
            AlertDialog.Builder(this)
                .setTitle(titleText)
                .setItems(labels) { _, which -> action(which) }
                .show()
        }

        fun refreshTrackButtons() {
            trackContext.text = "♫" + (editor.currentTrackIndex + 1)
        }
        fun refreshSelectionInfo() {
            selectionContext.text = editor.selectionInfoText()
            selectedBarButton.text = "B" + (editor.selectedBarIndex + 1)
            voiceContext.text = "V" + (editor.currentVoiceIndex + 1)
            trackContext.text = "♫" + (editor.currentTrackIndex + 1)
        }
        editor.onSelectionChanged = {
            refreshSelectionInfo()
        }

        file.setOnClickListener { showFileMenu() }

        playbackMenu.setOnClickListener {
            showMenu("PLAYBACK", arrayOf("PLAY / PAUSE", "STOP", "0.5×", "0.75×", "1×", "1.25×", "1.5×", "PLAYER STATUS")) { which ->
                when (which) {
                    0 -> if (score.api.isReadyForPlayback) score.api.playPause()
                        else status.text = "Player is preparing…"
                    1 -> score.api.stop()
                    2 -> { score.api.playbackSpeed = 0.50; speedButton.text = "0.5×" }
                    3 -> { score.api.playbackSpeed = 0.75; speedButton.text = "0.75×" }
                    4 -> { score.api.playbackSpeed = 1.00; speedButton.text = "1×" }
                    5 -> { score.api.playbackSpeed = 1.25; speedButton.text = "1.25×" }
                    6 -> { score.api.playbackSpeed = 1.50; speedButton.text = "1.5×" }
                    7 -> status.text = "Ready=" + score.api.isReadyForPlayback +
                        " • Player=" + score.api.actualPlayerMode.toString()
                }
            }
        }

        speedButton.setOnClickListener {
            showMenu("PLAYBACK SPEED", arrayOf("0.5×", "0.75×", "1×", "1.25×", "1.5×")) { which ->
                val value = when (which) {
                    0 -> 0.50
                    1 -> 0.75
                    2 -> 1.00
                    3 -> 1.25
                    else -> 1.50
                }
                score.api.playbackSpeed = value
                speedButton.text = when (which) {
                    0 -> "0.5×"
                    1 -> "0.75×"
                    2 -> "1×"
                    3 -> "1.25×"
                    else -> "1.5×"
                }
            }
        }
        play.setOnClickListener {
            if (score.api.isReadyForPlayback) score.api.playPause()
            else status.text = "Player is preparing…"
        }
        stop.setOnClickListener { score.api.stop() }

        editMenu.setOnClickListener {
            showMenu("EDIT", arrayOf("COPY NOTE", "PASTE NOTE", "DELETE NOTE", "ARROW NAVIGATION")) { which ->
                when (which) {
                    0 -> editor.copyCurrentNoteFromUi()
                    1 -> editor.pasteCurrentNoteFromUi()
                    2 -> editor.deleteCurrentNoteFromUi()
                    3 -> status.text = "← → Beat • ↑ ↓ String • Selected Bar stays independent"
                }
            }
        }

        trackMenu.setOnClickListener {
            showMenu("TRACK", arrayOf("SELECT TRACK", "ADD TRACK", "TRACK MIXER")) { which ->
                when (which) {
                    0 -> editor.showTrackSelectorDialog()
                    1 -> addTrackDialog()
                    2 -> editor.showTrackMixerDialog()
                }
            }
        }

        barMenu.setOnClickListener {
            showMenu("BAR • SELECTED BAR " + (editor.selectedBarIndex + 1),
                arrayOf("SELECT / GO TO BAR", "BAR TOOLS", "+ MEASURE", "DUPLICATE BAR", "CLEAR BAR", "DELETE BAR", "TIME / KEY")) { which ->
                when (which) {
                    0 -> editor.showBarSelectionDialog()
                    1 -> editor.showBarToolsDialog()
                    2 -> editor.addMeasureFromUi()
                    3 -> editor.duplicateCurrentBarToEndFromUi()
                    4 -> editor.clearCurrentBarFromUi()
                    5 -> editor.deleteCurrentBarFromUi()
                    6 -> editor.showTimelineDialog()
                }
            }
        }

        noteMenu.setOnClickListener {
            showMenu("NOTE", arrayOf("PLAY CURRENT BEAT", "DURATION", "TUPLET", "EFFECTS", "BEND", "PICK STROKE", "REST / DELETE")) { which ->
                when (which) {
                    0 -> editor.playCurrentBeatFromUi()
                    1 -> editor.showDurationDialog()
                    2 -> editor.showTupletDialog()
                    3 -> editor.showNoteEffectsDialog()
                    4 -> editor.showBendDialog()
                    5 -> editor.showPickStrokeDialog()
                    6 -> editor.makeCurrentRestFromUi()
                }
            }
        }

        beatMenu.setOnClickListener {
            showMenu("BEAT", arrayOf("BEAT EFFECTS", "TIE", "REST", "COPY BAR", "PASTE BAR")) { which ->
                when (which) {
                    0 -> editor.showBeatEffectsDialog()
                    1 -> editor.toggleTieFromUi()
                    2 -> editor.makeCurrentRestFromUi()
                    3 -> editor.copyCurrentBarFromUi()
                    4 -> editor.pasteBarToCurrentFromUi()
                }
            }
        }

        viewMenu.setOnClickListener {
            showMenu("VIEW", arrayOf("SCORE + TAB", "TAB ONLY", "SCORE ONLY", "ZOOM 72%", "ZOOM 85%", "ZOOM 100%")) { which ->
                when (which) {
                    0 -> { score.settings.display.staveProfile = StaveProfile.ScoreTab; score.settings.display.scale = 0.72; score.api.updateSettings(); score.api.render() }
                    1 -> { score.settings.display.staveProfile = StaveProfile.Tab; score.settings.display.scale = 0.72; score.api.updateSettings(); score.api.render() }
                    2 -> { score.settings.display.staveProfile = StaveProfile.Score; score.settings.display.scale = 0.72; score.api.updateSettings(); score.api.render() }
                    3 -> { score.settings.display.scale = 0.72; score.api.updateSettings(); score.api.render() }
                    4 -> { score.settings.display.scale = 0.85; score.api.updateSettings(); score.api.render() }
                    5 -> { score.settings.display.scale = 1.0; score.api.updateSettings(); score.api.render() }
                }
            }
        }

        trackContext.setOnClickListener { editor.showTrackSelectorDialog() }
        voiceContext.setOnClickListener { editor.showVoiceSelectorDialog() }
        selectedBarButton.setOnClickListener { editor.showBarSelectionDialog() }

        score.api.postRenderFinished.on {
            runOnUiThread {
                editor.syncSelectedBarHighlight()
                editor.refreshVisualCursor()
                editor.logRenderState()
            }
        }

        score.api.scoreLoaded.on { loaded ->
            normalizeImportedTracks(loaded)
            currentScore = loaded
            projectName = loaded.title.ifBlank { projectName }
            noteEditor?.resetSelection()
            runOnUiThread {
                title.text = projectName
                status.text = "Score loaded • preparing playback"
                refreshTrackButtons()
                refreshSelectionInfo()
            }
            noteEditor?.syncSelectedBarHighlight()
            try {
                score.api.loadMidiForScore()
            } catch (t: Throwable) {
                android.util.Log.e("EARAM_PLAYER", "loadMidiForScore after scoreLoaded failed", t)
                runOnUiThread {
                    status.text = "MIDI preparation failed • " + (t.message ?: t.javaClass.simpleName)
                }
            }
        }

        score.api.error.on { error ->
            runOnUiThread {
                status.text = "AlphaTab error: " + (error.message ?: "unknown")
            }
        }

        score.api.soundFontLoaded.on {
            soundFontLoaded = true
            soundFontLoading = false
            android.util.Log.i("EARAM_PLAYER", "SoundFontLoaded=true")
        }

        score.api.playerReady.on {
            playerEngineReady = true
            // AlphaTab 1.8.4 Android reports playerReady only after audio output,
            // SoundFont and MIDI are ready. Do not inject or replace the bundled SoundFont.
            soundFontLoaded = true
            soundFontLoading = false
            android.util.Log.i(
                "EARAM_PLAYER",
                "playerReady=true; readyForPlayback=" + score.api.isReadyForPlayback +
                    "; actualPlayerMode=" + score.api.actualPlayerMode +
                    "; masterVolume=" + score.api.masterVolume
            )
            runOnUiThread {
                val ready = score.api.isReadyForPlayback
                play.isEnabled = true
                status.text = if (ready) "Player ready • " + bpm + " BPM"
                else "Player initialized • PLAY will activate when ready"
            }
        }


        score.api.playerStateChanged.on {
            runOnUiThread {
                val ready = score.api.isReadyForPlayback
                play.isEnabled = true
                play.text =
                    if (score.api.playerState.toString().contains("Playing", true)) "PAUSE"
                    else "PLAY"
                if (ready) {
                    status.text = "Sound ready • " + bpm + " BPM"
                    score.api.scrollToCursor()
                }
            }
        }

        root.addView(brandBar, LinearLayout.LayoutParams(-1, dp(50f)))
        root.addView(status, LinearLayout.LayoutParams(-1, dp(30f)))
        root.addView(topMenuScroll, LinearLayout.LayoutParams(-1, dp(46f)))
        root.addView(transportScroll, LinearLayout.LayoutParams(-1, dp(44f)))
        root.addView(contextScroll, LinearLayout.LayoutParams(-1, dp(44f)))
        root.addView(navigationScroll, LinearLayout.LayoutParams(-1, dp(44f)))
        root.addView(numberScroll, LinearLayout.LayoutParams(-1, dp(44f)))
        root.addView(scoreLayer, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        play.isEnabled = true
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
                    0 -> showNewFileWizard()
                    1, 4 -> importTab()
                    2, 3 -> Toast.makeText(this, "Save is not enabled in this build.", Toast.LENGTH_SHORT).show()
                    5 -> Toast.makeText(this, "Export is not enabled in this build.", Toast.LENGTH_SHORT).show()
                    6 -> finish()
                    7 -> Toast.makeText(this, "Update service is not enabled in this build.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** Guitar-Pro-style New File setup using the same AlphaTab Score model. */
    private fun showNewFileWizard() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
        }
        fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) =
            EditText(this).apply {
                this.hint = hint
                setText(value)
                inputType = type
                setSingleLine(true)
                setPadding(0, dp(4f), 0, dp(4f))
            }

        val title = field("Song title", projectName)
        val tempo = field("Tempo (BPM)", bpm.toString(), InputType.TYPE_CLASS_NUMBER)
        val meter = field("Time signature (e.g. 4/4, 6/8, 7/8)", timeSig)
        val measures = field("Starting measures", "200", InputType.TYPE_CLASS_NUMBER)

        box.addView(TextView(this).apply { text = "SCORE"; textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD) })
        box.addView(title, LinearLayout.LayoutParams(-1, dp(48f)))
        box.addView(tempo, LinearLayout.LayoutParams(-1, dp(48f)))
        box.addView(meter, LinearLayout.LayoutParams(-1, dp(48f)))
        box.addView(measures, LinearLayout.LayoutParams(-1, dp(48f)))
        box.addView(TextView(this).apply {
            text = "TRACKS"
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8f), 0, dp(2f))
        })

        val trackNames = arrayOf(
            "Guitar", "Guitar 2", "Guitar 3", "Guitar 7-string", "Guitar 8-string",
            "Bass 4-string", "Bass 5-string", "Keyboard / Piano", "Drums"
        )
        val selected = trackNames.map { it == "Guitar" }.toMutableList()
        trackNames.forEachIndexed { i, name ->
            val cb = android.widget.CheckBox(this).apply {
                text = name
                isChecked = selected[i]
                textSize = 13f
                setPadding(0, 0, 0, 0)
                setOnCheckedChangeListener { _, checked -> selected[i] = checked }
            }
            box.addView(cb, LinearLayout.LayoutParams(-1, dp(40f)))
        }

        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this)
            .setTitle("NEW FILE")
            .setView(scroll)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CREATE", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val t = tempo.text.toString().toIntOrNull()?.coerceIn(20, 300) ?: 120
                        val m = measures.text.toString().toIntOrNull()?.coerceIn(1, 2000) ?: 200
                        val sig = meter.text.toString().trim().ifBlank { "4/4" }
                        if (!sig.matches(Regex("[1-9][0-9]?/(2|4|8|16|32)"))) {
                            Toast.makeText(this@MainActivity, "Invalid time signature", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val chosen = trackNames.filterIndexed { i, _ -> selected[i] }
                        if (chosen.isEmpty()) {
                            Toast.makeText(this@MainActivity, "Select at least one track", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val p = sig.split('/')
                        newScore(
                            title.text.toString().trim().ifBlank { "Music Home" },
                            t, p[0].toInt(), p[1].toInt(), m, chosen
                        )
                        dismiss()
                    }
                }
            }.show()
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

    private fun newScore(
        title: String = "Music Home",
        tempoBpm: Int = 120,
        numerator: Int = 4,
        denominator: Int = 4,
        measureCount: Int = 200,
        trackNames: List<String> = listOf("Guitar")
    ) {
        try {
            val score = Score().apply { this.title = title }
            projectName = title
            bpm = tempoBpm
            timeSig = "$numerator/$denominator"
            val count = measureCount.coerceIn(1, 2000)
            repeat(count) {
                score.addMasterBar(MasterBar().apply {
                    timeSignatureNumerator = numerator.toDouble()
                    timeSignatureDenominator = denominator.toDouble()
                })
            }
            val names = trackNames.distinct().ifEmpty { listOf("Guitar") }
            for (name in names) {
                val track = alphaTab.model.Track().apply {
                    this.name = name
                    this.shortName = name
                    playbackInfo.program = when {
                        name.startsWith("Bass", true) -> 33.0
                        name.equals("Drums", true) -> 0.0
                        name.contains("Piano", true) || name.contains("Keyboard", true) -> 0.0
                        else -> 30.0
                    }
                    if (name.equals("Drums", true)) playbackInfo.primaryChannel = 9.0
                    isVisibleOnMultiTrack = true
                }
                val stringed = !name.contains("Piano", true) && !name.contains("Keyboard", true) && !name.equals("Drums", true)
                val strings = when {
                    name.equals("Bass 5-string", true) -> 5.0
                    name.startsWith("Bass", true) -> 4.0
                    name.equals("Guitar 8-string", true) -> 8.0
                    name.equals("Guitar 7-string", true) -> 7.0
                    else -> 6.0
                }
                val staff = alphaTab.model.Staff().apply {
                    showStandardNotation = true
                    showTablature = stringed
                    if (stringed) {
                        stringTuning = alphaTab.model.Tuning.getDefaultTuningFor(strings)
                            ?: throw IllegalStateException("No default tuning available for $name")
                        stringTuning.finish()
                    }
                }
                track.addStaff(staff)
                repeat(count) {
                    val bar = Bar()
                    staff.addBar(bar)
                    val voice = alphaTab.model.Voice()
                    bar.addVoice(voice)
                    val duration = when (denominator) {
                        2 -> Duration.Half
                        8 -> Duration.Eighth
                        16 -> Duration.Sixteenth
                        32 -> Duration.ThirtySecond
                        else -> Duration.Quarter
                    }
                    repeat(numerator.coerceIn(1, 32)) {
                        voice.addBeat(Beat().apply {
                            this.duration = duration
                            dots = 0.0
                            tupletNumerator = -1.0
                            tupletDenominator = -1.0
                            isEmpty = true
                        })
                    }
                }
                score.addTrack(track)
            }
            val settings = alphaTabView?.settings ?: return
            score.finish(settings)
            currentScore = score
            alphaTabView?.api?.renderScore(score)
            alphaTabView?.api?.render()
            prepareMidiForCurrentScore("new-score")
            noteEditor?.resetSelection()
            statusView?.text = "New score • $title • $timeSig • ${names.size} track(s)"
        } catch (t: Throwable) {
            showImportError("New Score", t)
        }
    }

    private fun prepareMidiForCurrentScore(reason: String) {
        val api = alphaTabView?.api ?: return
        try {
            api.loadMidiForScore()
            android.util.Log.i(
                "EARAM_PLAYER",
                "MIDI prepared from current Score; reason=" + reason +
                    "; readyForPlayback=" + api.isReadyForPlayback
            )
        } catch (t: Throwable) {
            android.util.Log.e("EARAM_PLAYER", "MIDI preparation failed; reason=" + reason, t)
            statusView?.let { view ->
                runOnUiThread {
                    view.text = "MIDI preparation failed • " + (t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    private fun normalizeImportedTracks(score: Score) {
        for (track in score.tracks.toList()) {
            for (staff in track.staves.toList()) {
                if (staff.isStringed) {
                    staff.showStandardNotation = true
                    staff.showTablature = true
                }
            }
        }
        try {
            score.finish(alphaTabView?.settings ?: return)
        } catch (t: Throwable) {
            android.util.Log.w("EARAM_IMPORT", "Track display normalization finish failed", t)
        }
    }

    private fun addTrackDialog() {
        val choices = arrayOf(
            "Guitar 2", "Guitar 3", "Guitar 7-string", "Guitar 8-string",
            "Bass 4-string", "Bass 5-string", "Keyboard / Piano", "Drums"
        )
        AlertDialog.Builder(this)
            .setTitle("ADD TRACK")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> addEditableTrack("Guitar 2", 30.0, 6, true)
                    1 -> addEditableTrack("Guitar 3", 30.0, 6, true)
                    2 -> addEditableTrack("Guitar 7-string", 30.0, 7, true)
                    3 -> addEditableTrack("Guitar 8-string", 30.0, 8, true)
                    4 -> addEditableTrack("Bass 4-string", 33.0, 4, true)
                    5 -> addEditableTrack("Bass 5-string", 33.0, 5, true)
                    6 -> addEditableTrack("Keyboard / Piano", 0.0, 0, false)
                    7 -> addEditableTrack("Drums", 0.0, 0, false)
                }
            }
            .show()
    }

    private fun addEditableTrack(name: String, midiProgram: Double, stringCount: Int, stringed: Boolean) {
        try {
            val score = currentScore ?: alphaTabView?.api?.score
                ?: throw IllegalStateException("No Score is loaded")
            val settings = alphaTabView?.settings
                ?: throw IllegalStateException("AlphaTab settings are not initialized")
            val templateStaff = score.tracks.firstOrNull()?.staves?.firstOrNull()
                ?: throw IllegalStateException("The Score has no staff to copy")
            val track = alphaTab.model.Track().apply {
                this.name = name
                this.shortName = name
                playbackInfo.program = midiProgram
                if (name.equals("Drums", true)) playbackInfo.primaryChannel = 9.0
                isVisibleOnMultiTrack = true
            }
            val staff = alphaTab.model.Staff().apply {
                showStandardNotation = true
                showTablature = stringed
                if (stringed) {
                    stringTuning = alphaTab.model.Tuning.getDefaultTuningFor(stringCount.toDouble())
                        ?: throw IllegalStateException("No default tuning available for $name")
                    stringTuning.finish()
                }
            }
            track.addStaff(staff)
            for (sourceBar in templateStaff.bars.toList()) {
                val newBar = Bar()
                staff.addBar(newBar)
                val sourceVoices = sourceBar.voices.toList()
                if (sourceVoices.isEmpty()) {
                    val voice = alphaTab.model.Voice()
                    newBar.addVoice(voice)
                    addQuarterRestBeats(voice)
                } else {
                    for (sourceVoice in sourceVoices) {
                        val voice = alphaTab.model.Voice()
                        newBar.addVoice(voice)
                        val sourceBeats = sourceVoice.beats.toList()
                        if (sourceBeats.isEmpty()) {
                            addQuarterRestBeats(voice)
                        } else {
                            for (sourceBeat in sourceBeats) {
                                voice.addBeat(Beat().apply {
                                    duration = sourceBeat.duration
                                    dots = sourceBeat.dots
                                    tupletNumerator = sourceBeat.tupletNumerator
                                    tupletDenominator = sourceBeat.tupletDenominator
                                    isEmpty = true
                                })
                            }
                        }
                    }
                }
            }
            score.addTrack(track)
            score.finish(settings)
            currentScore = score
            val newIndex = score.tracks.toList().lastIndex
            alphaTabView?.api?.renderScore(score, alphaTab.collections.DoubleList(newIndex.toDouble()))
            alphaTabView?.api?.render()
            prepareMidiForCurrentScore("add-track")
            noteEditor?.selectTrackFromUi(newIndex)
            statusView?.text = "Added " + name + " • track " + score.tracks.toList().size
        } catch (t: Throwable) {
            showImportError("Add Track", t)
        }
    }

    private fun renderAllTracks(score: Score) {
        val api = alphaTabView?.api ?: return
        val rendered = alphaTab.collections.List<alphaTab.model.Track>()
        score.tracks.toList().forEach { rendered.push(it) }
        api.renderTracks(rendered)
    }

    private fun importTab() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/octet-stream",
                "application/x-earam-tab",
                "text/plain"
            ))
        }
        startActivityForResult(intent, 4107)
    }

    private fun handleIncomingFileIntent(incoming: Intent?) {
        if (incoming == null || incoming.action != Intent.ACTION_VIEW) return
        val uri = incoming.data ?: return
        try {
            val readFlag = incoming.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (readFlag != 0 && (incoming.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                try { contentResolver.takePersistableUriPermission(uri, readFlag) } catch (_: Throwable) { }
            }
            val fileName = displayNameForUri(uri)
            statusView?.text = "Importing • $fileName"
            importScore(uri, fileName)
        } catch (t: Throwable) {
            showImportError("Open TAB", t)
        }
    }

    private fun displayNameForUri(uri: Uri): String {
        val queried = contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            else null
        }
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "IMPORT TAB"
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

        normalizeImportedTracks(parsed)

        currentScore = parsed
        projectName = parsed.title.ifBlank { fileName.substringBeforeLast('.') }

        runOnUiThread {
            titleView?.text = projectName

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
                // Replace the previous score cleanly, then render every imported track.
                view.api.stop()
                noteEditor?.resetSelection()
                renderAllTracks(parsed)
                statusView?.text =
                    "Imported • " + trackCount + " tracks • " + masterBarCount + " measures"
            } catch (t: Throwable) {
                showImportError("AlphaTab render", t)
            }
        }

        // Android alphaTab 1.8.4 loads its bundled SONiVOX SoundFont automatically.
    }

    private fun showImportError(stage: String, error: Throwable) {
        showDetailedImportError(stage, error, error.message ?: error.javaClass.name)
    }

    private fun showDetailedImportError(stage: String, error: Throwable, detail: String) {
        AlertDialog.Builder(this)
            .setTitle("$stage failed")
            .setMessage(detail)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 4107 || resultCode != RESULT_OK || data?.data == null) return

        val uri = data.data!!
        val name = displayNameForUri(uri)

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
        private val beatFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x12000000
        }
        private val beatLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            color = 0xFFFF5A00.toInt()
        }
        private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            color = 0xFFFF5A00.toInt()
        }
        private val beatRect = RectF()
        private val noteRect = RectF()
        private var hasBeat = false
        private var hasNote = false

        fun showBeatCursor(centerX: Float, top: Float, bottom: Float) {
            if (!centerX.isFinite() || !top.isFinite() || !bottom.isFinite() || bottom <= top) {
                hasBeat = false
                invalidate()
                return
            }
            val half = maxOf(5f, 6f * resources.displayMetrics.density)
            beatRect.set(centerX - half, top, centerX + half, bottom)
            hasBeat = true
            invalidate()
        }

        fun showNoteCursor(left: Float, top: Float, width: Float, height: Float) {
            if (!left.isFinite() || !top.isFinite() || !width.isFinite() || !height.isFinite()) {
                hasNote = false
                invalidate()
                return
            }
            val w = width.coerceIn(14f, 60f)
            val h = height.coerceIn(14f, 60f)
            noteRect.set(left - 4f, top - 4f, left + w + 4f, top + h + 4f)
            hasNote = true
            invalidate()
        }

        fun hideCursor() {
            hasBeat = false
            hasNote = false
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (hasBeat) {
                canvas.drawRoundRect(beatRect, 3f, 3f, beatFillPaint)
                canvas.drawLine(beatRect.centerX(), beatRect.top, beatRect.centerX(), beatRect.bottom, beatLinePaint)
            }
            if (hasNote) canvas.drawRoundRect(noteRect, 4f, 4f, notePaint)
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
        fun barUsedTicks(bar: Bar, voiceIndex: Int = 0, excluding: Beat? = null): Long =
            bar.voices.toList().getOrNull(voiceIndex)?.beats?.toList()
                ?.filter { it !== excluding }?.sumOf { beatTicks(it) } ?: 0L
        fun remainingTicks(bar: Bar, voiceIndex: Int = 0, excluding: Beat? = null): Long =
            (barCapacityTicks(bar) - barUsedTicks(bar, voiceIndex, excluding)).coerceAtLeast(0L)
        fun candidateTicks(duration: Duration, dots: Int, tupletNumerator: Int, tupletDenominator: Int): Long {
            var ticks = durationTicks(duration)
            when (dots.coerceIn(0, 2)) { 1 -> ticks += ticks / 2; 2 -> ticks += (ticks / 4) * 3 }
            if (tupletNumerator >= 0 && tupletDenominator > 0) ticks = (ticks * tupletDenominator.toLong()) / tupletNumerator.toLong()
            return ticks.coerceAtLeast(1L)
        }
        fun fits(bar: Bar, voiceIndex: Int, beat: Beat, duration: Duration, dots: Int, tupletNumerator: Int, tupletDenominator: Int): Boolean =
            candidateTicks(duration, dots, tupletNumerator, tupletDenominator) <= remainingTicks(bar, voiceIndex, beat)
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
        var selectedBarIndex: Int = 0
            private set
        var currentBeatIndex: Int = 0
            private set
        var currentStringIndex: Int = 1
            private set
        var currentTrackIndex: Int = 0
            private set
        var currentVoiceIndex: Int = 0
            private set
        private var copiedFret: Int? = null
        private var copiedBar: Bar? = null
        var onSelectionChanged: (() -> Unit)? = null

        private fun pushUndoSnapshot() {
            // Intentionally empty on Android AlphaTab 1.8.4: no supported full-score serializer.
            // This hook keeps mutation sites centralized without exposing a nonfunctional Undo UI.
        }

        fun resetSelection() {
            currentBarIndex = 0
            selectedBarIndex = 0
            currentBeatIndex = 0
            currentStringIndex = 1
            currentTrackIndex = 0.coerceAtMost((score.api.score?.tracks?.toList()?.size ?: 1) - 1)
            currentVoiceIndex = 0
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

            score.api.beatMouseDown.on { beat ->
                try {
                    val song = score.api.score ?: return@on
                    val clickedTrack = beat.voice.bar.staff.track
                    currentTrackIndex = clickedTrack.index.toInt().coerceIn(0, song.tracks.toList().lastIndex)
                    val staff = clickedTrack.staves.firstOrNull() ?: return@on
                    val bar = beat.voice.bar
                    val barIndex = staff.bars.toList().indexOf(bar)
                    val beatIndex = beat.voice.beats.toList().indexOf(beat)
                    if (barIndex < 0 || beatIndex < 0) return@on
                    currentBarIndex = barIndex
                    selectedBarIndex = barIndex
                    currentVoiceIndex = beat.voice.index.toInt().coerceIn(0, 3)
                    currentBeatIndex = beatIndex
                    armed = true
                    pendingFret = ""
                    updateCursor()
                    score.requestFocus()
                    updateStatus("SELECTED BAR " + (currentBarIndex + 1) +
                        " • BEAT " + (currentBeatIndex + 1) +
                        " • STRING " + currentStringIndex)
                    onSelectionChanged?.invoke()
                } catch (t: Throwable) {
                    android.util.Log.e("EARAM_SELECTION", "beat selection failed", t)
                }
            }

            score.api.noteMouseDown.on { note ->
                try {
                    val song = score.api.score ?: return@on
                    val clickedTrack = note.beat.voice.bar.staff.track
                    currentTrackIndex = clickedTrack.index.toInt().coerceIn(0, song.tracks.toList().lastIndex)
                    val staff = clickedTrack.staves.firstOrNull() ?: return@on
                    val bar = note.beat.voice.bar
                    val barIndex = staff.bars.toList().indexOf(bar)
                    val beatIndex = note.beat.voice.beats.toList().indexOf(note.beat)
                    if (barIndex < 0 || beatIndex < 0) return@on
                    currentBarIndex = barIndex
                    selectedBarIndex = barIndex
                    currentVoiceIndex = note.beat.voice.index.toInt().coerceIn(0, 3)
                    currentBeatIndex = beatIndex
                    currentStringIndex = (maxStringIndex() + 1 - note.string.toInt())
                        .coerceIn(1, maxStringIndex())
                    armed = true
                    pendingFret = ""
                    updateCursor()
                    score.requestFocus()
                    onSelectionChanged?.invoke()
                } catch (t: Throwable) {
                    android.util.Log.e("EARAM_SELECTION", "note selection failed", t)
                }
            }
        }

        private fun bars(): List<alphaTab.model.Bar>? =
            score.api.score?.tracks?.toList()?.getOrNull(currentTrackIndex)?.staves?.firstOrNull()?.bars?.toList()

        fun selectVoiceFromUi(index: Int) {
            val max = bars()?.getOrNull(selectedBarIndex)?.voices?.toList()?.lastIndex ?: 0
            currentVoiceIndex = index.coerceIn(0, max)
            currentBeatIndex = 0
            armed = true
            pendingFret = ""
            updateCursor()
            updateStatus("VOICE " + (currentVoiceIndex + 1))
            onSelectionChanged?.invoke()
        }

        fun addVoiceFromUi() {
            try {
                val song = score.api.score ?: return
                val staff = song.tracks.toList().getOrNull(currentTrackIndex)?.staves?.firstOrNull() ?: return
                val existing = staff.bars.firstOrNull()?.voices?.toList()?.size ?: 0
                if (existing >= 4) { updateStatus("Maximum 4 voices per bar"); return }
                for (bar in staff.bars.toList()) {
                    val voice = alphaTab.model.Voice()
                    val source = bar.voices.firstOrNull()?.beats?.toList().orEmpty()
                    for (b in source) voice.addBeat(Beat().apply {
                        duration = b.duration
                        dots = b.dots
                        tupletNumerator = b.tupletNumerator
                        tupletDenominator = b.tupletDenominator
                        isEmpty = true
                    })
                    if (source.isEmpty()) repeat(4) {
                        voice.addBeat(Beat().apply {
                            duration = Duration.Quarter
                            dots = 0.0
                            tupletNumerator = -1.0
                            tupletDenominator = -1.0
                            isEmpty = true
                        })
                    }
                    bar.addVoice(voice)
                }
                currentVoiceIndex = existing
                song.finish(score.settings)
                renderAndLog("add-voice")
                updateStatus("VOICE " + (currentVoiceIndex + 1) + " added")
                onSelectionChanged?.invoke()
            } catch (t: Throwable) {
                updateStatus("Add voice failed • " + (t.message ?: t.javaClass.simpleName))
            }
        }

        fun showTupletDialog() {
            val labels = arrayOf("Off", "3:2 Triplet", "5:4 Quintuplet", "6:4 Sextuplet", "7:4 Septuplet")
            AlertDialog.Builder(activity).setTitle("TUPLET").setItems(labels) { _, which ->
                val beat = currentBeat() ?: return@setItems
                val pair = when (which) {
                    0 -> Pair(-1, -1)
                    1 -> Pair(3, 2)
                    2 -> Pair(5, 4)
                    3 -> Pair(6, 4)
                    else -> Pair(7, 4)
                }
                setCurrentDuration(beat.duration, beat.dots.toInt(), pair.first, pair.second)
            }.show()
        }

        fun copyCurrentNoteFromUi() {
            val note = currentBeat()?.getNoteOnString(alphaTabString(currentStringIndex).toDouble())
            if (note == null) { updateStatus("Nothing to copy on current string"); return }
            copiedFret = note.fret.toInt()
            updateStatus("Copied fret " + copiedFret)
        }

        fun pasteCurrentNoteFromUi() {
            val fret = copiedFret ?: run { updateStatus("Clipboard is empty"); return }
            writeFret(fret)
            updateStatus("Pasted fret " + fret)
        }

        private fun currentVoice(): alphaTab.model.Voice? =
            bars()?.getOrNull(currentBarIndex)?.voices?.toList()?.getOrNull(currentVoiceIndex)

        private fun currentBeat(): alphaTab.model.Beat? =
            currentVoice()?.beats?.toList()?.getOrNull(currentBeatIndex)

        fun showScoreInfoDialog() {
            val song = score.api.score ?: return
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(20f), activity.dp(4f), activity.dp(20f), 0)
            }
            fun f(h: String, v: String) = EditText(activity).apply {
                hint = h
                setText(v)
                setSingleLine(true)
            }
            val title = f("Title", song.title)
            val artist = f("Artist", song.artist)
            val album = f("Album", song.album)
            val copyright = f("Copyright", song.copyright)
            val transcriber = f("Transcriber", song.tab)
            listOf(title, artist, album, copyright, transcriber).forEach {
                panel.addView(it, LinearLayout.LayoutParams(-1, activity.dp(46f)))
            }
            AlertDialog.Builder(activity)
                .setTitle("SCORE INFO")
                .setView(panel)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("APPLY") { _, _ ->
                    song.title = title.text.toString()
                    song.artist = artist.text.toString()
                    song.album = album.text.toString()
                    song.copyright = copyright.text.toString()
                    song.tab = transcriber.text.toString()
                    activity.projectName = song.title.ifBlank { "Music Home" }
                    score.api.score?.finish(score.settings)
                    score.api.render()
                    onSelectionChanged?.invoke()
                    updateStatus("Score information updated")
                }.show()
        }

        fun showLyricsDialog() {
            val track = score.api.score?.tracks?.toList()?.getOrNull(currentTrackIndex) ?: return
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(20f), activity.dp(4f), activity.dp(20f), 0)
            }
            val start = EditText(activity).apply {
                hint = "Start bar (1-based)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText("1")
                setSingleLine(true)
            }
            val raw = EditText(activity).apply {
                hint = "Lyrics — use spaces for syllables, + to join"
                setText("")
                minLines = 4
                gravity = Gravity.TOP
            }
            panel.addView(start, LinearLayout.LayoutParams(-1, activity.dp(46f)))
            panel.addView(raw, LinearLayout.LayoutParams(-1, activity.dp(110f)))
            AlertDialog.Builder(activity)
                .setTitle("TRACK LYRICS")
                .setView(panel)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("APPLY") { _, _ ->
                    val lyrics = alphaTab.model.Lyrics()
                    lyrics.startBar = ((start.text.toString().toIntOrNull() ?: 1) - 1).coerceAtLeast(0).toDouble()
                    lyrics.text = raw.text.toString()
                    lyrics.finish(true)
                    val lyricList = alphaTab.collections.List<alphaTab.model.Lyrics>()
                     lyricList.push(lyrics)
                     track.applyLyrics(lyricList)
                    score.api.score?.finish(score.settings)
                    score.api.render()
                    updateStatus("Lyrics updated")
                }.show()
        }

        fun showTimelineDialog() {
            val song = score.api.score ?: return
            val master = song.masterBars.toList().getOrNull(selectedBarIndex) ?: return
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(20f), activity.dp(4f), activity.dp(20f), 0)
            }
            fun field(hint: String, value: String) = EditText(activity).apply {
                this.hint = hint
                setText(value)
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
            }
            val num = field("Numerator", master.timeSignatureNumerator.toInt().toString())
            val den = field("Denominator (2,4,8,16)", master.timeSignatureDenominator.toInt().toString())
            val tempo = field("Tempo BPM (0 = keep)", "")
            val keys = arrayOf("Cb","Gb","Db","Ab","Eb","Bb","F","C","G","D","A","E","B","F#","C#")
            val keySpinner = android.widget.Spinner(activity)
            keySpinner.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, keys)
            val currentKey = master.keySignature.ordinal.coerceIn(0, 14)
            keySpinner.setSelection(currentKey)
            val modes = arrayOf("Major","Minor")
            val modeSpinner = android.widget.Spinner(activity)
            modeSpinner.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, modes)
            modeSpinner.setSelection(if (master.keySignatureType == KeySignatureType.Minor) 1 else 0)
            panel.addView(num, LinearLayout.LayoutParams(-1, activity.dp(46f)))
            panel.addView(den, LinearLayout.LayoutParams(-1, activity.dp(46f)))
            panel.addView(keySpinner, LinearLayout.LayoutParams(-1, activity.dp(46f)))
            panel.addView(modeSpinner, LinearLayout.LayoutParams(-1, activity.dp(46f)))
            panel.addView(tempo, LinearLayout.LayoutParams(-1, activity.dp(46f)))
            AlertDialog.Builder(activity)
                .setTitle("TIME / KEY / TEMPO • BAR " + (selectedBarIndex + 1))
                .setView(panel)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("APPLY") { _, _ ->
                    try {
                        val n = num.text.toString().toInt().coerceIn(1, 32)
                        val d = den.text.toString().toInt()
                        if (d !in listOf(1,2,4,8,16,32)) throw IllegalArgumentException("Denominator must be 1, 2, 4, 8, 16 or 32")
                        master.timeSignatureNumerator = n.toDouble()
                        master.timeSignatureDenominator = d.toDouble()
                        master.timeSignatureCommon = n == 4 && d == 4
                        master.keySignature = KeySignature.values()[keySpinner.selectedItemPosition]
                        master.keySignatureType = if (modeSpinner.selectedItemPosition == 1) KeySignatureType.Minor else KeySignatureType.Major
                        val tempoValue = tempo.text.toString().toDoubleOrNull()
                        if (tempoValue != null && tempoValue > 0) {
                            master.tempoAutomations.splice(0.0, master.tempoAutomations.length)
                            master.tempoAutomations.push(Automation.buildTempoAutomation(false, 0.0, tempoValue, tempoValue, true))
                        }
                        song.finish(score.settings)
                        renderAndLog("timeline-bar")
                        updateStatus("Bar " + (selectedBarIndex + 1) + " • " + n + "/" + d + " • " + keys[keySpinner.selectedItemPosition])
                    } catch (t: Throwable) {
                        updateStatus("Timeline edit failed • " + (t.message ?: t.javaClass.simpleName))
                    }
                }.show()
        }

        private fun cloneBarForScore(source: Bar, master: MasterBar): Bar {
            val cloned = Bar()
            cloned.barLineLeft = source.barLineLeft
            cloned.barLineRight = source.barLineRight
            cloned.clef = source.clef
            cloned.clefOttava = source.clefOttava
            cloned.keySignature = source.keySignature
            cloned.keySignatureType = source.keySignatureType
            val sourceVoices = source.voices.toList()
            if (sourceVoices.isEmpty()) {
                val voice = alphaTab.model.Voice()
                cloned.addVoice(voice)
                voice.addBeat(Beat().apply {
                    duration = Duration.Quarter
                    dots = 0.0
                    tupletNumerator = -1.0
                    tupletDenominator = -1.0
                    isEmpty = true
                })
                return cloned
            }
            for (sourceVoice in sourceVoices) {
                val voice = alphaTab.model.Voice()
                cloned.addVoice(voice)
                for (sourceBeat in sourceVoice.beats.toList()) {
                    val beat = Beat().apply {
                        duration = sourceBeat.duration
                        dots = sourceBeat.dots
                        tupletNumerator = sourceBeat.tupletNumerator
                        tupletDenominator = sourceBeat.tupletDenominator
                        isEmpty = sourceBeat.isEmpty
                    }
                    for (sourceNote in sourceBeat.notes.toList()) {
                        beat.addNote(Note().apply {
                            string = sourceNote.string
                            fret = sourceNote.fret
                            dynamics = sourceNote.dynamics
                            isGhost = sourceNote.isGhost
                            isDead = sourceNote.isDead
                            isPalmMute = sourceNote.isPalmMute
                            isLetRing = sourceNote.isLetRing
                            isStaccato = sourceNote.isStaccato
                            isHammerPullOrigin = sourceNote.isHammerPullOrigin
                        })
                    }
                    voice.addBeat(beat)
                }
            }
            return cloned
        }

        fun copyCurrentBarFromUi() {
            try {
                val song = score.api.score ?: return
                val source = song.tracks.toList().getOrNull(currentTrackIndex)
                    ?.staves?.firstOrNull()?.bars?.toList()?.getOrNull(selectedBarIndex)
                    ?: throw IllegalStateException("No current bar")
                copiedBar = cloneBarForScore(source, song.masterBars.toList().getOrNull(selectedBarIndex)
                    ?: throw IllegalStateException("No current master bar"))
                updateStatus("Copied bar " + (selectedBarIndex + 1))
            } catch (t: Throwable) {
                updateStatus("Copy bar failed • " + (t.message ?: t.javaClass.simpleName))
            }
        }

        fun pasteBarToCurrentFromUi() {
            try {
                val source = copiedBar ?: run {
                    updateStatus("No copied bar")
                    return
                }
                val song = score.api.score ?: return
                val targetIndex = selectedBarIndex
                pushUndoSnapshot()
                val track = song.tracks.toList().getOrNull(currentTrackIndex)
                    ?: throw IllegalStateException("No current track")
                for (staff in track.staves.toList()) {
                        val target = staff.bars.toList().getOrNull(targetIndex) ?: continue
                        val sourceVoices = source.voices.toList()
                        val targetVoices = target.voices.toList()
                        for (vi in targetVoices.indices) {
                            val tv = targetVoices[vi]
                            for (tb in tv.beats.toList()) {
                                for (note in tb.notes.toList()) tb.removeNote(note)
                                tb.isEmpty = true
                            }
                            val sv = sourceVoices.getOrNull(vi) ?: continue
                            val targetBeats = tv.beats.toList()
                            val sourceBeats = sv.beats.toList()
                            for (bi in targetBeats.indices) {
                                val tb = targetBeats[bi]
                                val sb = sourceBeats.getOrNull(bi) ?: continue
                                tb.duration = sb.duration
                                tb.dots = sb.dots
                                tb.tupletNumerator = sb.tupletNumerator
                                tb.tupletDenominator = sb.tupletDenominator
                                for (sn in sb.notes.toList()) {
                                    tb.addNote(Note().apply {
                                        string = sn.string
                                        fret = sn.fret
                                        dynamics = sn.dynamics
                                        isGhost = sn.isGhost
                                        isDead = sn.isDead
                                        isPalmMute = sn.isPalmMute
                                        isLetRing = sn.isLetRing
                                        isStaccato = sn.isStaccato
                                        isHammerPullOrigin = sn.isHammerPullOrigin
                                    })
                                }
                                tb.isEmpty = tb.notes.toList().isEmpty()
                                tb.finish(score.settings, null)
                            }
                        }
                }
                song.finish(score.settings)
                renderAndLog("paste-bar")
                updateCursor()
                onSelectionChanged?.invoke()
                updateStatus("Pasted copied bar into bar " + (targetIndex + 1))
            } catch (t: Throwable) {
                updateStatus("Paste bar failed • " + (t.message ?: t.javaClass.simpleName))
            }
        }

        fun addMeasureFromUi() {
            pushUndoSnapshot()
            if (createNextMeasures(1)) {
                updateStatus("Measure added • total " + (score.api.score?.masterBars?.toList()?.size ?: 0))
                onSelectionChanged?.invoke()
            }
        }

        fun duplicateCurrentBarToEndFromUi() {
            pushUndoSnapshot()
            try {
                val song = score.api.score ?: return
                val sourceMaster = song.masterBars.toList().getOrNull(selectedBarIndex)
                    ?: throw IllegalStateException("No current measure")
                val sourceIndex = selectedBarIndex
                val newMaster = MasterBar().apply {
                    timeSignatureNumerator = sourceMaster.timeSignatureNumerator
                    timeSignatureDenominator = sourceMaster.timeSignatureDenominator
                    timeSignatureCommon = sourceMaster.timeSignatureCommon
                    keySignature = sourceMaster.keySignature
                    keySignatureType = sourceMaster.keySignatureType
                    repeatCount = 0.0
                    isRepeatStart = false
                    isDoubleBar = false
                }
                song.addMasterBar(newMaster)

                for (track in song.tracks.toList()) {
                    for (staff in track.staves.toList()) {
                        val sourceBar = staff.bars.toList().getOrNull(sourceIndex)
                            ?: continue
                        staff.addBar(cloneBarForScore(sourceBar, newMaster))
                    }
                }

                song.finish(score.settings)
                selectedBarIndex = song.masterBars.toList().lastIndex
                currentBeatIndex = 0
                currentStringIndex = 1
                armed = true
                pendingFret = ""
                renderAndLog("duplicate-bar")
                updateCursor()
                onSelectionChanged?.invoke()
                updateStatus("Duplicated bar " + (sourceIndex + 1) + " → bar " + (selectedBarIndex + 1))
            } catch (t: Throwable) {
                updateStatus("Duplicate bar failed • " + (t.message ?: t.javaClass.simpleName))
            }
        }

        fun clearCurrentBarFromUi() {
            pushUndoSnapshot()
            try {
                val song = score.api.score ?: return
                val index = selectedBarIndex
                val track = song.tracks.toList().getOrNull(currentTrackIndex)
                    ?: throw IllegalStateException("No current track")
                var cleared = 0
                for (staff in track.staves.toList()) {
                    val bar = staff.bars.toList().getOrNull(index) ?: continue
                    for (voice in bar.voices.toList()) {
                        for (beat in voice.beats.toList()) {
                            for (note in beat.notes.toList()) beat.removeNote(note)
                            beat.isEmpty = true
                            beat.finish(score.settings, null)
                        }
                    }
                    cleared++
                }
                song.finish(score.settings)
                renderAndLog("clear-bar")
                updateCursor()
                onSelectionChanged?.invoke()
                updateStatus("Selected bar " + (index + 1) + " cleared • " + cleared + " staff(s)")
            } catch (t: Throwable) {
                updateStatus("Clear bar failed • " + (t.message ?: t.javaClass.simpleName))
            }
        }
        fun deleteCurrentBarFromUi() {
            val song = score.api.score ?: return
            val count = song.masterBars.toList().size
            if (count <= 1) {
                updateStatus("At least one measure must remain")
                return
            }
            val index = selectedBarIndex.coerceIn(0, count - 1)
            try {
                pushUndoSnapshot()
                song.masterBars.splice(index.toDouble(), 1.0)
                for (track in song.tracks.toList()) {
                    for (staff in track.staves.toList()) {
                        if (index < staff.bars.toList().size) {
                            staff.bars.splice(index.toDouble(), 1.0)
                        }
                    }
                }
                val last = song.masterBars.toList().lastIndex
                selectedBarIndex = index.coerceAtMost(last)
                currentBarIndex = selectedBarIndex
                currentBeatIndex = 0
                song.finish(score.settings)
                renderAndLog("delete-bar")
                syncSelectedBarHighlight()
                updateCursor()
                onSelectionChanged?.invoke()
                updateStatus("Deleted selected bar • " + (selectedBarIndex + 1) + " is now selected")
            } catch (t: Throwable) {
                updateStatus("Delete bar failed • " + (t.message ?: t.javaClass.simpleName))
            }
        }

        fun showBarToolsDialog() {
            val song = score.api.score ?: return
            val masterBar = song.masterBars.toList().getOrNull(selectedBarIndex) ?: return
            val track = song.tracks.toList().getOrNull(currentTrackIndex)
                ?: return
            val staff = track.staves.firstOrNull() ?: return
            val selectedBar = staff.bars.toList().getOrNull(selectedBarIndex) ?: return
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(20f), activity.dp(4f), activity.dp(20f), 0)
            }
            val repeatCount = EditText(activity).apply {
                hint = "Repeat count (0 = none)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(if (masterBar.repeatCount > 0) masterBar.repeatCount.toInt().toString() else "0")
                setSingleLine(true)
            }
            val section = EditText(activity).apply {
                hint = "Section / Marker text"
                setSingleLine(true)
            }
            panel.addView(repeatCount, LinearLayout.LayoutParams(-1, activity.dp(46f)))
            panel.addView(section, LinearLayout.LayoutParams(-1, activity.dp(46f)))
            AlertDialog.Builder(activity)
                .setTitle("BAR TOOLS • " + (selectedBarIndex + 1))
                .setView(panel)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("APPLY") { _, _ ->
                    masterBar.repeatCount = repeatCount.text.toString().toIntOrNull()?.coerceIn(0, 99)?.toDouble() ?: 0.0
                    val txt = section.text.toString().trim()
                    if (txt.isNotEmpty()) {
                        val targetBeat = selectedBar.voices.toList().firstOrNull()?.beats?.toList()?.firstOrNull()
                        if (targetBeat != null) targetBeat.text = txt
                    }
                    song.rebuildRepeatGroups()
                    finishEditedScore("bar-tools")
                }.show()
        }

        fun showTuningDialog() {
            val track = score.api.score?.tracks?.toList()?.getOrNull(currentTrackIndex) ?: return
            val staff = track.staves.firstOrNull() ?: return
            val tuning = staff.stringTuning ?: run {
                updateStatus("This track has no string tuning")
                return
            }
            val values = tuning.tunings.toList()
            val inputs = values.mapIndexed { idx, value ->
                EditText(activity).apply {
                    hint = "String " + (idx + 1)
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(value.toString())
                    setSingleLine(true)
                }
            }
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(20f), activity.dp(4f), activity.dp(20f), 0)
            }
            inputs.forEach { panel.addView(it, LinearLayout.LayoutParams(-1, activity.dp(42f))) }
            AlertDialog.Builder(activity)
                .setTitle("CUSTOM TUNING • " + track.name)
                .setView(android.widget.ScrollView(activity).apply { addView(panel) })
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("APPLY") { _, _ ->
                    for (i in inputs.indices) {
                        val v = inputs[i].text.toString().toDoubleOrNull()
                        if (v != null) tuning.tunings[i] = v
                    }
                    tuning.isStandard = false
                    tuning.finish()
                    finishEditedScore("tuning")
                }.show()
        }

        fun showTrackMixerDialog() {
            val track = score.api.score?.tracks?.toList()?.getOrNull(currentTrackIndex) ?: return
            val staff = track.staves.firstOrNull()
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(20f), activity.dp(6f), activity.dp(20f), 0)
            }
            val nameField = EditText(activity).apply {
                setText(track.name)
                hint = "Track name"
                setSingleLine(true)
            }
            val transposeField = EditText(activity).apply {
                val p = staff?.transpositionPitch?.toInt() ?: 0
                setText(p.toString())
                hint = "Transpose (semitones)"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                setSingleLine(true)
            }
            val mute = android.widget.CheckBox(activity).apply {
                text = "Mute"
                isChecked = track.playbackInfo.isMute
            }
            val solo = android.widget.CheckBox(activity).apply {
                text = "Solo"
                isChecked = track.playbackInfo.isSolo
            }
            val volumeLabel = TextView(activity).apply { text = "Volume" }
            val volume = android.widget.SeekBar(activity).apply {
                max = 100
                progress = (track.playbackInfo.volume / 16.0 * 100.0).toInt().coerceIn(0, 100)
            }
            val panLabel = TextView(activity).apply { text = "Pan" }
            val pan = android.widget.SeekBar(activity).apply {
                max = 100
                progress = ((track.playbackInfo.balance / 16.0 * 100.0).toInt()).coerceIn(0, 100)
            }
            panel.addView(nameField, LinearLayout.LayoutParams(-1, activity.dp(48f)))
            panel.addView(transposeField, LinearLayout.LayoutParams(-1, activity.dp(48f)))
            panel.addView(mute, LinearLayout.LayoutParams(-1, activity.dp(40f)))
            panel.addView(solo, LinearLayout.LayoutParams(-1, activity.dp(40f)))
            panel.addView(volumeLabel, LinearLayout.LayoutParams(-1, activity.dp(26f)))
            panel.addView(volume, LinearLayout.LayoutParams(-1, activity.dp(44f)))
            panel.addView(panLabel, LinearLayout.LayoutParams(-1, activity.dp(26f)))
            panel.addView(pan, LinearLayout.LayoutParams(-1, activity.dp(44f)))

            AlertDialog.Builder(activity)
                .setTitle("TRACK MIXER")
                .setView(panel)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("APPLY") { _, _ ->
                    track.name = nameField.text.toString().trim().ifBlank { track.name }
                    track.shortName = track.name
                    track.playbackInfo.isMute = mute.isChecked
                    track.playbackInfo.isSolo = solo.isChecked
                    track.playbackInfo.volume = volume.progress / 100.0 * 16.0
                    track.playbackInfo.balance = pan.progress / 100.0 * 16.0
                    staff?.transpositionPitch = transposeField.text.toString().toIntOrNull()?.coerceIn(-24, 24)?.toDouble() ?: 0.0
                    score.api.score?.finish(score.settings)
                    score.api.render()
                    try { score.api.loadMidiForScore() } catch (_: Throwable) { }
                    onSelectionChanged?.invoke()
                    updateStatus("Track settings applied")
                }
                .show()
        }

        fun setPickStrokeFromUi(direction: String) {
            val beat = currentBeat() ?: return
            beat.pickStroke = when (direction.lowercase()) {
                "down" -> alphaTab.model.PickStroke.Down
                "up" -> alphaTab.model.PickStroke.Up
                else -> alphaTab.model.PickStroke.None
            }
            finishEditedScore("pick-stroke")
            updateStatus("Pick stroke • " + direction.uppercase())
        }

        fun makeCurrentRestFromUi() {
            val beat = currentBeat() ?: return
            for (note in beat.notes.toList()) beat.removeNote(note)
            beat.isEmpty = true
            finishEditedScore("rest")
            updateStatus("Rest • Bar " + (currentBarIndex + 1) + " • Beat " + (currentBeatIndex + 1))
        }

        fun toggleTieFromUi() {
            val beat = currentBeat() ?: return
            val currentNote = beat.getNoteOnString(alphaTabString(currentStringIndex).toDouble()) ?: run {
                updateStatus("Enter a note first")
                return
            }
            val previousBeat = when {
                currentBeatIndex > 0 -> bars()?.getOrNull(currentBarIndex)?.voices?.firstOrNull()?.beats?.toList()?.getOrNull(currentBeatIndex - 1)
                currentBarIndex > 0 -> bars()?.getOrNull(currentBarIndex - 1)?.voices?.toList()?.getOrNull(currentVoiceIndex)?.beats?.toList()?.lastOrNull()
                else -> null
            }
            val previousNote = previousBeat?.getNoteOnString(currentNote.string)
            if (previousNote == null) {
                updateStatus("No previous note on this string for tie")
                return
            }
            val enabled = !currentNote.isTieDestination
            if (enabled) {
                previousNote.tieDestination = currentNote
                currentNote.tieOrigin = previousNote
                currentNote.isTieDestination = true
            } else {
                previousNote.tieDestination = null
                currentNote.tieOrigin = null
                currentNote.isTieDestination = false
            }
            finishEditedScore("tie")
            updateStatus(if (enabled) "Tie ON" else "Tie OFF")
        }

        fun toggleRepeatStartFromUi() {
            val song = score.api.score ?: return
            val bar = song.masterBars.toList().getOrNull(selectedBarIndex) ?: return
            bar.isRepeatStart = !bar.isRepeatStart
            song.rebuildRepeatGroups()
            finishEditedScore("repeat-start")
            updateStatus(if (bar.isRepeatStart) "Repeat start ON" else "Repeat start OFF")
        }

        fun toggleDoubleBarFromUi() {
            val song = score.api.score ?: return
            val bar = song.masterBars.toList().getOrNull(selectedBarIndex) ?: return
            bar.isDoubleBar = !bar.isDoubleBar
            finishEditedScore("double-bar")
            updateStatus(if (bar.isDoubleBar) "Double bar ON" else "Double bar OFF")
        }

        fun showBendDialog() {
            val beat = currentBeat() ?: return
            val note = beat.getNoteOnString(alphaTabString(currentStringIndex).toDouble()) ?: run {
                updateStatus("Enter/select a note first")
                return
            }
            val types = arrayOf("None", "Bend", "Release", "Bend + Release", "Hold", "Pre-bend", "Pre-bend + Bend", "Pre-bend + Release")
            val values = arrayOf(
                alphaTab.model.BendType.None,
                alphaTab.model.BendType.Bend,
                alphaTab.model.BendType.Release,
                alphaTab.model.BendType.BendRelease,
                alphaTab.model.BendType.Hold,
                alphaTab.model.BendType.Prebend,
                alphaTab.model.BendType.PrebendBend,
                alphaTab.model.BendType.PrebendRelease
            )
            var selected = values.indexOf(note.bendType).coerceAtLeast(0)
            AlertDialog.Builder(activity)
                .setTitle("BEND")
                .setSingleChoiceItems(types, selected) { dialog, which ->
                    selected = which
                    dialog.dismiss()
                    note.bendType = values[selected]
                    finishEditedScore("bend")
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }

        fun showNoteEffectsDialog() {
            val beat = currentBeat() ?: return
            val note = beat.getNoteOnString(alphaTabString(currentStringIndex).toDouble()) ?: run {
                updateStatus("Enter/select a note first")
                return
            }
            val labels = arrayOf(
                "Hammer-on / Pull-off", "Palm mute", "Let ring", "Ghost note",
                "Dead note", "Staccato", "Vibrato (slight)", "Left-hand tap"
            )
            val checked = booleanArrayOf(
                note.isHammerPullOrigin, note.isPalmMute, note.isLetRing, note.isGhost,
                note.isDead, note.isStaccato,
                note.vibrato != alphaTab.model.VibratoType.None,
                note.isLeftHandTapped
            )
            AlertDialog.Builder(activity)
                .setTitle("NOTE EFFECTS")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    when (which) {
                        0 -> note.isHammerPullOrigin = isChecked
                        1 -> note.isPalmMute = isChecked
                        2 -> note.isLetRing = isChecked
                        3 -> note.isGhost = isChecked
                        4 -> note.isDead = isChecked
                        5 -> note.isStaccato = isChecked
                        6 -> note.vibrato = if (isChecked) alphaTab.model.VibratoType.Slight else alphaTab.model.VibratoType.None
                        7 -> note.isLeftHandTapped = isChecked
                    }
                    note.finish(score.settings, null)
                    beat.finish(score.settings, null)
                    score.api.score?.finish(score.settings)
                    score.api.render()
                }
                .setPositiveButton("DONE") { _, _ ->
                    finishEditedScore("note-effects")
                }
                .show()
        }

        fun showBeatEffectsDialog() {
            val beat = currentBeat() ?: return
            val labels = arrayOf("Slap", "Pop", "Tap", "Dead Slap", "Fade In", "Slashed", "Show Time", "Text / Annotation")
            val checked = booleanArrayOf(beat.slap, beat.pop, beat.tap, beat.deadSlapped, beat.fadeIn, beat.slashed, beat.showTimer, !beat.text.isNullOrBlank())
            AlertDialog.Builder(activity)
                .setTitle("BEAT EFFECTS")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    when (which) {
                        0 -> beat.slap = isChecked
                        1 -> beat.pop = isChecked
                        2 -> beat.tap = isChecked
                        3 -> beat.deadSlapped = isChecked
                        4 -> beat.fadeIn = isChecked
                        5 -> beat.slashed = isChecked
                        6 -> beat.showTimer = isChecked
                        7 -> {
                            if (isChecked) {
                                val input = EditText(activity).apply { setSingleLine(true); hint = "Beat text" }
                                AlertDialog.Builder(activity).setTitle("ANNOTATION").setView(input)
                                    .setPositiveButton("OK") { _, _ ->
                                        beat.text = input.text.toString()
                                        finishEditedScore("beat-text")
                                    }.setNegativeButton("CANCEL", null).show()
                            } else beat.text = null
                        }
                    }
                }
                .setPositiveButton("DONE") { _, _ -> finishEditedScore("beat-effects") }
                .show()
        }

        private fun finishEditedScore(reason: String) {
            score.api.score?.finish(score.settings)
            score.api.render()
            try { score.api.loadMidiForScore() } catch (_: Throwable) { }
            onSelectionChanged?.invoke()
            activity.runOnUiThread { updateStatus(reason.uppercase().replace('-', ' ')) }
        }

        private fun maxStringIndex(): Int =
            score.api.score?.tracks?.toList()?.getOrNull(currentTrackIndex)?.staves?.firstOrNull()?.tuning?.toList()?.size?.coerceAtLeast(1) ?: 6

        fun currentBeatDurationTicks(): Long = currentBeat()?.let { AlphaTabRhythmEngine.beatTicks(it) } ?: 0L
        fun currentBeatDuration(): Pair<Duration, Int> = currentBeat()?.let { Pair(it.duration, it.dots.toInt().coerceIn(0, 2)) } ?: Pair(Duration.Quarter, 0)
        fun currentBarCapacityTicks(): Long = bars()?.getOrNull(currentBarIndex)?.let { AlphaTabRhythmEngine.barCapacityTicks(it) } ?: 0L
        fun currentBarUsedTicks(): Long = bars()?.getOrNull(currentBarIndex)?.let { AlphaTabRhythmEngine.barUsedTicks(it, currentVoiceIndex) } ?: 0L

        private fun currentSelectedDuration(): Duration = currentBeatDuration().first
        private fun currentSelectedDots(): Int = currentBeatDuration().second
        private fun currentSelectedTupletNumerator(): Int =
            currentBeat()?.let { if (it.tupletNumerator >= 0 && it.tupletDenominator > 0) it.tupletNumerator.toInt() else -1 } ?: -1
        private fun currentSelectedTupletDenominator(): Int =
            currentBeat()?.let { if (it.tupletNumerator >= 0 && it.tupletDenominator > 0) it.tupletDenominator.toInt() else -1 } ?: -1

        fun setCurrentDuration(duration: Duration, dots: Int = 0, tupletNumerator: Int = -1, tupletDenominator: Int = -1): Boolean {
            pushUndoSnapshot()
            val beat = currentBeat() ?: return false
            val bar = bars()?.getOrNull(currentBarIndex) ?: return false
            if (!AlphaTabRhythmEngine.fits(bar, currentVoiceIndex, beat, duration, dots, tupletNumerator, tupletDenominator)) {
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
                for (track in song.tracks.toList()) for (staff in track.staves.toList()) {
                    val bar = Bar()
                    staff.addBar(bar)
                    val voiceCount = sourceBar.voices.toList().size.coerceAtLeast(1)
                    repeat(voiceCount) {
                        val voice = alphaTab.model.Voice()
                        bar.addVoice(voice)
                        addEmptyBeatsForTimeSignature(
                            voice,
                            master.timeSignatureNumerator.toInt().coerceIn(1, 32),
                            master.timeSignatureDenominator.toInt().coerceIn(1, 32)
                        )
                    }
                }
            }
            song.finish(score.settings)
            renderAndLog("create-next-measures")
            return true
        }

        private fun addEmptyBeatsForTimeSignature(
            voice: alphaTab.model.Voice,
            numerator: Int,
            denominator: Int
        ) {
            val duration = when (denominator) {
                1 -> Duration.Whole
                2 -> Duration.Half
                4 -> Duration.Quarter
                8 -> Duration.Eighth
                16 -> Duration.Sixteenth
                else -> Duration.ThirtySecond
            }
            repeat(numerator.coerceAtLeast(1)) {
                voice.addBeat(Beat().apply {
                    this.duration = duration
                    dots = 0.0
                    tupletNumerator = -1.0
                    tupletDenominator = -1.0
                    isEmpty = true
                })
            }
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

        fun selectTrackFromUi(index: Int) {
            val tracks = score.api.score?.tracks?.toList().orEmpty()
            if (index !in tracks.indices) return
            try {
                currentTrackIndex = index
                selectedBarIndex = 0
                currentBeatIndex = 0
                currentStringIndex = 1
                armed = true
                pendingFret = ""
                val rendered = alphaTab.collections.List<alphaTab.model.Track>()
                rendered.push(tracks[index])
                score.api.renderTracks(rendered)
                syncSelectedBarHighlight()
                updateCursor()
                updateStatus("TRACK " + (index + 1) + " • " + trackLabel())
                onSelectionChanged?.invoke()
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    status.text = "Track switch failed • " + (t.message ?: t.javaClass.simpleName)
                }
            }
        }

        private fun trackLabel(): String {
            val track = score.api.score?.tracks?.toList()?.getOrNull(currentTrackIndex)
            return track?.name?.ifBlank { track.shortName }?.ifBlank { "Track " + (currentTrackIndex + 1) }
                ?: "Track " + (currentTrackIndex + 1)
        }

        fun selectionInfoText(): String {
            val track = score.api.score?.tracks?.toList()?.getOrNull(currentTrackIndex)
            val label = track?.name?.ifBlank { track.shortName }?.ifBlank { "Track " + (currentTrackIndex + 1) }
                ?: "Track " + (currentTrackIndex + 1)
            return "♫" + (currentTrackIndex + 1) + " " + label +
                "  ·  B" + (selectedBarIndex + 1) +
                "  ·  V" + (currentVoiceIndex + 1) +
                "  ·  b" + (currentBeatIndex + 1) +
                "  ·  S" + currentStringIndex +
                "  ·  F" + currentFretLabel()
        }

        private fun moveBeat(delta: Int) {
            val bs = bars() ?: return
            if (bs.isEmpty()) return
            var b = currentBarIndex.coerceIn(0, bs.lastIndex)
            var beat = currentBeatIndex
            val step = if (delta < 0) -1 else 1

            repeat(kotlin.math.abs(delta)) {
                val count = bs[b].voices.toList().getOrNull(currentVoiceIndex)?.beats?.toList()?.size ?: 0
                if (count <= 0) return@repeat
                var candidate = beat + step

                if (candidate < 0) {
                    if (b == 0) return@repeat
                    b--
                    candidate = (bs[b].voices.toList().getOrNull(currentVoiceIndex)?.beats?.toList()?.size ?: 1) - 1
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
            onSelectionChanged?.invoke()
        }

        private fun moveToEdge(end: Boolean) {
            val bs = bars() ?: return
            if (bs.isEmpty()) return
            currentBarIndex = if (end) bs.lastIndex else 0
            val beats = bs[currentBarIndex].voices.toList().getOrNull(currentVoiceIndex)?.beats?.toList().orEmpty()
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
            onSelectionChanged?.invoke()
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
            (maxStringIndex() + 1 - uiString).coerceIn(1, maxStringIndex())

        private fun writeFret(fret: Int) {
            if (fret !in 0..24) return
            pushUndoSnapshot()
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
                onSelectionChanged?.invoke()
                // Stay on the same Beat after entering a fret. This is required for chords:
                // move up/down through strings and enter additional frets at the same rhythmic
                // position. Beat navigation is explicit via the left/right controls.
                updateStatus("Fret " + fret + " • Bar " + (currentBarIndex + 1) + " • Beat " + (currentBeatIndex + 1) + " • String " + currentStringIndex)
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
            // Editing mutates the live AlphaTab Score. AlphaTab's previously generated
            // MIDI does not automatically follow those mutations, so rebuild MIDI from
            // that same Score after every edit. This is the critical path that makes
            // newly entered frets audible without introducing a second music model.
            try {
                score.api.loadMidiForScore()
                updateStatus("MIDI updated • " + reason)
                android.util.Log.i(
                    "EARAM_PLAYER",
                    "MIDI rebuilt from edited Score; reason=" + reason +
                        "; readyForPlayback=" + score.api.isReadyForPlayback
                )
            } catch (t: Throwable) {
                android.util.Log.e("EARAM_PLAYER", "MIDI rebuild failed; reason=" + reason, t)
                activity.runOnUiThread {
                    status.text = "MIDI update failed • " + (t.message ?: t.javaClass.simpleName)
                }
            }
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
            pushUndoSnapshot()
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
            onSelectionChanged?.invoke()
            updateStatus(if (beat.isEmpty) "Beat cleared" else "Note deleted")
        }

        /**
         * Uses AlphaTab's own playback-range highlight as the visual editor cursor.
         * The cursor is therefore anchored to the real rendered Beat, not a fake grid.
         */
        fun refreshVisualCursor() {
            val beat = currentBeat() ?: run {
                overlay.hideCursor()
                return
            }
            try {
                val lookup = score.api.renderer.boundsLookup ?: run {
                    overlay.hideCursor()
                    return
                }
                val bounds = lookup.findBeat(beat) ?: run {
                    overlay.hideCursor()
                    return
                }
                val barBounds = bounds.barBounds.realBounds

                // BeatBounds.onNotesX is AlphaTab's authoritative horizontal timing coordinate.
                val cursorX = (bounds.onNotesX - score.scrollX).toFloat()
                overlay.showBeatCursor(
                    cursorX,
                    (barBounds.y - score.scrollY).toFloat(),
                    (barBounds.y + barBounds.h - score.scrollY).toFloat()
                )

                val targetString = alphaTabString(currentStringIndex)
                val noteBounds = bounds.notes?.toList()
                    ?.firstOrNull { it.note.string.toInt() == targetString }
                if (noteBounds != null) {
                    val nb = noteBounds.noteHeadBounds
                    overlay.showNoteCursor(
                        (nb.x - score.scrollX).toFloat(),
                        (nb.y - score.scrollY).toFloat(),
                        nb.w.toFloat(),
                        nb.h.toFloat()
                    )
                }

                android.util.Log.d(
                    "EARAM_CURSOR",
                    "bar=" + (selectedBarIndex + 1) +
                        " beat=" + (currentBeatIndex + 1) +
                        " string=" + currentStringIndex +
                        " onNotesX=" + bounds.onNotesX
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


        fun showTrackSelectorDialog() {
            val tracks = score.api.score?.tracks?.toList().orEmpty()
            if (tracks.isEmpty()) {
                updateStatus("No tracks available")
                return
            }
            val labels = Array(tracks.size + 1) { i ->
                if (i == 0) {
                    "ALL TRACKS"
                } else {
                    val trackIndex = i - 1
                    val name = tracks[trackIndex].name.ifBlank {
                        tracks[trackIndex].shortName.ifBlank { "Track " + (trackIndex + 1) }
                    }
                    if (trackIndex == currentTrackIndex) "✓ " + name else name
                }
            }
            AlertDialog.Builder(activity)
                .setTitle("TRACK")
                .setItems(labels) { _, which ->
                    if (which == 0) {
                        val all = alphaTab.collections.List<alphaTab.model.Track>()
                        tracks.forEach { all.push(it) }
                        score.api.renderTracks(all)
                        updateCursor()
                        updateStatus("All tracks")
                        onSelectionChanged?.invoke()
                    } else {
                        selectTrackFromUi(which - 1)
                    }
                }
                .show()
        }

        fun showVoiceSelectorDialog() {
            val max = bars()?.getOrNull(selectedBarIndex)?.voices?.toList()?.size?.coerceAtLeast(1) ?: 1
            val count = max.coerceAtMost(4)
            val labels = Array(count) { i ->
                if (i == currentVoiceIndex) "✓ VOICE " + (i + 1) else "VOICE " + (i + 1)
            }
            AlertDialog.Builder(activity)
                .setTitle("SELECT VOICE")
                .setItems(labels) { _, which -> selectVoiceFromUi(which) }
                .show()
        }

        fun showBarSelectionDialog() {
            val bs = bars().orEmpty()
            if (bs.isEmpty()) {
                updateStatus("No measures available")
                return
            }
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(20f), activity.dp(6f), activity.dp(20f), 0)
            }
            val field = EditText(activity).apply {
                hint = "Measure number (1-" + bs.size + ")"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText((selectedBarIndex + 1).toString())
                setSingleLine(true)
            }
            val info = TextView(activity).apply {
                text = "Selected measure: " + (selectedBarIndex + 1)
                setPadding(0, activity.dp(4f), 0, activity.dp(8f))
            }
            panel.addView(field, LinearLayout.LayoutParams(-1, activity.dp(48f)))
            panel.addView(info, LinearLayout.LayoutParams(-1, activity.dp(34f)))
            AlertDialog.Builder(activity)
                .setTitle("SELECT BAR")
                .setView(panel)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SELECT") { _, _ ->
                    val number = field.text.toString().toIntOrNull()
                    if (number == null || number !in 1..bs.size) {
                        updateStatus("Invalid measure number")
                    } else {
                        selectBarFromUi(number - 1)
                    }
                }
                .show()
        }

        fun selectBarFromUi(index: Int) {
            val bs = bars() ?: return
            if (index !in bs.indices) return
            selectedBarIndex = index
            currentBarIndex = index
            val beats = bs[index].voices.toList().getOrNull(currentVoiceIndex)?.beats?.toList().orEmpty()
            currentBeatIndex = if (beats.isEmpty()) 0 else currentBeatIndex.coerceIn(0, beats.lastIndex)
            armed = true
            pendingFret = ""
            syncSelectedBarHighlight()
            updateCursor()
            updateStatus("SELECTED BAR " + (selectedBarIndex + 1) + " • Beat " + (currentBeatIndex + 1))
            onSelectionChanged?.invoke()
        }

        fun syncSelectedBarHighlight() {
            try {
                val song = score.api.score ?: return
                val track = song.tracks.toList().getOrNull(currentTrackIndex) ?: return
                val staff = track.staves.firstOrNull() ?: return
                val bar = staff.bars.toList().getOrNull(selectedBarIndex) ?: return
                val beats = bar.voices.toList().firstOrNull { it.beats.toList().isNotEmpty() }
                    ?.beats?.toList().orEmpty()
                if (beats.isEmpty()) {
                    score.api.clearPlaybackRangeHighlight()
                } else {
                    score.api.highlightPlaybackRange(beats.first(), beats.last())
                }
            } catch (t: Throwable) {
                android.util.Log.e("EARAM_SELECTION", "native selected-bar highlight failed", t)
            }
        }



        fun playCurrentBeatFromUi() {
            val beat = currentBeat() ?: return
            if (!score.api.isReadyForPlayback) {
                updateStatus("Player is preparing…")
                return
            }
            try {
                score.api.playBeat(beat)
                updateStatus("Playing cursor beat • Bar " + (currentBarIndex + 1) + " • Beat " + (currentBeatIndex + 1))
            } catch (t: Throwable) {
                updateStatus("Beat playback failed • " + (t.message ?: t.javaClass.simpleName))
            }
        }

        fun showDurationDialog() {
            val labels = arrayOf("𝅝  WHOLE", "𝅗𝅥  HALF", "♩  QUARTER", "♪  EIGHTH", "𝅘𝅥𝅮  16TH", "𝅘𝅥𝅯  32ND", "♩.  DOTTED")
            AlertDialog.Builder(activity)
                .setTitle("NOTE DURATION")
                .setItems(labels) { _, which ->
                    when (which) {
                        0 -> setCurrentDuration(Duration.Whole)
                        1 -> setCurrentDuration(Duration.Half)
                        2 -> setCurrentDuration(Duration.Quarter)
                        3 -> setCurrentDuration(Duration.Eighth)
                        4 -> setCurrentDuration(Duration.Sixteenth)
                        5 -> setCurrentDuration(Duration.ThirtySecond)
                        6 -> {
                            val pair = currentBeatDuration()
                            setCurrentDuration(pair.first, (pair.second + 1).coerceAtMost(2))
                        }
                    }
                }.show()
        }

        fun showPickStrokeDialog() {
            AlertDialog.Builder(activity)
                .setTitle("PICK STROKE")
                .setItems(arrayOf("↓ DOWN", "↑ UP", "OFF")) { _, which ->
                    when (which) {
                        0 -> setPickStrokeFromUi("down")
                        1 -> setPickStrokeFromUi("up")
                        2 -> setPickStrokeFromUi("none")
                    }
                }
                .show()
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
                "♫" + (currentTrackIndex + 1) + " · V" + (currentVoiceIndex + 1) +
                " · B" + (selectedBarIndex + 1) + " · beat " + (currentBeatIndex + 1) +
                " · S" + currentStringIndex + " · F" + currentFretLabel()
            )
            activity.runOnUiThread { status.text = text }
        }
    }


}