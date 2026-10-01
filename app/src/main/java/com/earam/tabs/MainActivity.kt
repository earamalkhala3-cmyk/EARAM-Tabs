@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package com.earam.tabs

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.ViewModelProvider
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
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
import kotlin.math.roundToInt
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
import alphaTab.platform.IContainer

data class Caret(
    val trackIndex: Int,
    val measureIndex: Int,
    val beatIndex: Int,
    val stringIndex: Int
)

data class BeatHit(
    val measure: Int,
    val beat: Int,
    val rect: RectF,
    val tabTopY: Float,
    val stringSpacing: Float,
    val virtual: Boolean = false
)

data class BeatHitBarMeta(
    val measure: Int,
    val bar: Bar,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val system: Int
)

private class DurationSelectorTextView(context: Context) : TextView(context) {
    private val density: Float get() = resources.displayMetrics.density
    private val scaledDensity: Float get() = resources.displayMetrics.scaledDensity

    private var selectedDuration: Duration = Duration.Quarter
    private var selectedDots: Int = 0

    init {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(0xFFF3F0E8.toInt())
        isClickable = true
        isFocusable = true
        super.setText("Duration")
    }

    fun setDurationVisual(duration: Duration, dots: Int) {
        selectedDuration = duration
        selectedDots = dots.coerceIn(0, 2)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = currentTextColor
            textSize = 13f * scaledDensity
            typeface = typeface
            textAlign = Paint.Align.LEFT
        }

        val label = "Duration"
        val gap = 8f * density
        val iconWidth = 36f * density
        val totalWidth = labelPaint.measureText(label) + gap + iconWidth
        val left = (width - totalWidth).coerceAtLeast(10f * density) / 2f

        val metrics = labelPaint.fontMetrics
        val baseline = (height - metrics.ascent - metrics.descent) / 2f
        canvas.drawText(label, left, baseline, labelPaint)

        drawDurationIcon(
            canvas = canvas,
            left = left + labelPaint.measureText(label) + gap,
            centerY = height / 2f,
            width = iconWidth
        )
    }

    private fun drawDurationIcon(canvas: Canvas, left: Float, centerY: Float, width: Float) {
        val d = density
        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = currentTextColor
            strokeWidth = 1.8f * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.FILL
        }

        val noteHeadW = 12f * d
        val noteHeadH = 8f * d
        val headLeft = left + 3f * d
        val headTop = centerY + 5f * d
        val stemX = headLeft + noteHeadW - 1.2f * d
        val stemTop = centerY - 11f * d
        val stemBottom = centerY + 6f * d

        canvas.save()
        canvas.rotate(-18f, headLeft + noteHeadW / 2f, headTop + noteHeadH / 2f)

        val hollow = selectedDuration == Duration.Whole || selectedDuration == Duration.Half
        notePaint.style = if (hollow) Paint.Style.STROKE else Paint.Style.FILL
        notePaint.strokeWidth = 1.7f * d
        canvas.drawOval(
            RectF(headLeft, headTop, headLeft + noteHeadW, headTop + noteHeadH),
            notePaint
        )
        canvas.restore()

        if (selectedDuration != Duration.Whole) {
            notePaint.style = Paint.Style.STROKE
            notePaint.strokeWidth = 1.8f * d
            canvas.drawLine(stemX, headTop + 2f * d, stemX, stemTop, notePaint)

            val beamCount = when (selectedDuration) {
                Duration.Quarter, Duration.Half -> 0
                Duration.Eighth -> 1
                Duration.Sixteenth -> 2
                Duration.ThirtySecond -> 3
                Duration.SixtyFourth -> 4
                else -> 0
            }

            if (beamCount > 0) {
                notePaint.strokeWidth = 2f * d
                for (i in 0 until beamCount) {
                    val y = stemTop + i * 3.2f * d
                    canvas.drawLine(stemX, y, stemX + 13f * d, y, notePaint)
                }
            }
        }

        if (selectedDots > 0) {
            notePaint.style = Paint.Style.FILL
            val dotX = when (selectedDuration) {
                Duration.Whole -> headLeft + noteHeadW + 7f * d
                else -> stemX + 15f * d
            }
            val dotY = centerY + 4f * d
            canvas.drawCircle(dotX, dotY, 1.7f * d, notePaint)
            if (selectedDots > 1) {
                canvas.drawCircle(dotX + 5f * d, dotY, 1.7f * d, notePaint)
            }
        }
    }
}

class MainActivity : ComponentActivity() {
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
    private lateinit var session: EditorSessionViewModel
    private var ciCursorReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ViewModelProvider(this).get(EditorSessionViewModel::class.java)
        projectName = session.projectName
        bpm = session.bpm
        timeSig = session.timeSignature
        openEditor()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, incoming: Intent) {
                if (incoming.action == "com.earam.tabs.CI_CURSOR_TEST") {
                    window.decorView.postDelayed({ runCiCursorTest() }, 300L)
                }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter("com.earam.tabs.CI_CURSOR_TEST"),
            ContextCompat.RECEIVER_EXPORTED
        )
        ciCursorReceiver = receiver

        if (intent?.action == "com.earam.tabs.CI_CURSOR_TEST") {
            window.decorView.postDelayed({ runCiCursorTest() }, 900L)
        }

        // Activity recreation must not create a new empty score. The Score and all
        // editor/playback state live in the ViewModel and are rebound to this new view.
        window.decorView.post {
            if (intent?.action == Intent.ACTION_VIEW && intent?.data != null) {
                handleIncomingFileIntent(intent)
            } else if (session.score != null) {
                restoreEditorSession()
            } else {
                newScore()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        window.decorView.post { handleIncomingFileIntent(intent) }
    }

    override fun onDestroy() {
        ciCursorReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Throwable) { }
            ciCursorReceiver = null
        }
        // Do not call api.stop() here: rotation destroys the Activity and stop() would
        // reset the exact playback position we are trying to preserve.
        try {
            alphaTabView?.let { view ->
                session.score = currentScore ?: view.api.score ?: session.score
                session.tickPosition = view.api.tickPosition
                session.playbackSpeed = view.api.playbackSpeed
                session.zoom = view.settings.display.scale
                session.wasPlaying = view.api.playerState.toString().contains("Playing", true)
            }
        } catch (_: Throwable) { }
        try { alphaTabView?.api?.destroy() } catch (_: Throwable) { }
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
            setBackgroundColor(0xFF17191C.toInt())
        }

        fun surface(color: Int, radius: Float = 0f): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
            }

        fun iconButton(symbol: String, description: String, size: Float = 46f): TextView =
            TextView(this).apply {
                text = symbol
                contentDescription = description
                setTextColor(0xFFF3F0E8.toInt())
                textSize = 21f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = surface(0xFF24272B.toInt(), 10f)
                setPadding(0, 0, 0, 0)
            }

        fun setActive(view: TextView, active: Boolean) {
            view.setTextColor(if (active) 0xFFFF8A00.toInt() else 0xFFF3F0E8.toInt())
        }

        fun labelView(textValue: String, size: Float = 11f): TextView =
            TextView(this).apply {
                text = textValue
                setTextColor(0xFFB9BABD.toInt())
                textSize = size
                gravity = Gravity.CENTER_VERTICAL
            }

        fun panelButton(textValue: String, description: String? = null): TextView =
            TextView(this).apply {
                text = textValue
                if (description != null) contentDescription = description
                setTextColor(0xFFF3F0E8.toInt())
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), 0, dp(16f), 0)
                background = surface(0xFF25282C.toInt(), 12f)
                isClickable = true
                isFocusable = true
            }

        // Compact Earam bottom sheet: replaces the generic Android AlertDialog look for editor menus.
        fun showPanel(titleText: String, items: List<Pair<String, () -> Unit>>) {
            val dialog = android.app.Dialog(this)
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(12f))
                background = surface(0xFF202327.toInt(), 20f)
            }
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6f), 0, dp(4f), dp(8f))
            }
            val titleTextView = TextView(this).apply {
                text = titleText
                setTextColor(0xFFF3F0E8.toInt())
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
            }
            val close = iconButton("×", "Close", 40f).apply {
                textSize = 22f
                background = surface(0x00303030, 10f)
                setOnClickListener { dialog.dismiss() }
            }
            titleRow.addView(titleTextView, LinearLayout.LayoutParams(0, dp(40f), 1f))
            titleRow.addView(close, LinearLayout.LayoutParams(dp(40f), dp(40f)))
            container.addView(titleRow)

            items.forEach { (textValue, action) ->
                val item = panelButton(textValue)
                item.setOnClickListener {
                    dialog.dismiss()
                    action()
                }
                container.addView(item, LinearLayout.LayoutParams(-1, dp(48f)).apply {
                    bottomMargin = dp(6f)
                })
            }
            dialog.setContentView(container)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.setOnShowListener {
                dialog.window?.apply {
                    setLayout(-1, WindowManager.LayoutParams.WRAP_CONTENT)
                    setGravity(Gravity.BOTTOM)
                    addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    attributes = attributes.apply { dimAmount = 0.52f }
                }
            }
            dialog.show()
            dialog.window?.apply {
                setLayout(-1, WindowManager.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
            }
        }

        lateinit var score: AlphaTabView
        lateinit var editor: AlphaTabNoteEditor

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(8f), dp(10f), dp(8f))
            setBackgroundColor(0xFF151719.toInt())
        }

        val brand = TextView(this).apply {
            text = "Ea"
            setTextColor(0xFFF3F0E8.toInt())
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
            setTextColor(0xFFF3F0E8.toInt())
            textSize = 23f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val brandWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandWrap.addView(brand, LinearLayout.LayoutParams(-2, dp(32f)))
        brandWrap.addView(brandR, LinearLayout.LayoutParams(-2, dp(32f)))
        brandWrap.addView(brandEnd, LinearLayout.LayoutParams(-2, dp(32f)))

        val title = TextView(this).apply {
            text = projectName
            setTextColor(0xFFBFC0C2.toInt())
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(3f), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleView = title
        title.setOnLongClickListener {
            editor.setDebugModeFromUi(!editor.isDebugMode())
            true
        }
        title.contentDescription = "File title • long press for coordinate debug"

        val overflow = iconButton("⋮", "Open Earam menu", 42f).apply {
            textSize = 24f
            setOnClickListener {
                showPanel("Earam", listOf(
                    "File" to { showFileMenu() },
                    "Edit" to { showPanel("EDIT", listOf(
                        "Undo  ↶" to { editor.undoFromUi() },
                        "Redo  ↷" to { editor.redoFromUi() },
                        "Copy note" to { editor.copyCurrentNoteFromUi() },
                        "Paste note" to { editor.pasteCurrentNoteFromUi() },
                        "Delete note" to { editor.deleteCurrentNoteFromUi() }
                    )) },
                    "Track" to { showPanel("TRACK", listOf(
                        "Select track" to { editor.showTrackSelectorDialog() },
                        "Add track" to { addTrackDialog() },
                        "Track mixer" to { editor.showTrackMixerDialog() }
                    )) },
                    "Bar" to { showPanel("BAR", listOf(
                        "Select / go to bar" to { editor.showBarSelectionDialog() },
                        "Bar tools" to { editor.showBarToolsDialog() },
                        "+ Measure" to { editor.addMeasureFromUi() },
                        "Duplicate bar" to { editor.duplicateCurrentBarToEndFromUi() },
                        "Clear bar" to { editor.clearCurrentBarFromUi() },
                        "Delete bar" to { editor.deleteCurrentBarFromUi() },
                        "Time / key" to { editor.showTimelineDialog() }
                    )) },
                    "Note & effects" to { showPanel("NOTE", listOf(
                        "Play current beat" to { editor.playCurrentBeatFromUi() },
                        "Duration" to { editor.showDurationDialog() },
                        "Tuplet" to { editor.showTupletDialog() },
                        "Effects" to { editor.showNoteEffectsDialog() },
                        "Bend" to { editor.showBendDialog() },
                        "Pick stroke" to { editor.showPickStrokeDialog() },
                        "Rest / delete" to { editor.makeCurrentRestFromUi() }
                    )) },
                    "View" to { showPanel("VIEW", listOf(
                        "Score + TAB" to { score.settings.display.staveProfile = StaveProfile.ScoreTab; score.api.updateSettings(); score.api.render() },
                        "TAB only" to { score.settings.display.staveProfile = StaveProfile.Tab; score.api.updateSettings(); score.api.render() },
                        "Score only" to { score.settings.display.staveProfile = StaveProfile.Score; score.api.updateSettings(); score.api.render() },
                        "Zoom 72%" to { score.settings.display.scale = 0.72; session.zoom = 0.72; score.api.updateSettings(); score.api.render() },
                        "Zoom 85%" to { score.settings.display.scale = 0.85; session.zoom = 0.85; score.api.updateSettings(); score.api.render() },
                        "Zoom 100%" to { score.settings.display.scale = 1.0; session.zoom = 1.0; score.api.updateSettings(); score.api.render() }
                    )) }
                ))
            }
        }

        header.addView(brandWrap, LinearLayout.LayoutParams(-2, dp(42f)))
        header.addView(title, LinearLayout.LayoutParams(0, dp(42f), 1f))
        header.addView(overflow, LinearLayout.LayoutParams(dp(42f), dp(42f)))

        val status = TextView(this).apply {
            text = "Ready • $bpm BPM • $timeSig"
            setTextColor(0xFF8F9296.toInt())
            textSize = 10f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), 0, dp(16f), 0)
            setBackgroundColor(0xFF1C1F22.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusView = status

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            setBackgroundColor(0xFF202327.toInt())
        }
        val play = iconButton("▶", "Play / Pause").apply {
            textSize = 20f
            setOnClickListener {
                if (score.api.isReadyForPlayback) score.api.playPause()
                else status.text = "Player is preparing…"
            }
        }
        val stop = iconButton("■", "Stop").apply {
            textSize = 16f
            setOnClickListener {
                score.api.stop()
                editor.hidePlaybackCursor()
                setActive(play, false)
            }
        }
        val rewind = iconButton("↶", "Previous / rewind").apply {
            textSize = 20f
            setOnClickListener { editor.moveBeatFromUi(-1) }
        }
        val forward = iconButton("↷", "Next / forward").apply {
            textSize = 20f
            setOnClickListener { editor.moveBeatFromUi(1) }
        }
        lateinit var speed: TextView
        speed = TextView(this).apply {
            text = "1×"
            contentDescription = "Playback speed"
            setTextColor(0xFFF3F0E8.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            background = surface(0xFF2A2D31.toInt(), 10f)
            isClickable = true
            setOnClickListener {
                showPanel("PLAYBACK SPEED", listOf(
                    "0.5×" to { score.api.playbackSpeed = 0.50; session.playbackSpeed = 0.50; text = "0.5×"; setActive(speed, true) },
                    "0.75×" to { score.api.playbackSpeed = 0.75; session.playbackSpeed = 0.75; text = "0.75×"; setActive(speed, true) },
                    "1×" to { score.api.playbackSpeed = 1.00; session.playbackSpeed = 1.00; text = "1×"; setActive(speed, true) },
                    "1.25×" to { score.api.playbackSpeed = 1.25; session.playbackSpeed = 1.25; text = "1.25×"; setActive(speed, true) },
                    "1.5×" to { score.api.playbackSpeed = 1.50; session.playbackSpeed = 1.50; text = "1.5×"; setActive(speed, true) }
                ))
            }
        }

        transport.addView(rewind, LinearLayout.LayoutParams(dp(42f), dp(42f)))
        transport.addView(play, LinearLayout.LayoutParams(dp(48f), dp(42f)).apply { leftMargin = dp(3f); rightMargin = dp(3f) })
        transport.addView(stop, LinearLayout.LayoutParams(dp(42f), dp(42f)))
        transport.addView(speed, LinearLayout.LayoutParams(dp(54f), dp(34f)).apply { leftMargin = dp(10f); rightMargin = dp(10f) })
        transport.addView(forward, LinearLayout.LayoutParams(dp(42f), dp(42f)))

        val context = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), 0, dp(16f), 0)
            setBackgroundColor(0xFF292C30.toInt())
        }
        val selection = TextView(this).apply {
            text = "Bar 1  ·  Beat 1  ·  String —  ·  Fret —"
            setTextColor(0xFFD7D5CE.toInt())
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val track = TextView(this).apply {
            text = "♫ 1"
            setTextColor(0xFFFF8A00.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            isClickable = true
            contentDescription = "Select track"
            setOnClickListener { editor.showTrackSelectorDialog() }
        }
        val voice = TextView(this).apply {
            text = "V1"
            setTextColor(0xFFB9BABD.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            isClickable = true
            contentDescription = "Select voice"
            setOnClickListener { editor.showVoiceSelectorDialog() }
        }
        context.addView(track, LinearLayout.LayoutParams(dp(42f), dp(38f)))
        context.addView(voice, LinearLayout.LayoutParams(dp(40f), dp(38f)))
        context.addView(selection, LinearLayout.LayoutParams(0, dp(38f), 1f))

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            setBackgroundColor(0xFF24272B.toInt())
        }
        fun nav(symbol: String, description: String, action: () -> Unit): TextView =
            iconButton(symbol, description, 42f).apply {
                setOnClickListener { action() }
            }
        navigation.addView(nav("←", "Previous beat") { editor.moveBeatFromUi(-1) })
        navigation.addView(nav("↑", "Previous string") { editor.moveStringFromUi(-1) })
        navigation.addView(nav("↓", "Next string") { editor.moveStringFromUi(1) })
        navigation.addView(nav("→", "Next beat") { editor.moveBeatFromUi(1) })

        // Contextual editing strip. Fret digits stay visible as a compact, always-available
        // keypad so entering 0-9 never depends on finding a hidden mode.
        val editingStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            setBackgroundColor(0xFF191B1E.toInt())
        }

        // ONE duration control only. Choosing a duration closes the selector;
        // the control then shows the selected rhythmic symbol as the current input/edit value.
        val durationSelector = DurationSelectorTextView(this).apply {
            contentDescription = "Duration selector"
            background = surface(0xFF25282C.toInt(), 9f)
            setDurationVisual(Duration.Quarter, 0)
            setOnClickListener { editor.showDurationDialog() }
        }
        editingStrip.addView(durationSelector, LinearLayout.LayoutParams(0, dp(42f), 1f).apply {
            leftMargin = dp(2f); rightMargin = dp(2f)
        })

        val editMore = iconButton("⋯", "More note tools", 42f).apply {
            textSize = 22f
            setOnClickListener {
                showPanel("NOTE TOOLS", listOf(
                    "Dotted" to { status.text = "Dotted duration mode" },
                    "3 Triplet" to { status.text = "Triplet duration mode" },
                    "↓ Downstroke" to { editor.showPickStrokeDialog() },
                    "↑ Upstroke" to { editor.showPickStrokeDialog() },
                    "Effects" to { editor.showNoteEffectsDialog() },
                    "Bend / Vibrato / Slide" to { editor.showBendDialog() }
                ))
            }
        }
        editingStrip.addView(editMore, LinearLayout.LayoutParams(dp(46f), dp(42f)))

        val fretDigits = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            setBackgroundColor(0xFF1F2226.toInt())
        }
        for (digit in 0..9) {
            val d = TextView(this).apply {
                text = digit.toString()
                setTextColor(0xFFF3F0E8.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                background = surface(0xFF2B2F34.toInt(), 7f)
                isClickable = true
                isFocusable = false
                contentDescription = "Fret $digit"
                setOnClickListener { editor.enterDigitFromUi(digit) }
            }
            fretDigits.addView(d, LinearLayout.LayoutParams(dp(32f), dp(34f)).apply {
                leftMargin = dp(2f); rightMargin = dp(2f)
            })
        }

        val deleteFret = TextView(this).apply {
            text = "⌫"
            setTextColor(0xFFF3F0E8.toInt())
            textSize = 17f
            gravity = Gravity.CENTER
            background = surface(0xFF3A2B2B.toInt(), 7f)
            isClickable = true
            isFocusable = false
            contentDescription = "Delete note on selected string"
            setOnClickListener { editor.deleteCurrentNoteFromUi() }
        }
        fretDigits.addView(deleteFret, LinearLayout.LayoutParams(dp(40f), dp(34f)).apply {
            leftMargin = dp(5f); rightMargin = dp(2f)
        })

        score = AlphaTabView(this, null).apply {
            setBackgroundColor(0xFFFFFEFB.toInt())
            settings.display.layoutMode = LayoutMode.Page
            settings.display.staveProfile = StaveProfile.ScoreTab
            // Automatic page layout lets AlphaTab use the real rhythmic width of each
            // measure. A hard-coded 2 bars/system was causing dense systems around
            // short-note passages and made the page look like measures were merged.
            settings.display.barsPerRow = -1.0
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
            settings.player.enableElementHighlighting = false
            settings.player.bufferTimeInMilliseconds = 1000.0
            // Do not push renderer settings from inside the AlphaTabView constructor.
            // The view must first be attached to the window; early native renderer setup
            // was a startup-crash risk on Android 15.
            api.masterVolume = 0.70
        }

        alphaTabView = score
        statusView = status
        val scoreLayer = FrameLayout(this).apply {
            setBackgroundColor(0xFFFFFEFB.toInt())
            clipChildren = false
            clipToPadding = false
        }
        val editorOverlay = TabEditOverlayView(this).apply {
            isEnabled = false
            isClickable = false
            isFocusable = false
            elevation = dp(20f).toFloat()
            clipToOutline = false
        }
        scoreLayer.addView(score, FrameLayout.LayoutParams(-1, -1))
        scoreLayer.addView(editorOverlay, FrameLayout.LayoutParams(-1, -1))
        editorOverlay.bringToFront()
        editor = AlphaTabNoteEditor(this, score, status, editorOverlay)
        noteEditor = editor
        editorOverlay.setScrollProvider { editor.actualScrollOffsets() }
        editor.attach()
        editor.attachAlphaTabCursorLayer()

        fun refreshSelectionInfo() {
            selection.text = editor.selectionInfoText()
            val selectedDuration = editor.currentBeatDuration()
            durationSelector.setDurationVisual(selectedDuration.first, selectedDuration.second)
            setActive(durationSelector, true)
            track.text = "♫ " + (editor.currentTrackIndex + 1)
            voice.text = "V" + (editor.currentVoiceIndex + 1)
            setActive(track, true)
            setActive(voice, true)
            session.caret = Caret(
                editor.currentTrackIndex,
                editor.currentBarIndex,
                editor.currentBeatIndex,
                editor.currentStringIndex
            )
        }
        editor.onSelectionChanged = { refreshSelectionInfo() }

        score.api.postRenderFinished.on {
            runOnUiThread {
                try {
                    android.util.Log.d("EARAM_CARET", "postRenderFinished: rebuilding beatHits before caret draw")
                    editor.rebuildBeatHitsAfterLayout()
                    editor.refreshVisualCursor()
                    editor.invalidateCaretOverlay("postRenderFinished")
                    editor.logRenderState()
                    editor.logCoordinateDiagnostic("postRenderFinished")
                } catch (t: Throwable) {
                    android.util.Log.e("EARAM_RENDER", "post-render editor overlay failed", t)
                    // Rendering the score must never be allowed to terminate the Activity.
                }
            }
        }

        score.api.scoreLoaded.on { loaded ->
            normalizeImportedTracks(loaded)
            currentScore = loaded
            AlphaTabRhythmEngine.syncFromScore(loaded)
            debugAlphaTabTimeline(loaded)
            projectName = loaded.title.ifBlank { projectName }
            noteEditor?.resetSelection()
            runOnUiThread {
                title.text = projectName
                status.text = "Score loaded • preparing playback"
                refreshSelectionInfo()
            }
            // Do not block/kill startup while the renderer is still attaching its first page.
            // MIDI preparation is deferred until the UI has had a chance to finish opening.
            window.decorView.postDelayed({
                try {
                    score.api.loadMidiForScore()
                } catch (t: Throwable) {
                    android.util.Log.e("EARAM_PLAYER", "deferred loadMidiForScore failed", t)
                    runOnUiThread { status.text = "MIDI preparation failed • " + (t.message ?: t.javaClass.simpleName) }
                }
            }, 1200L)
        }

        score.api.error.on { error ->
            runOnUiThread { status.text = "AlphaTab error: " + (error.message ?: "unknown") }
        }

        score.api.soundFontLoaded.on {
            soundFontLoaded = true
            soundFontLoading = false
            android.util.Log.i("EARAM_PLAYER", "SoundFontLoaded=true")
        }

        score.api.playerReady.on {
            playerEngineReady = true
            soundFontLoaded = true
            soundFontLoading = false
            android.util.Log.i("EARAM_PLAYER", "playerReady=true; readyForPlayback=" + score.api.isReadyForPlayback)
            runOnUiThread {
                play.isEnabled = true
                status.text = if (score.api.isReadyForPlayback) "Player ready • $bpm BPM" else "Player initialized • PLAY will activate when ready"
            }
        }

        score.api.playerPositionChanged.on {
            session.tickPosition = score.api.tickPosition
        }

        // AlphaTab exposes the exact played Beat. Use it as the sole source for the
        // playback marker so the cursor advances beat-by-beat and is constrained to
        // the current measure instead of behaving like a six-measure system cursor.
        score.api.playedBeatChanged.on { playedBeat ->
            runOnUiThread { editor.logOfficialPlaybackCursor(playedBeat) }
        }

        score.api.playerFinished.on {
            runOnUiThread { editor.hidePlaybackCursor() }
        }

        score.api.playerStateChanged.on {
            session.wasPlaying = score.api.playerState.toString().contains("Playing", true)
            session.tickPosition = score.api.tickPosition
            runOnUiThread {
                play.text = if (session.wasPlaying) "❚❚" else "▶"
                setActive(play, session.wasPlaying)
                if (score.api.isReadyForPlayback) {
                    // Earam owns the playback cursor and anchors it to playedBeatChanged.
                    // Never let AlphaTab's system cursor move the page by multiple measures.
                    status.text = "Sound ready • $bpm BPM"
                }
            }
        }

        root.addView(header, LinearLayout.LayoutParams(-1, dp(58f)))
        root.addView(status, LinearLayout.LayoutParams(-1, dp(24f)))
        root.addView(transport, LinearLayout.LayoutParams(-1, dp(52f)))
        root.addView(context, LinearLayout.LayoutParams(-1, dp(40f)))
        root.addView(navigation, LinearLayout.LayoutParams(-1, dp(48f)))
        root.addView(scoreLayer, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(editingStrip, LinearLayout.LayoutParams(-1, dp(52f)))
        // Always visible: compact fret-entry keypad (0–9).
        root.addView(fretDigits, LinearLayout.LayoutParams(-1, dp(40f)))
        setContentView(root)

        // Initialize AlphaTab only after the complete view hierarchy is attached.
        // Keep this isolated so a renderer initialization exception cannot prevent
        // the Earam shell from opening.
        window.decorView.post {
            try {
                score.api.updateSettings()
                android.util.Log.i("EARAM_STARTUP", "AlphaTab renderer settings initialized after view attachment")
            } catch (t: Throwable) {
                android.util.Log.e("EARAM_STARTUP", "AlphaTab renderer initialization failed", t)
                status.text = "Renderer unavailable • Earam opened safely"
            }
        }
        play.isEnabled = true
    }

    /** Rebinds the ViewModel-owned Score to the newly created AlphaTabView after rotation. */
    private fun restoreEditorSession() {
        val score = session.score ?: return
        val view = alphaTabView ?: return
        try {
            currentScore = score
            projectName = session.projectName.ifBlank { score.title.ifBlank { "Music Home" } }
            bpm = session.bpm
            timeSig = session.timeSignature
            titleView?.text = projectName

            view.settings.display.scale = session.zoom.coerceIn(0.4, 2.0)
            view.settings.display.barsPerRow = -1.0
            view.api.playbackSpeed = session.playbackSpeed.coerceIn(0.25, 2.0)
            view.api.updateSettings()
            score.finish(view.settings)
            AlphaTabRhythmEngine.syncFromScore(score)
            renderAllTracks(score)
            view.api.render()
            view.api.loadMidiForScore()

            noteEditor?.resetSelection()
            noteEditor?.restoreCaretFromSession()
            statusView?.text = "Restored • $projectName • $timeSig"

            // Restore the exact musical position only after the new MIDI timeline is ready.
            window.decorView.postDelayed({
                try {
                    view.api.tickPosition = session.tickPosition.coerceIn(0.0, view.api.endTick)
                    view.api.scrollToCursor()
                    noteEditor?.refreshVisualCursor()
                    if (session.wasPlaying && view.api.isReadyForPlayback) view.api.play()
                } catch (t: Throwable) {
                    android.util.Log.w("EARAM_ROTATION", "Playback position restore deferred", t)
                }
            }, 250L)
        } catch (t: Throwable) {
            android.util.Log.e("EARAM_ROTATION", "Session restore failed", t)
            statusView?.text = "Session restore failed • ${t.message ?: t.javaClass.simpleName}"
        }
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

    private fun runCiCursorTest() {
        try {
            newScore("CI Cursor Geometry", 120, 4, 4, 8, listOf("Guitar"))
            window.decorView.postDelayed({
                try {
                    val editor = noteEditor ?: throw IllegalStateException("note editor missing")
                    editor.setCiTestCaret()
                    editor.writeFretFromUi(7)
                    editor.setDebugModeFromUi(true)
                    statusView?.text = "CI CURSOR TEST • B1 b1 S2"
                    window.decorView.postDelayed({
                        editor.refreshVisualCursor()
                        editor.refreshVisualCursor()
                        editor.logCoordinateDiagnostic("ci-screenshot-ready")
                    }, 1000L)
                } catch (t: Throwable) {
                    android.util.Log.e("EARAM_CI_CURSOR", "CI fixture failed", t)
                }
            }, 1200L)
        } catch (t: Throwable) {
            android.util.Log.e("EARAM_CI_CURSOR", "CI score creation failed", t)
        }
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
        val measures = field("Starting measures", "32", InputType.TYPE_CLASS_NUMBER)

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
        measureCount: Int = 32,
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
            session.score = score
            session.projectName = title
            session.bpm = tempoBpm
            session.timeSignature = "$numerator/$denominator"
            session.tickPosition = 0.0
            session.wasPlaying = false
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
        AlphaTabRhythmEngine.syncFromScore(parsed)
        debugAlphaTabTimeline(parsed)
        session.score = parsed
        session.sourceUri = uri.toString()
        session.sourceName = fileName
        projectName = parsed.title.ifBlank { fileName.substringBeforeLast('.') }
        session.projectName = projectName

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

    /** Derive PPQ from AlphaTab's finished Beat timing instead of assuming 960. */
    private fun alphaTabQuarterTicks(score: Score): Double {
        val beats = score.tracks.toList()
            .flatMap { it.staves.toList() }
            .flatMap { it.bars.toList() }
            .flatMap { it.voices.toList() }
            .flatMap { it.beats.toList() }
        val simple = beats.firstOrNull {
            it.dots <= 0.0 &&
                it.tupletNumerator < 0.0 &&
                it.tupletDenominator < 0.0
        } ?: return 1.0
        val multiplier = when (simple.duration) {
            Duration.DoubleWhole -> 8.0
            Duration.QuadrupleWhole -> 16.0
            Duration.Whole -> 4.0
            Duration.Half -> 2.0
            Duration.Quarter -> 1.0
            Duration.Eighth -> 0.5
            Duration.Sixteenth -> 0.25
            Duration.ThirtySecond -> 0.125
            Duration.SixtyFourth -> 0.0625
            Duration.OneHundredTwentyEighth -> 0.03125
            Duration.TwoHundredFiftySixth -> 0.015625
        }
        return simple.displayDuration / multiplier
    }

    /** AlphaTab is the authoritative musical timeline. */
    private fun debugAlphaTabTimeline(score: Score) {
        try {
            val track = score.tracks.toList().firstOrNull() ?: return
            val staff = track.staves.toList().firstOrNull() ?: return
            val masters = score.masterBars.toList()
            val bars = staff.bars.toList()
            android.util.Log.d("EARAM_GP3_TIMELINE", "===== AlphaTab GP timeline =====")
            for (i in masters.indices) {
                val master = masters[i]
                val bar = bars.getOrNull(i)
                val beats = bar?.voices?.toList()?.firstOrNull()?.beats?.toList().orEmpty()
                val startTick = beats.firstOrNull()?.absolutePlaybackStart ?: -1.0
                val totalDisplayTicks = beats.sumOf { it.displayDuration }
                val quarterTicks = alphaTabQuarterTicks(score)
                val expectedTicks = quarterTicks * 4.0 * master.timeSignatureNumerator / master.timeSignatureDenominator
                val diff = totalDisplayTicks - expectedTicks
                android.util.Log.d("EARAM_GP3_TIMELINE",
                    "measure=${i + 1} meter=${master.timeSignatureNumerator.toInt()}/${master.timeSignatureDenominator.toInt()} " +
                    "startTick=$startTick beatCount=${beats.size} total=$totalDisplayTicks expected=$expectedTicks diff=$diff")
                var lastStart = Double.NEGATIVE_INFINITY
                for ((bi, beat) in beats.withIndex()) {
                    val start = beat.absolutePlaybackStart
                    if (start < lastStart) android.util.Log.e("EARAM_GP3_TIMELINE",
                        "NON_MONOTONIC measure=${i + 1} beat=${bi + 1} start=$start previous=$lastStart")
                    lastStart = start
                    android.util.Log.v("EARAM_GP3_TIMELINE",
                        " beat=${bi + 1} start=$start displayDuration=${beat.displayDuration} duration=${beat.duration} " +
                        "dots=${beat.dots} tuplet=${beat.tupletNumerator}:${beat.tupletDenominator}")
                }
            }
            android.util.Log.d("EARAM_GP3_TIMELINE", "================================")
        } catch (t: Throwable) {
            android.util.Log.e("EARAM_GP3_TIMELINE", "Timeline diagnostics failed", t)
        }
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
        private val debugBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=2f*resources.displayMetrics.density; color=0xFFFF0000.toInt() }
        private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL; textSize=10f*resources.displayMetrics.scaledDensity; color=0xFFFF00FF.toInt() }
        private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL; color=0xEE111318.toInt() }
        private val bannerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL; textSize=9f*resources.displayMetrics.scaledDensity; color=0xFFFFE66D.toInt() }
        private val debugBars=mutableListOf<RectF>()
        private var debugBanner=""
        private var debugEnabled=false
        private var scrollProvider:(()->Pair<Float,Float>)?=null
        fun setScrollProvider(provider:()->Pair<Float,Float>){scrollProvider=provider}
        fun setDebugData(enabled:Boolean,bars:List<RectF>,label:String){debugEnabled=enabled;debugBars.clear();debugBars.addAll(bars);invalidate()}
        fun setDebugBanner(text:String){debugBanner=text;invalidate()}
        fun showBeatCaretContent(centerX:Float,centerY:Float,half:Float)=invalidate()
        fun showPlaybackCursorContent(centerX:Float,top:Float,bottom:Float)=invalidate()
        fun showNoteCursor(left:Float,top:Float,width:Float,height:Float)=invalidate()
        fun hideCursor()=invalidate()
        fun hidePlaybackCursor()=invalidate()
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            if(!debugEnabled)return
            val scroll=scrollProvider?.invoke() ?: (0f to 0f)
            canvas.save();canvas.translate(-scroll.first,-scroll.second)
            for((i,bar) in debugBars.withIndex()){canvas.drawRect(bar,debugBarPaint);canvas.drawText("BAR "+(i+1),bar.left+3f,bar.top+12f,debugTextPaint)}
            canvas.restore()
            if(debugBanner.isNotBlank()){
                val pad=8f*resources.displayMetrics.density
                val lineH=13f*resources.displayMetrics.scaledDensity
                val lines=debugBanner.split("\n")
                val boxH=pad*2f+lineH*lines.size
                canvas.drawRect(pad,pad,width.toFloat()-pad,pad+boxH,bannerPaint)
                lines.forEachIndexed{i,line->canvas.drawText(line,pad+6f,pad+lineH*(i+1)-2f,bannerTextPaint)}
            }
        }
    }


    private object AlphaTabRhythmEngine {
        var quarterTicks: Long = 1L
            private set
        fun syncFromScore(score: Score) {
            val simple = score.tracks.toList()
                .flatMap { it.staves.toList() }
                .flatMap { it.bars.toList() }
                .flatMap { it.voices.toList() }
                .flatMap { it.beats.toList() }
                .firstOrNull {
                    it.dots <= 0.0 &&
                        it.tupletNumerator < 0.0 &&
                        it.tupletDenominator < 0.0
                } ?: return
            val multiplier = when (simple.duration) {
                Duration.DoubleWhole -> 8.0
                Duration.QuadrupleWhole -> 16.0
                Duration.Whole -> 4.0
                Duration.Half -> 2.0
                Duration.Quarter -> 1.0
                Duration.Eighth -> 0.5
                Duration.Sixteenth -> 0.25
                Duration.ThirtySecond -> 0.125
                Duration.SixtyFourth -> 0.0625
                Duration.OneHundredTwentyEighth -> 0.03125
                Duration.TwoHundredFiftySixth -> 0.015625
            }
            quarterTicks = kotlin.math.round(simple.displayDuration / multiplier).toLong().coerceAtLeast(1L)
        }
        fun durationTicks(duration: Duration): Long = when (duration) {
            Duration.QuadrupleWhole -> quarterTicks * 16
            Duration.DoubleWhole -> quarterTicks * 8
            Duration.Whole -> quarterTicks * 4
            Duration.Half -> quarterTicks * 2
            Duration.Quarter -> quarterTicks
            Duration.Eighth -> quarterTicks / 2
            Duration.Sixteenth -> quarterTicks / 4
            Duration.ThirtySecond -> quarterTicks / 8
            Duration.SixtyFourth -> quarterTicks / 16
            Duration.OneHundredTwentyEighth -> quarterTicks / 32
            Duration.TwoHundredFiftySixth -> quarterTicks / 64
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
            return (m.timeSignatureNumerator.toLong().coerceAtLeast(1L) * quarterTicks * 4L) / m.timeSignatureDenominator.toLong().coerceAtLeast(1L)
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
                Quintuple(Duration.Whole, 0, -1, -1, quarterTicks * 4),
                Quintuple(Duration.Half, 1, -1, -1, quarterTicks * 3),
                Quintuple(Duration.DoubleWhole, 0, -1, -1, quarterTicks * 8),
                Quintuple(Duration.Half, 0, -1, -1, quarterTicks * 2),
                Quintuple(Duration.Quarter, 1, -1, -1, quarterTicks * 3 / 2),
                Quintuple(Duration.Quarter, 0, -1, -1, quarterTicks),
                Quintuple(Duration.Eighth, 1, -1, -1, quarterTicks * 3 / 4),
                Quintuple(Duration.Eighth, 0, -1, -1, quarterTicks / 2),
                Quintuple(Duration.Sixteenth, 1, -1, -1, quarterTicks * 3 / 8),
                Quintuple(Duration.Sixteenth, 0, -1, -1, quarterTicks / 4),
                Quintuple(Duration.ThirtySecond, 0, -1, -1, quarterTicks / 8),
                Quintuple(Duration.SixtyFourth, 0, -1, -1, quarterTicks / 16),
                Quintuple(Duration.Eighth, 0, 3, 2, quarterTicks / 3),
                Quintuple(Duration.Sixteenth, 0, 3, 2, quarterTicks / 6),
                Quintuple(Duration.ThirtySecond, 0, 3, 2, quarterTicks / 12)
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

    private class BeatSnapshot(
        val barIndex: Int,
        val voiceIndex: Int,
        val beatIndex: Int,
        val duration: Duration,
        val dots: Double,
        val tupletNumerator: Double,
        val tupletDenominator: Double,
        val isEmpty: Boolean,
        val notes: List<Pair<Double, Double>>
    )

    /** Phase 3: deterministic keyboard navigation over the real AlphaTab Score. */
    private inner class AlphaTabNoteEditor(
        private val activity: MainActivity,
        private val score: AlphaTabView,
        private val status: TextView,
        private val overlay: TabEditOverlayView
    ) {
        var caret: Caret = session.caret
            private set

        val currentBarIndex: Int get() = caret.measureIndex
        val selectedBarIndex: Int get() = caret.measureIndex
        val currentBeatIndex: Int get() = caret.beatIndex
        val currentStringIndex: Int get() = caret.stringIndex
        val currentTrackIndex: Int get() = caret.trackIndex
        var currentVoiceIndex: Int = 0
            private set
        private var copiedFret: Int? = null
        private var copiedBar: Bar? = null
        var onSelectionChanged: (() -> Unit)? = null

        /**
         * Command-level history for the editor's live AlphaTab Score.
         *
         * We deliberately snapshot only the affected Beat rather than cloning the whole Score.
         * That keeps Score as the single source of truth while making fret/duration/delete edits
         * genuinely undoable. Redo stores the state that existed immediately before restoration.
         */
        private val undoHistory = java.util.ArrayDeque<BeatSnapshot>()
        private val redoHistory = java.util.ArrayDeque<BeatSnapshot>()
        private var restoringHistory = false

        private fun setCaret(
            trackIndex: Int = caret.trackIndex,
            measureIndex: Int = caret.measureIndex,
            beatIndex: Int = caret.beatIndex,
            stringIndex: Int = caret.stringIndex,
            notify: Boolean = true
        ) {
            val song = score.api.score
            val trackMax = (song?.tracks?.toList()?.lastIndex ?: 0).coerceAtLeast(0)
            val track = trackIndex.coerceIn(0, trackMax)
            val bs = score.api.score?.tracks?.toList()?.getOrNull(track)?.staves?.firstOrNull()?.bars?.toList().orEmpty()
            val measure = measureIndex.coerceIn(0, bs.lastIndex.coerceAtLeast(0))
            val voice = currentVoiceIndex.coerceAtLeast(0)
            val beatCount = bs.getOrNull(measure)?.voices?.toList()?.getOrNull(voice)?.beats?.toList()?.size ?: 0
            val beat = if (beatCount > 0) beatIndex.coerceIn(0, beatCount - 1) else 0
            val string = stringIndex.coerceIn(1, maxStringIndex())
            caret = Caret(track, measure, beat, string)
            session.caret = caret
            armed = true
            pendingFret = ""
            if (notify) {
                updateCursor()
                invalidateCaretOverlay("setCaret")
                onSelectionChanged?.invoke()
            }
        }

        private fun captureCurrentBeat(): BeatSnapshot? {
            val beat = currentBeat() ?: return null
            return BeatSnapshot(
                currentBarIndex,
                currentVoiceIndex,
                currentBeatIndex,
                beat.duration,
                beat.dots,
                beat.tupletNumerator,
                beat.tupletDenominator,
                beat.isEmpty,
                beat.notes.toList().map { Pair(it.string, it.fret) }
            )
        }

        private fun pushUndoSnapshot() {
            if (restoringHistory) return
            captureCurrentBeat()?.let {
                undoHistory.addLast(it)
                while (undoHistory.size > 100) undoHistory.removeFirst()
                redoHistory.clear()
            }
        }

        private fun restoreSnapshot(snapshot: BeatSnapshot): Boolean {
            val bs = bars() ?: return false
            val bar = bs.getOrNull(snapshot.barIndex) ?: return false
            val beat = bar.voices.toList().getOrNull(snapshot.voiceIndex)
                ?.beats?.toList()?.getOrNull(snapshot.beatIndex) ?: return false

            beat.duration = snapshot.duration
            beat.dots = snapshot.dots
            beat.tupletNumerator = snapshot.tupletNumerator
            beat.tupletDenominator = snapshot.tupletDenominator

            beat.notes.toList().forEach { beat.removeNote(it) }
            snapshot.notes.forEach { (string, fret) ->
                beat.addNote(Note().apply {
                    this.string = string
                    this.fret = fret
                })
            }
            beat.isEmpty = snapshot.isEmpty || beat.notes.toList().isEmpty()
            beat.notes.toList().forEach { it.finish(score.settings, null) }
            beat.finish(score.settings, null)
            score.api.score?.finish(score.settings)

            currentVoiceIndex = snapshot.voiceIndex
            caret = caret.copy(measureIndex = snapshot.barIndex, beatIndex = snapshot.beatIndex)
            session.caret = caret
            armed = true
            pendingFret = ""
            renderAndLog("history")
            updateCursor()
            onSelectionChanged?.invoke()
            return true
        }

        fun undoFromUi() {
            if (undoHistory.isEmpty()) {
                updateStatus("Nothing to undo")
                return
            }
            val current = captureCurrentBeat()
            val previous = undoHistory.removeLast()
            restoringHistory = true
            try {
                if (current != null) redoHistory.addLast(current)
                if (restoreSnapshot(previous)) updateStatus("Undo")
            } finally {
                restoringHistory = false
            }
        }

        fun redoFromUi() {
            if (redoHistory.isEmpty()) {
                updateStatus("Nothing to redo")
                return
            }
            val current = captureCurrentBeat()
            val next = redoHistory.removeLast()
            restoringHistory = true
            try {
                if (current != null) undoHistory.addLast(current)
                if (restoreSnapshot(next)) updateStatus("Redo")
            } finally {
                restoringHistory = false
            }
        }

        fun resetSelection() {
            currentVoiceIndex = 0
            val track = 0.coerceAtMost((score.api.score?.tracks?.toList()?.size ?: 1) - 1)
            caret = Caret(track, 0, 0, 1)
            session.caret = caret
            armed = false
            pendingFret = ""
            updateCursor()
            updateStatus()
        }


        private var alphaTabCaret: IContainer? = null
        private var lastCaretRect: RectF? = null
        fun attachAlphaTabCursorLayer() {
            try {
                val cursors=score.api.uiFacade.createCursors() ?: throw IllegalStateException("AlphaTab cursor containers unavailable")
                val caretElement=score.api.uiFacade.createSelectionElement() ?: throw IllegalStateException("AlphaTab selection element unavailable")
                cursors.selectionWrapper.appendChild(caretElement)
                alphaTabCaret=caretElement
                android.util.Log.i("EARAM_ALPHA_CURSOR","editor caret attached to AlphaTab selectionWrapper")
            } catch(t:Throwable){android.util.Log.e("EARAM_ALPHA_CURSOR","native caret attach failed",t)}
        }
        fun setCiTestCaret(){caret=Caret(0,0,0,2);session.caret=caret;currentVoiceIndex=0;armed=true;pendingFret="";updateCursor()}
        private var armed = false
        private var pendingFret: String = ""
        private var pendingAtMs: Long = 0L
        private var inputGeneration: Long = 0L
        private var noteTouchSelectionPending = false

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
            score.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        noteTouchSelectionPending = false
                        false
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (noteTouchSelectionPending) {
                            noteTouchSelectionPending = false
                            true
                        } else {
                            handleScoreTouch(event.x, event.y)
                        }
                    }
                    else -> false
                }
            }

            score.api.beatMouseDown.on { beat ->
                try {
                    val song = score.api.score ?: return@on
                    val clickedTrack = beat.voice.bar.staff.track
                    val clickedTrackIndex = clickedTrack.index.toInt().coerceIn(0, song.tracks.toList().lastIndex)
                    caret = caret.copy(trackIndex = clickedTrackIndex)
                    session.caret = caret
                    val staff = clickedTrack.staves.firstOrNull() ?: return@on
                    val bar = beat.voice.bar
                    val barIndex = staff.bars.toList().indexOf(bar)
                    val beatIndex = beat.voice.beats.toList().indexOf(beat)
                    if (barIndex < 0 || beatIndex < 0) return@on
                    currentVoiceIndex = beat.voice.index.toInt().coerceIn(0, 3)
                    caret = Caret(currentTrackIndex, barIndex, beatIndex, currentStringIndex)
                    session.caret = caret
                    armed = true
                    pendingFret = ""

                    // A tapped AlphaTab Beat is the editor selection and playback origin.
                    try {
                        score.api.stop()
                        score.api.tickPosition = beat.absolutePlaybackStart
                        session.tickPosition = score.api.tickPosition
                    } catch (_: Throwable) { }

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
                    noteTouchSelectionPending = true
                    val song = score.api.score ?: return@on
                    val clickedTrack = note.beat.voice.bar.staff.track
                    val clickedTrackIndex = clickedTrack.index.toInt().coerceIn(0, song.tracks.toList().lastIndex)
                    caret = caret.copy(trackIndex = clickedTrackIndex)
                    session.caret = caret
                    val staff = clickedTrack.staves.firstOrNull() ?: return@on
                    val bar = note.beat.voice.bar
                    val barIndex = staff.bars.toList().indexOf(bar)
                    val beatIndex = note.beat.voice.beats.toList().indexOf(note.beat)
                    if (barIndex < 0 || beatIndex < 0) return@on
                    currentVoiceIndex = note.beat.voice.index.toInt().coerceIn(0, 3)
                    val uiString = (maxStringIndex() + 1 - note.string.toInt()).coerceIn(1, maxStringIndex())
                    caret = Caret(currentTrackIndex, barIndex, beatIndex, uiString)
                    session.caret = caret
                    armed = true
                    pendingFret = ""

                    // A tapped note is also the exact playback origin.
                    try {
                        score.api.stop()
                        score.api.tickPosition = note.beat.absolutePlaybackStart
                        session.tickPosition = score.api.tickPosition
                    } catch (_: Throwable) { }

                    updateCursor()
                    score.requestFocus()
                    updateStatus("SELECTED NOTE • B" + (currentBarIndex + 1) +
                        " • BEAT " + (currentBeatIndex + 1) +
                        " • STRING " + currentStringIndex +
                        " • FRET " + note.fret.toInt())
                    onSelectionChanged?.invoke()
                } catch (t: Throwable) {
                    android.util.Log.e("EARAM_SELECTION", "note selection failed", t)
                }
            }
        }

        private var coordinateDebugEnabled = false
        private val beatHits = mutableListOf<BeatHit>()
        private var stringSpacing: Float = 10f
        private var lastCaretPosition: Triple<Float, Float, Float>? = null

        private fun buildBeatHits() {
            try {
            beatHits.clear()
            val lookup = score.api.renderer.boundsLookup ?: return
            val song = score.api.score ?: return
            val track = song.tracks.toList().getOrNull(currentTrackIndex) ?: return
            val staff = track.staves.firstOrNull() ?: return
            val bars = staff.bars.toList()
            if (bars.isEmpty()) return

            data class NoteGeom(val system: Int, val y: Float, val uiString: Int)
            val noteGeoms = mutableListOf<NoteGeom>()
            val barMeta = mutableListOf<BeatHitBarMeta>()

            for ((mi, bar) in bars.withIndex()) {
                val master = song.masterBars.toList().getOrNull(mi) ?: continue
                val masterBounds = lookup.findMasterBar(master) ?: continue
                val masterBarCandidates = masterBounds.bars.toList()
                val barBounds = masterBarCandidates.firstOrNull { it.bar === bar }
                    ?: masterBarCandidates.getOrNull(0)
                    ?: continue
                val system = masterBounds.staffSystemBounds?.index?.toInt() ?: mi
                barMeta.add(
                    BeatHitBarMeta(
                        mi, bar,
                        barBounds.realBounds.x.toFloat(),
                        barBounds.realBounds.y.toFloat(),
                        barBounds.realBounds.w.toFloat(),
                        barBounds.realBounds.h.toFloat(),
                        system
                    )
                )

                val voice = bar.voices.toList().getOrNull(currentVoiceIndex)
                for (beat in voice?.beats?.toList().orEmpty()) {
                    val bb = lookup.findBeat(beat) ?: continue
                    for (nb in bb.notes?.toList().orEmpty()) {
                        val ui = (maxStringIndex() + 1 - nb.note.string.toInt()).coerceIn(1, maxStringIndex())
                        val nh = nb.noteHeadBounds
                        noteGeoms.add(
                            NoteGeom(
                                system,
                                nh.y.toFloat() + nh.h.toFloat() * 0.5f,
                                ui
                            )
                        )
                    }
                }
            }

            val systemSpacing = mutableMapOf<Int, Float>()
            val systemTop = mutableMapOf<Int, Float>()
            for ((system, notes) in noteGeoms.groupBy { it.system }) {
                val candidates = mutableListOf<Float>()
                for (i in notes.indices) for (j in i + 1 until notes.size) {
                    val ds = kotlin.math.abs(notes[i].uiString - notes[j].uiString)
                    if (ds > 0) {
                        val candidate = kotlin.math.abs(notes[i].y - notes[j].y) / ds
                        if (candidate.isFinite() && candidate in 3f..40f) candidates.add(candidate)
                    }
                }
                val spacing = candidates.sorted().let { if (it.isEmpty()) 10f else it[it.size / 2] }
                systemSpacing[system] = spacing
                val tops = notes.map { it.y - it.uiString * spacing }.sorted()
                if (tops.isNotEmpty()) systemTop[system] = tops[tops.size / 2]
            }

            for (meta in barMeta) {
                if (!systemTop.containsKey(meta.system)) {
                    systemTop[meta.system] = (meta.y + meta.h * 0.58f)
                }
                if (!systemSpacing.containsKey(meta.system)) {
                    systemSpacing[meta.system] = (meta.h * 0.075f).coerceIn(6f, 24f)
                }
                val top = systemTop[meta.system] ?: meta.y
                val spacing = systemSpacing[meta.system] ?: 10f
                val voice = meta.bar.voices.toList().getOrNull(currentVoiceIndex)
                val beats = voice?.beats?.toList().orEmpty()
                if (beats.isEmpty()) {
                    beatHits.add(
                        BeatHit(
                            meta.measure,
                            0,
                            RectF(meta.x, meta.y, meta.x + meta.w, meta.y + meta.h),
                            top,
                            spacing,
                            virtual = true
                        )
                    )
                    continue
                }
                for ((bi, beat) in beats.withIndex()) {
                    val bounds = lookup.findBeat(beat) ?: continue
                    beatHits.add(
                        BeatHit(
                            meta.measure,
                            bi,
                            RectF(
                                bounds.realBounds.x.toFloat(),
                                bounds.realBounds.y.toFloat(),
                                (bounds.realBounds.x + bounds.realBounds.w).toFloat(),
                                (bounds.realBounds.y + bounds.realBounds.h).toFloat()
                            ),
                            top,
                            spacing,
                            virtual = false
                        )
                    )
                }
            }
            stringSpacing = systemSpacing.values.firstOrNull() ?: 10f
            android.util.Log.d(
                "EARAM_CARET",
                "BeatHits rebuilt: count=" + beatHits.size +
                    " virtual=" + beatHits.count { it.virtual } +
                    " measures=" + beatHits.map { it.measure + 1 }.distinct().joinToString() +
                    " systems=" + systemTop.size +
                    " spacing=" + systemSpacing.values.joinToString() +
                    " scroll=" + score.scrollX + "," + score.scrollY +
                    " scale=" + score.settings.display.scale
            )
            } catch (t: Throwable) {
                beatHits.clear()
                android.util.Log.e("EARAM_CARET", "buildBeatHits failed", t)
            }
        }

        private fun hitTest(x: Float, y: Float): BeatHit? {
            val contentX = x + score.scrollX.toFloat()
            val contentY = y + score.scrollY.toFloat()
            return beatHits.firstOrNull { hit ->
                contentX >= hit.rect.left && contentX <= hit.rect.right &&
                    contentY >= hit.tabTopY &&
                    contentY <= hit.tabTopY + 5f * hit.stringSpacing
            }
        }

        private fun handleScoreTouch(x: Float, y: Float): Boolean {
            val hit = hitTest(x, y) ?: return false
            val maxString = maxStringIndex()
            val uiString = (((y + score.scrollY.toFloat() - hit.tabTopY) / hit.stringSpacing)
                .roundToInt() + 1).coerceIn(1, maxString)
            caret = Caret(currentTrackIndex, hit.measure, if (hit.virtual) 0 else hit.beat, uiString)
            session.caret = caret
            armed = true
            pendingFret = ""
            try {
                if (!hit.virtual) {
                    val beat = bars()?.getOrNull(hit.measure)?.voices?.toList()?.getOrNull(currentVoiceIndex)
                        ?.beats?.toList()?.getOrNull(hit.beat)
                    if (beat != null) {
                        score.api.stop()
                        score.api.tickPosition = beat.absolutePlaybackStart
                        session.tickPosition = score.api.tickPosition
                    }
                }
            } catch (_: Throwable) { }
            updateCursor()
            updateStatus()
            onSelectionChanged?.invoke()
            score.requestFocus()
            return true
        }

        private fun ensureRealBeatForCaret(): Beat? {
            val bar = bars()?.getOrNull(caret.measureIndex) ?: return null
            val voice = bar.voices.toList().getOrNull(currentVoiceIndex)
                ?: run {
                    val v = alphaTab.model.Voice()
                    bar.addVoice(v)
                    v
                }
            if (voice.beats.toList().isNotEmpty()) {
                return voice.beats.toList().getOrNull(caret.beatIndex.coerceAtLeast(0))
            }
            val duration = when (bar.masterBar.timeSignatureDenominator.toInt()) {
                1 -> Duration.Whole
                2 -> Duration.Half
                4 -> Duration.Quarter
                8 -> Duration.Eighth
                16 -> Duration.Sixteenth
                else -> Duration.ThirtySecond
            }
            voice.addBeat(Beat().apply {
                this.duration = duration
                dots = 0.0
                tupletNumerator = -1.0
                tupletDenominator = -1.0
                isEmpty = true
            })
            caret = caret.copy(beatIndex = 0)
            session.caret = caret
            score.api.score?.finish(score.settings)
            renderAndLog("materialize-empty-beat")
            return voice.beats.toList().firstOrNull()
        }

        private fun writeFretInternal(beat: Beat, fret: Int) {
            val song = score.api.score ?: return
            val alphaString = alphaTabString(caret.stringIndex)
            val existing = beat.getNoteOnString(alphaString.toDouble())
            if (existing != null) {
                existing.fret = fret.toDouble()
                existing.finish(score.settings, null)
            } else {
                val note = Note().apply {
                    string = alphaString.toDouble()
                    this.fret = fret.toDouble()
                }
                beat.addNote(note)
                note.finish(score.settings, null)
            }
            beat.isEmpty = beat.notes.toList().isEmpty()
            beat.finish(score.settings, null)
            song.finish(score.settings)
            window.decorView.post {
                buildBeatHits()
                refreshVisualCursor()
                invalidateCaretOverlay("writeFret")
            }
        }

        private fun bars(): List<alphaTab.model.Bar>? =
            score.api.score?.tracks?.toList()?.getOrNull(currentTrackIndex)?.staves?.firstOrNull()?.bars?.toList()

        fun selectVoiceFromUi(index: Int) {
            val max = bars()?.getOrNull(selectedBarIndex)?.voices?.toList()?.lastIndex ?: 0
            currentVoiceIndex = index.coerceIn(0, max)
            caret = caret.copy(beatIndex = 0)
            session.caret = caret
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
                caret = caret.copy(
                    measureIndex = song.masterBars.toList().lastIndex,
                    beatIndex = 0,
                    stringIndex = 1
                )
                session.caret = caret
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
                val target = index.coerceAtMost(last)
                caret = caret.copy(measureIndex = target, beatIndex = 0)
                session.caret = caret
                song.finish(score.settings)
                renderAndLog("delete-bar")
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
            val beat = currentBeat() ?: return false
            val bar = bars()?.getOrNull(currentBarIndex) ?: return false
            if (!AlphaTabRhythmEngine.fits(bar, currentVoiceIndex, beat, duration, dots, tupletNumerator, tupletDenominator)) {
                updateStatus("Duration does not fit • " + bar.masterBar.timeSignatureNumerator.toInt() + "/" + bar.masterBar.timeSignatureDenominator.toInt())
                return false
            }
            pushUndoSnapshot()
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
                caret = caret.copy(beatIndex = caret.beatIndex + 1)
                session.caret = caret
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
            val nextMeasure = currentBarIndex + 1
            caret = caret.copy(measureIndex = nextMeasure, beatIndex = 0)
            session.caret = caret
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
                caret = Caret(index, 0, 0, 1)
                session.caret = caret
                armed = true
                pendingFret = ""
                val rendered = alphaTab.collections.List<alphaTab.model.Track>()
                rendered.push(tracks[index])
                score.api.renderTracks(rendered)
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

        fun currentDurationSymbol(): String {
            val beat = currentBeat() ?: return "♩"
            val base = when (beat.duration) {
                Duration.Whole -> "𝅝"
                Duration.Half -> "𝅗𝅥"
                Duration.Quarter -> "♩"
                Duration.Eighth -> "♪"
                Duration.Sixteenth -> "𝅘𝅥𝅮"
                Duration.ThirtySecond -> "𝅘𝅥𝅯"
                Duration.SixtyFourth -> "𝅘𝅥𝅰"
                else -> "♪"
            }
            return if (beat.dots.toInt() > 0) base + "." else base
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

            caret = Caret(currentTrackIndex, b, beat, currentStringIndex)
            session.caret = caret
            armed = true
            pendingFret = ""
            updateCursor()
            updateStatus()
            onSelectionChanged?.invoke()
        }

        private fun moveToEdge(end: Boolean) {
            val bs = bars() ?: return
            if (bs.isEmpty()) return
            val targetBar = if (end) bs.lastIndex else 0
            val beats = bs[targetBar].voices.toList().getOrNull(currentVoiceIndex)?.beats?.toList().orEmpty()
            caret = Caret(currentTrackIndex, targetBar, if (end) (beats.size - 1).coerceAtLeast(0) else 0, currentStringIndex)
            session.caret = caret
            armed = true
            pendingFret = ""
            updateCursor()
            updateStatus()
        }

        /** Up/down changes the TAB string only; it never changes the rhythmic beat. */
        private fun moveString(delta: Int) {
            caret = caret.copy(stringIndex = (caret.stringIndex + delta).coerceIn(1, maxStringIndex()))
            session.caret = caret
            armed = true
            pendingFret = ""
            updateCursor()
            updateStatus()
            onSelectionChanged?.invoke()
        }

        private fun acceptDigit(digit: Int) {
            if (digit !in 0..9) return
            if (!armed) armed = true
            val now = android.os.SystemClock.uptimeMillis()
            if (now - pendingAtMs > 700L) pendingFret = ""
            pendingAtMs = now
            val generation = ++inputGeneration

            if (pendingFret.isEmpty()) {
                pendingFret = digit.toString()
            } else {
                val candidate = pendingFret + digit
                val value = candidate.toIntOrNull()
                if (value != null && value <= 24) {
                    writeFret(value)
                    pendingFret = ""
                    return
                }
                val first = pendingFret.toIntOrNull()
                if (first != null && first <= 9) writeFret(first)
                pendingFret = digit.toString()
                pendingAtMs = now
            }

            val value = pendingFret.toIntOrNull() ?: return
            if (value == 0) {
                writeFret(0)
                pendingFret = ""
            } else {
                updateStatus("Fret $pendingFret…")
                activity.window.decorView.postDelayed({
                    val t = android.os.SystemClock.uptimeMillis()
                    if (generation == inputGeneration && t - pendingAtMs >= 700L && pendingFret == value.toString()) {
                        writeFret(value)
                        pendingFret = ""
                    }
                }, 720L)
            }
        }

        /** UI String 1 is the thin/high E; AlphaTab string 1 is the lowest/bottom string. */
        private fun alphaTabString(uiString: Int): Int =
            (maxStringIndex() + 1 - uiString).coerceIn(1, maxStringIndex())

        private fun writeFret(fret: Int) {
            if (fret !in 0..24) return
            pushUndoSnapshot()
            try {
                val beat = ensureRealBeatForCaret() ?: return
                writeFretInternal(beat, fret)
                renderAndLog("fret=" + fret)
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
            val beat = currentBeat() ?: return
            val alphaTabString = alphaTabString(currentStringIndex)
            val note = beat.getNoteOnString(alphaTabString.toDouble())

            if (note == null) {
                moveBeat(-1)
                updateStatus("No note on current string • previous beat")
                return
            }

            pushUndoSnapshot()
            beat.removeNote(note)
            beat.isEmpty = beat.notes.toList().isEmpty()
            beat.finish(score.settings, null)
            score.api.score?.finish(score.settings)
            renderAndLog("delete-string-note")
            updateCursor()
            onSelectionChanged?.invoke()
            updateStatus(if (beat.isEmpty) "Beat is now rest" else "Note deleted")
        }

        fun refreshVisualCursor() {
            try {
                val lookup=score.api.renderer.boundsLookup
                val song=score.api.score
                val track=song?.tracks?.toList()?.getOrNull(currentTrackIndex)
                val staff=track?.staves?.firstOrNull()
                val bar=staff?.bars?.toList()?.getOrNull(caret.measureIndex)
                val beat=bar?.voices?.toList()?.getOrNull(currentVoiceIndex)?.beats?.toList()?.getOrNull(caret.beatIndex)
                val bb=beat?.let{lookup?.findBeat(it)}
                val barBounds=if(bar!=null&&lookup!=null)lookup.findMasterBar(bar.masterBar)?.bars?.toList()?.firstOrNull{it.bar===bar}else null
                if(bb!=null&&barBounds!=null){
                    val cx=bb.onNotesX.toFloat()
                    val cy=bb.notes?.toList()?.firstOrNull{it.note.string.toInt()==alphaTabString(caret.stringIndex)}?.noteHeadBounds?.let{it.y.toFloat()+it.h.toFloat()/2f}
                        ?: (barBounds.realBounds.y.toFloat()+barBounds.realBounds.h.toFloat()*0.58f+(caret.stringIndex-1)*(barBounds.realBounds.h.toFloat()*0.075f).coerceIn(6f,24f))
                    val half=(4f*resources.displayMetrics.density).coerceAtLeast(3f)
                    val l=cx-half;val t=cy-half;val r=cx+half;val b=cy+half
                    lastCaretPosition=Triple(cx,cy,half);lastCaretRect=RectF(l,t,r,b)
                    alphaTabCaret?.setBounds(l.toDouble(),t.toDouble(),(r-l).toDouble(),(b-t).toDouble())
                    updateDebugOverlay()
                    updateDebugBanner(barBounds.realBounds.x.toDouble(),barBounds.realBounds.y.toDouble(),barBounds.realBounds.w.toDouble(),barBounds.realBounds.h.toDouble(),bb.onNotesX.toDouble(),l,t,r,b)
                    logCoordinateDiagnostic("caret-native")
                }else{alphaTabCaret?.setBounds(-1000.0,-1000.0,0.0,0.0)}
            }catch(t:Throwable){android.util.Log.e("EARAM_ALPHA_CURSOR","native caret positioning failed",t)}
        }
        private fun updateDebugBanner(x:Double,y:Double,w:Double,h:Double,onNotesX:Double,l:Float,t:Float,r:Float,b:Float){
            if(!coordinateDebugEnabled)return
            val scroll=actualScrollOffsets();val sl=IntArray(2);val ol=IntArray(2);score.getLocationOnScreen(sl);overlay.getLocationOnScreen(ol)
            overlay.setDebugBanner("Bar1 raw x=$x y=$y w=$w h=$h\nBeat.onNotesX=$onNotesX caret=[$l,$t,$r,$b]\nAlphaTab scroll=(${scroll.first},${scroll.second})\nAlphaTabView screen=(${sl[0]},${sl[1]}) overlay screen=(${ol[0]},${ol[1]})")
        }
        fun logOfficialPlaybackCursor(playedBeat:Beat){try{val b=score.api.renderer.boundsLookup?.findBeat(playedBeat)?:return;android.util.Log.d("EARAM_ALPHA_CURSOR","OFFICIAL playback onNotesX="+b.onNotesX+" barBounds="+b.barBounds.realBounds+" scroll="+actualScrollOffsets())}catch(t:Throwable){android.util.Log.e("EARAM_ALPHA_CURSOR","official cursor diagnostic failed",t)}}
        fun showPlaybackBeat(playedBeat:Beat)=logOfficialPlaybackCursor(playedBeat)
        fun hidePlaybackCursor()=Unit
        fun invalidateCaretOverlay(reason:String){android.util.Log.d("EARAM_ALPHA_CURSOR","native caret refresh "+reason);refreshVisualCursor()}
        fun restoreCaretFromSession(){caret=session.caret;currentVoiceIndex=0;armed=true;pendingFret="";updateCursor();updateStatus()}
        fun rebuildBeatHitsAfterLayout(){buildBeatHits()}
        fun isDebugMode():Boolean=coordinateDebugEnabled
        fun setDebugModeFromUi(enabled:Boolean){coordinateDebugEnabled=enabled;buildBeatHits();updateDebugOverlay();updateStatus(if(enabled)"DEBUG ON • long-press title to disable" else "DEBUG OFF");refreshVisualCursor();logCoordinateDiagnostic("debug-toggle")}
        fun actualScrollOffsets():Pair<Float,Float>{return try{val s=score.api.uiFacade.getScrollContainer();Pair(s.scrollLeft.toFloat(),s.scrollTop.toFloat())}catch(t:Throwable){Pair(score.scrollX.toFloat(),score.scrollY.toFloat())}}
        fun logCoordinateDiagnostic(reason:String){try{val s=score.api.uiFacade.getScrollContainer();val sl=IntArray(2);val ol=IntArray(2);score.getLocationOnScreen(sl);overlay.getLocationOnScreen(ol);android.util.Log.d("EARAM_SCROLL","reason="+reason+" scroller="+s.javaClass.name+" actualScroll="+s.scrollLeft+","+s.scrollTop+" scoreScroll="+score.scrollX+","+score.scrollY+" scoreLoc="+sl[0]+","+sl[1]+" overlayLoc="+ol[0]+","+ol[1]);val lookup=score.api.renderer.boundsLookup?:return;val song=score.api.score?:return;val bar=song.tracks.toList().getOrNull(currentTrackIndex)?.staves?.firstOrNull()?.bars?.toList()?.getOrNull(caret.measureIndex);val bb=bar?.voices?.toList()?.getOrNull(currentVoiceIndex)?.beats?.toList()?.getOrNull(caret.beatIndex)?.let{lookup.findBeat(it)};val mb=bar?.let{lookup.findMasterBar(it.masterBar)?.bars?.toList()?.firstOrNull{b->b.bar===bar}};android.util.Log.d("EARAM_SCROLL","raw barRect="+mb?.realBounds+" beatRect="+bb?.realBounds+" onNotesX="+bb?.onNotesX+" nativeCaretRect="+lastCaretRect+" parent=AlphaTab.selectionWrapper")}catch(t:Throwable){android.util.Log.e("EARAM_SCROLL","coordinate diagnostic failed",t)}}
        private fun updateDebugOverlay(){val lookup=score.api.renderer.boundsLookup?:return;val song=score.api.score?:return;val staff=song.tracks.toList().getOrNull(currentTrackIndex)?.staves?.firstOrNull()?:return;val bars=staff.bars.toList().mapNotNull{bar->lookup.findMasterBar(bar.masterBar)?.bars?.toList()?.firstOrNull{it.bar===bar}?.realBounds?.let{RectF(it.x.toFloat(),it.y.toFloat(),(it.x+it.w).toFloat(),(it.y+it.h).toFloat())}};val sc=actualScrollOffsets();overlay.setDebugData(coordinateDebugEnabled,bars,"bar="+(caret.measureIndex+1)+" beat="+(caret.beatIndex+1)+" string="+caret.stringIndex+" cx="+(lastCaretPosition?.first?:-1f)+" cy="+(lastCaretPosition?.second?:-1f)+" scrollY="+sc.second);lastCaretRect?.let{r->val bb=currentBeat()?.let{score.api.renderer.boundsLookup?.findBeat(it)};val br=bb?.barBounds?.realBounds;if(bb!=null&&br!=null)updateDebugBanner(br.x.toDouble(),br.y.toDouble(),br.w.toDouble(),br.h.toDouble(),bb.onNotesX.toDouble(),r.left,r.top,r.right,r.bottom)}}
        private fun ensureCaretVisible(contentX:Float,contentY:Float){try{val s=score.api.uiFacade.getScrollContainer();val mx=(s.width*.12).coerceAtLeast(24.0);val my=(s.height*.10).coerceAtLeast(24.0);var x=s.scrollLeft;var y=s.scrollTop;if(contentX-x<mx)x=(contentX-mx).coerceAtLeast(0.0)else if(contentX-x>s.width-mx)x=(contentX-s.width+mx).coerceAtLeast(0.0);if(contentY-y<my)y=(contentY-my).coerceAtLeast(0.0)else if(contentY-y>s.height-my)y=(contentY-s.height+my).coerceAtLeast(0.0);s.scrollLeft=x;s.scrollTop=y}catch(t:Throwable){android.util.Log.e("EARAM_SCROLL","official scroll failed",t)}}
        private fun updateCursor(){refreshVisualCursor()}

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
            val beats = bs[index].voices.toList().getOrNull(currentVoiceIndex)?.beats?.toList().orEmpty()
            val targetBeat = if (beats.isEmpty()) 0 else currentBeatIndex.coerceIn(0, beats.lastIndex)
            caret = caret.copy(measureIndex = index, beatIndex = targetBeat)
            session.caret = caret
            armed = true
            pendingFret = ""
            // Selecting a measure also seeks playback to that measure.
            // Play/Pause will therefore start from the selected measure.
            val selectedBeat = beats.getOrNull(currentBeatIndex)
            if (selectedBeat != null) {
                try {
                    score.api.stop()
                    score.api.tickPosition = selectedBeat.absolutePlaybackStart
                    session.tickPosition = score.api.tickPosition
                } catch (_: Throwable) { }
            }
            updateCursor()
            updateStatus("SELECTED BAR " + (selectedBarIndex + 1) + " • Beat " + (currentBeatIndex + 1))
            onSelectionChanged?.invoke()
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
            session.caret = Caret(
                currentTrackIndex,
                currentBarIndex,
                currentBeatIndex,
                currentStringIndex
            )
            activity.runOnUiThread { status.text = text }
        }
    }


}