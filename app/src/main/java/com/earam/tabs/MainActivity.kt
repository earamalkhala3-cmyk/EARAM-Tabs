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
import android.view.WindowInsets
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.ViewModelProvider
import android.content.Context
import android.provider.Settings
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
import alphaTab.exporter.Gp7Exporter
import alphaTab.io.ByteBuffer
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

private enum class SelectionTarget { NOTE, BEAT, BAR, RANGE }
private enum class ClipboardKind { NOTE, BEAT, BAR, RANGE }

data class BeatHit(
    val measure: Int,
    val beat: Int,
    val rect: RectF,
    val tabTopY: Float,
    val stringSpacing: Float,
    val beatRef: Beat? = null,
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
    private var pendingSaveAs = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ViewModelProvider(this).get(EditorSessionViewModel::class.java)
        projectName = session.projectName
        bpm = session.bpm
        timeSig = session.timeSignature
        openEditor()

        // CI trigger: prefer the explicit launch extra; keep the Global setting
        // as a backward-compatible fallback. The explicit extra removes a race where
        // the renderer Activity could start before the shell setting was observable.
        val ciCursorRequested =
            intent?.getBooleanExtra("earam_ci_cursor_test", false) == true ||
                Settings.Global.getString(contentResolver, "earam_ci_cursor_test") == "1"
        if (ciCursorRequested) {
            android.util.Log.i("EARAM_CI_CURSOR", "CI cursor test requested")
            window.decorView.postDelayed({ runCiCursorTest() }, 700L)
        }

        // Activity recreation must not create a new empty score. The Score and all
        // editor/playback state live in the ViewModel and are rebound to this new view.
        window.decorView.post {
            if (ciCursorRequested) {
                // The CI fixture creates and renders its own Score. Do not also render
                // the normal startup score: two overlapping AlphaTab renderScore()
                // calls can race in the worker and corrupt BoundsLookup.fromJson().
                return@post
            }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return

        val uri = data.data!!

        when (requestCode) {
            5201 -> exportCurrentScoreToUri(uri)
            4107 -> {
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
        }
    }

    private fun requestSaveAs() {
        pendingSaveAs = true
        val title = (currentScore?.title?.ifBlank { projectName } ?: projectName)
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .ifBlank { "Untitled" }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, title + ".gp")
        }
        startActivityForResult(intent, 5201)
    }

    private fun saveCurrentScore() {
        val score = currentScore ?: alphaTabView?.api?.score
        if (score == null) {
            Toast.makeText(this, "No score to save.", Toast.LENGTH_SHORT).show()
            return
        }
        val existing = session.sourceUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (existing != null) {
            try {
                exportCurrentScoreToUri(existing)
                return
            } catch (t: Throwable) {
                android.util.Log.w("EARAM_SAVE", "Existing source URI is not writable; opening Save As", t)
            }
        }
        requestSaveAs()
    }

    private fun exportCurrentScoreToUri(uri: Uri) {
        val score = currentScore ?: alphaTabView?.api?.score
        val view = alphaTabView
        if (score == null || view == null) {
            Toast.makeText(this, "No score to save.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            score.finish(view.settings)
            val exporter = Gp7Exporter()
            val data = exporter.export(score, view.settings)
            val buffer = ByteBuffer.fromBuffer(data)
            val bytes = ByteArray(buffer.length.toInt())
            var i = 0
            while (i < bytes.size) {
                bytes[i] = buffer.readByte().toInt().toByte()
                i++
            }
            if (bytes.isEmpty()) throw IllegalStateException("Exporter returned an empty GP file")
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw IllegalStateException("Cannot open destination for writing")
            session.sourceUri = uri.toString()
            session.sourceName = displayNameForUri(uri)
            projectName = score.title.ifBlank { projectName }
            session.projectName = projectName
            titleView?.text = projectName
            pendingSaveAs = false
            Toast.makeText(this, "Saved • " + session.sourceName, Toast.LENGTH_SHORT).show()
            android.util.Log.i("EARAM_SAVE", "GP7 exported bytes=" + bytes.size + " uri=" + uri)
        } catch (t: Throwable) {
            android.util.Log.e("EARAM_SAVE", "GP7 export failed", t)
            Toast.makeText(this, "Save failed • " + (t.message ?: t.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
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
            val panelLogo = ImageView(this).apply {
                setImageResource(R.drawable.earam_logo)
                contentDescription = "Earam logo"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
            }
            titleRow.addView(panelLogo, LinearLayout.LayoutParams(dp(38f), dp(40f)))
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
            setOnApplyWindowInsetsListener { view, insets ->
                val statusBarTop = insets.getInsets(WindowInsets.Type.statusBars()).top
                view.setPadding(dp(16f), dp(8f) + statusBarTop, dp(10f), dp(8f))
                insets
            }
        }

        val brandLogo = ImageView(this).apply {
            setImageResource(R.drawable.earam_logo)
            contentDescription = "Earam logo"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
        }

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
            if (BuildConfig.DEBUG) {
                editor.showBoundsDiagnosticDialog()
                true
            } else {
                false
            }
        }
        title.contentDescription = "File title • long press to copy bounds diagnostics (Debug only)"

        val overflow = iconButton("⋮", "Open Earam menu", 42f).apply {
            textSize = 24f
            setOnClickListener {
                showPanel("Earam", listOf(
                    "File" to { showFileMenu() },
                    "Edit" to { showPanel("EDIT", listOf(
                        "Undo  ↶" to { editor.undoFromUi() },
                        "Redo  ↷" to { editor.redoFromUi() },
                        "Copy" to { editor.copyCurrentSelectionFromUi() },
                        "Paste" to { editor.pasteCurrentSelectionFromUi() },
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
                        "Zoom 100%" to { score.settings.display.scale = 1.0; session.zoom = 1.0; score.api.updateSettings(); score.api.render() },
                        "Debug coordinates" to { editor.setDebugModeFromUi(!editor.isDebugMode()) }
                    )) }
                ))
            }
        }

        header.addView(brandLogo, LinearLayout.LayoutParams(dp(46f), dp(42f)))
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
        // Keep Duration compact so the fret/delete controls get visual priority.
        editingStrip.addView(durationSelector, LinearLayout.LayoutParams(dp(112f), dp(42f)).apply {
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
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            setBackgroundColor(0xFF1F2226.toInt())
        }
        // Full fret entry: 0–24. Two rows keep every fret directly reachable without
        // making the toolbar excessively wide on phone displays.
        for (row in 0..1) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val start = if (row == 0) 0 else 13
            val end = if (row == 0) 12 else 24
            for (fret in start..end) {
                val d = TextView(this).apply {
                    text = fret.toString()
                    setTextColor(0xFFF3F0E8.toInt())
                    textSize = if (fret >= 10) 12f else 14f
                    gravity = Gravity.CENTER
                    background = surface(0xFF2B2F34.toInt(), 7f)
                    isClickable = true
                    isFocusable = false
                    contentDescription = "Fret $fret"
                    setOnClickListener { editor.enterFretFromUi(fret) }
                }
                rowLayout.addView(d, LinearLayout.LayoutParams(0, dp(30f), 1f).apply {
                    leftMargin = dp(1f); rightMargin = dp(1f)
                })
            }
            fretDigits.addView(rowLayout, LinearLayout.LayoutParams(-1, dp(31f)))
        }

        val deleteFret = TextView(this).apply {
            text = "⌫"
            setTextColor(0xFFF3F0E8.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            background = surface(0xFF3A2B2B.toInt(), 7f)
            isClickable = true
            isFocusable = false
            contentDescription = "Delete note on selected string"
            setOnClickListener { editor.deleteCurrentNoteFromUi() }
        }
        // Delete occupies a dedicated compact third row so the 0–24 keypad never
        // exceeds the phone width or gets clipped by the horizontal editor strip.
        fretDigits.addView(deleteFret, LinearLayout.LayoutParams(dp(44f), dp(30f)).apply {
            topMargin = dp(2f); leftMargin = dp(1f)
        })

        score = AlphaTabView(this, null).apply {
            setBackgroundColor(0xFFFFFEFB.toInt())
            // The score itself owns playback cursor rendering. There is no Earam caret
            // overlay and no second coordinate system over the TAB.
            // AlphaTab's beat cursor is the playback marker; the editor selection
            // is represented by the selected Score Beat/Note in the model.
            selectionFillColor = 0x66FF9800
            settings.display.layoutMode = LayoutMode.Page
            settings.display.staveProfile = StaveProfile.ScoreTab
            // Automatic page layout lets AlphaTab use the real rhythmic width of each
            // measure. A hard-coded 2 bars/system was causing dense systems around
            // short-note passages and made the page look like measures were merged.
            settings.display.barsPerRow = -1.0
            settings.display.barCount = -1.0
            settings.display.startBar = 1.0
            settings.display.scale = 0.72
            // Keep AlphaTab's automatic rhythmic spacing; a small positive stretch
            // gives short-note passages enough breathing room without forcing equal bars.
            settings.display.stretchForce = 0.8
            settings.display.barCountPerPartial = 8.0
            settings.display.justifyLastSystem = true
            settings.core.includeNoteBounds = true
            settings.player.playerMode = PlayerMode.EnabledSynthesizer
            settings.player.enablePlayer = true
            settings.player.enableUserInteraction = true
            settings.player.enableCursor = true
            // Let AlphaTab own playback cursor placement and scrolling. Editing selection
            // is model-based and must not be painted by a separate caret overlay.
            // Use AlphaTab's native Guitar-Pro-style beat cursor. The editor does
            // not synthesize a second playback coordinate system.
            settings.player.enableAnimatedBeatCursor = true
            settings.player.enableElementHighlighting = true
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
        // AlphaTab remains the touch target; the overlay is a transparent visual layer only.
        scoreLayer.addView(score, FrameLayout.LayoutParams(-1, -1))
        val editorOverlay = TabEditOverlayView(this).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // Add AFTER AlphaTab so the orange note-selection disc is actually visible above the TAB.
        scoreLayer.addView(editorOverlay, FrameLayout.LayoutParams(-1, -1))
        editor = AlphaTabNoteEditor(this, score, status, editorOverlay)
        noteEditor = editor
        editor.attach()
        editor.attachAlphaTabCursorLayer()

        fun refreshSelectionInfo() {
            val selectedDuration = editor.currentBeatDuration()
            durationSelector.setDurationVisual(selectedDuration.first, selectedDuration.second)
            setActive(durationSelector, true)
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
                    // AlphaTab owns the rendered page and playback cursor. There is
                    // intentionally no custom caret/coordinate redraw here.
                    editor.logRenderState()
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
            val tick = score.api.tickPosition
            android.util.Log.d(
                "EARAM_PLAYBACK",
                "playedBeatChanged FIRED • tick=${tick} • beat=${playedBeat}"
            )
            runOnUiThread {
                status.text = "PLAYED BEAT • tick=${tick.toLong()}"
                // Playback cursor placement is handled by AlphaTab itself.
            }
        }

        score.api.playerFinished.on {
            // AlphaTab removes/moves its own playback cursor when playback finishes.
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

        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        // Status text remains an internal feedback channel for commands/errors, but it is
        // deliberately NOT part of the visible editor chrome. The old 24dp status strip
        // duplicated information and consumed valuable vertical TAB space.
        header.requestApplyInsets()
        root.addView(transport, LinearLayout.LayoutParams(-1, dp(52f)))
        root.addView(navigation, LinearLayout.LayoutParams(-1, dp(48f)))
        root.addView(scoreLayer, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(editingStrip, LinearLayout.LayoutParams(-1, dp(52f)))
        // Always visible: compact fret-entry keypad (0–9).
        root.addView(fretDigits, LinearLayout.LayoutParams(-1, dp(100f)))
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
                    2 -> saveCurrentScore()
                    3 -> requestSaveAs()
                    5 -> requestSaveAs()
                    6 -> finish()
                    7 -> UpdateManager(this).checkForUpdates()
                }
            }
            .show()
    }

    private fun runCiCursorTest() {
        android.util.Log.i("EARAM_CI_CURSOR", "CI cursor test START")
        try {
            newScore("CI Cursor Geometry", 120, 4, 4, 8, listOf("Guitar"))
            window.decorView.postDelayed({
                try {
                    val editor = noteEditor ?: throw IllegalStateException("note editor missing")
                    editor.setCiTestCaret()
                    editor.writeFretFromUi(7)
                    statusView?.text = "CI CURSOR TEST • B1 b1 S2"
                    android.util.Log.i("EARAM_CI_CURSOR", "CI fixture created; waiting for AlphaTab bounds")

                    fun waitForAlphaTabBounds(attempt: Int) {
                        try {
                            val view = alphaTabView ?: throw IllegalStateException("AlphaTab view missing")
                            val score = view.api.score ?: throw IllegalStateException("AlphaTab score missing")
                            val track = score.tracks.toList().firstOrNull()
                                ?: throw IllegalStateException("CI track missing")
                            val staff = track.staves.firstOrNull()
                                ?: throw IllegalStateException("CI staff missing")
                            val bar = staff.bars.toList().firstOrNull()
                                ?: throw IllegalStateException("CI bar missing")
                            val beat = bar.voices.toList().firstOrNull()?.beats?.toList()?.firstOrNull()
                                ?: throw IllegalStateException("CI beat missing")
                            val lookup = view.api.renderer.boundsLookup
                                ?: throw IllegalStateException("AlphaTab boundsLookup missing")
                            val bounds = lookup.findBeat(beat)
                                ?: throw IllegalStateException("AlphaTab beat bounds missing")

                            editor.setDebugModeFromUi(true)
                            editor.refreshVisualCursor()
                            editor.showPlaybackBeat(beat)
                            editor.logCoordinateDiagnostic("ci-screenshot-ready")
                            android.util.Log.i(
                                "EARAM_CI_CURSOR",
                                "CI cursor test READY; bounds=" + bounds.realBounds
                            )
                        } catch (t: Throwable) {
                            if (attempt < 20) {
                                window.decorView.postDelayed(
                                    { waitForAlphaTabBounds(attempt + 1) },
                                    500L
                                )
                            } else {
                                android.util.Log.e(
                                    "EARAM_CI_CURSOR",
                                    "CI AlphaTab bounds did not become ready",
                                    t
                                )
                            }
                        }
                    }

                    waitForAlphaTabBounds(0)
                } catch (t: Throwable) {
                    android.util.Log.e("EARAM_CI_CURSOR", "CI fixture failed", t)
                }
            }, 1400L)
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

        // IMPORTANT: every rendered measure is an actual editable Score.Bar.
        // AlphaTab may legally represent a rest-only imported measure with a Bar that
        // has no Voice/Beat objects. That is fine for rendering, but it is NOT a valid
        // editing target for Earam's TAB editor because hit-testing/copy/paste then has
        // to invent a "virtual" bar. Materialize those missing structures once, at the
        // model boundary, while preserving every existing imported rhythm.
        materializeAllMeasures(score)

        try {
            score.finish(alphaTabView?.settings ?: return)
        } catch (t: Throwable) {
            android.util.Log.w("EARAM_IMPORT", "Track display normalization finish failed", t)
        }
    }

    /**
     * Converts structurally empty imported measures into real AlphaTab editing
     * structures. Existing voices/beats are never replaced or rewritten.
     *
     * Invariant after this method:
     *   staff.bars[i].voices[currentVoice] exists
     *   and that voice contains real Beat objects spanning the measure.
     *
     * Empty measures therefore never need a synthetic/virtual BeatHit.
     */
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

    private fun materializeAllMeasures(score: Score) {
        var createdVoices = 0
        var createdBeats = 0

        for (track in score.tracks.toList()) {
            for (staff in track.staves.toList()) {
                for (bar in staff.bars.toList()) {
                    var voices = bar.voices.toList()

                    if (voices.isEmpty()) {
                        val voice = alphaTab.model.Voice()
                        bar.addVoice(voice)
                        voices = bar.voices.toList()
                        createdVoices++
                    }

                    for (voice in voices) {
                        if (voice.beats.toList().isEmpty()) {
                            addEmptyBeatsForTimeSignature(
                                voice,
                                bar.masterBar.timeSignatureNumerator.toInt().coerceIn(1, 32),
                                bar.masterBar.timeSignatureDenominator.toInt().coerceIn(1, 32)
                            )
                            createdBeats++
                        }
                    }
                }
            }
        }

        android.util.Log.d(
            "EARAM_MODEL",
            "materializeAllMeasures: createdVoices=" + createdVoices +
                " createdBeatGrids=" + createdBeats
        )
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

            // Adding a track must preserve every existing track on screen.
            renderAllTracks(score)
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

    private class TabEditOverlayView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val debugBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=2f*density; color=0xFFFF0000.toInt() }
        private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL; textSize=10f*resources.displayMetrics.scaledDensity; color=0xFFFF00FF.toInt() }
        private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL; color=0xEE111318.toInt() }
        private val bannerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL; textSize=9f*resources.displayMetrics.scaledDensity; color=0xFFFFE66D.toInt() }
        private val diagnosticMagentaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=3f*density; color=0xFFFF00FF.toInt() }
        private val diagnosticCyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=3f*density; color=0xFF00FFFF.toInt() }
        private val diagnosticYellowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=3f*density; color=0xFFFFFF00.toInt() }
        private var diagnosticBanner = ""
        private var diagnosticRawX=Float.NaN
        private var diagnosticRawY=Float.NaN
        private var diagnosticRawDensity=1f

        // Both markers live in the same AlphaTab content coordinate system and are
        // translated by the actual score scroll. This is the only overlay geometry.
        private val playbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x66FF8A00
        }
        private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFFFD400.toInt()
            strokeWidth = 3f * density
        }
        // Guitar Pro-like editing guide: the musical caret is a thin line at the
        // selected beat, independent from the playback cursor. The yellow square
        // remains the Earam string-level handle.
        private val editingGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x806DA8FF.toInt()
        }
        private var editingGuideX = Float.NaN
        private var editingGuideTop = Float.NaN
        private var editingGuideBottom = Float.NaN

        private val debugBars=mutableListOf<RectF>()
        private var debugBanner=""
        private var debugEnabled=false
        private var scrollProvider:(()->Pair<Float,Float>)?=null
        private var contentOriginProvider:(()->Pair<Float,Float>)?=null

        private var caretCenterX = Float.NaN
        private var caretCenterY = Float.NaN
        private var caretHalf = 0f
        private var noteSelectionLeft = Float.NaN
        private var noteSelectionTop = Float.NaN
        private var noteSelectionRight = Float.NaN
        private var noteSelectionBottom = Float.NaN
        // A translucent orange disc keeps the TAB fret number readable while
        // making the exact selected note obvious on a dense chord.
        private val noteSelectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x55FF8A00
        }
        private val noteSelectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFFF8A00.toInt()
            strokeWidth = 1.8f * density
        }
        private var playbackX = Float.NaN
        private var playbackTop = 0f
        private var playbackBottom = 0f

        fun setScrollProvider(provider:()->Pair<Float,Float>){scrollProvider=provider}
        fun setContentOriginProvider(provider:()->Pair<Float,Float>){contentOriginProvider=provider}
        fun setDebugData(enabled:Boolean,bars:List<RectF>,label:String){debugEnabled=enabled;debugBars.clear();debugBars.addAll(bars);invalidate()}
        fun setDebugBanner(text:String){debugBanner=text;invalidate()}
        fun setDiagnosticBanner(text:String){diagnosticBanner=text;invalidate()}
        fun setDiagnosticRaw(rawX:Float,rawY:Float,d:Float){
            if (!BuildConfig.DEBUG || !debugEnabled) return
            diagnosticRawX=rawX;diagnosticRawY=rawY;diagnosticRawDensity=d;invalidate()
        }

        fun showBeatCaretContent(centerX:Float,centerY:Float,half:Float){
            caretCenterX=centerX; caretCenterY=centerY; caretHalf=half
            invalidate()
        }
        fun showGuitarProGuideContent(centerX:Float,top:Float,bottom:Float){
            editingGuideX=centerX
            editingGuideTop=top
            editingGuideBottom=bottom
            invalidate()
        }
        fun showPlaybackCursorContent(centerX:Float,top:Float,bottom:Float){
            playbackX=centerX; playbackTop=top; playbackBottom=bottom
            invalidate()
        }
        fun showNoteSelectionContent(left:Float,top:Float,right:Float,bottom:Float){
            noteSelectionLeft=left
            noteSelectionTop=top
            noteSelectionRight=right
            noteSelectionBottom=bottom
            invalidate()
        }
        fun hideNoteSelection(){
            noteSelectionLeft=Float.NaN
            noteSelectionTop=Float.NaN
            noteSelectionRight=Float.NaN
            noteSelectionBottom=Float.NaN
            invalidate()
        }
        fun diagnosticNoteSelectionBounds(): String {
            val centerX = if (noteSelectionLeft.isFinite() && noteSelectionRight.isFinite()) (noteSelectionLeft + noteSelectionRight) / 2f else Float.NaN
            return "noteSelectionLeft=$noteSelectionLeft, noteSelectionTop=$noteSelectionTop, " +
                "noteSelectionRight=$noteSelectionRight, noteSelectionBottom=$noteSelectionBottom, " +
                "noteSelectionCenterX=$centerX, deltaOrangeCenterMinusCaretX=${centerX - caretCenterX}"
        }
        fun showNoteCursor(left:Float,top:Float,width:Float,height:Float)=invalidate()
        fun hideCursor(){
            caretCenterX=Float.NaN; caretCenterY=Float.NaN; caretHalf=0f
            hideNoteSelection()
            editingGuideX=Float.NaN
            editingGuideTop=Float.NaN
            editingGuideBottom=Float.NaN
            invalidate()
        }
        fun hidePlaybackCursor(){
            playbackX=Float.NaN
            invalidate()
        }

        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)

            // Diagnostic rectangles are available only in DEBUG builds and only
            // while the existing hidden coordinate-debug mode is enabled.
            if (BuildConfig.DEBUG && debugEnabled) {
                val fixed=60f*density
                val fixedSize=30f*density
                canvas.drawRect(fixed,fixed,fixed+fixedSize,fixed+fixedSize,diagnosticMagentaPaint)
                if(diagnosticRawX.isFinite() && diagnosticRawY.isFinite()){
                    val half=15f*density
                    canvas.drawRect(diagnosticRawX-half,diagnosticRawY-half,diagnosticRawX+half,diagnosticRawY+half,diagnosticCyanPaint)
                    val yellowX=diagnosticRawX*diagnosticRawDensity
                    val yellowY=diagnosticRawY*diagnosticRawDensity
                    canvas.drawRect(yellowX-half,yellowY-half,yellowX+half,yellowY+half,diagnosticYellowPaint)
                }
            }

            val scroll=scrollProvider?.invoke() ?: (0f to 0f)
            canvas.save()
            canvas.translate(-scroll.first,-scroll.second)

            // Playback: a thin ~40% alpha orange line, exactly the rendered
            // master-bar height. It is independent from the editing caret.
            if (playbackX.isFinite() && playbackBottom > playbackTop) {
                val lineHalf = (0.65f * density).coerceAtLeast(0.5f)
                canvas.drawRect(
                    playbackX - lineHalf,
                    playbackTop,
                    playbackX + lineHalf,
                    playbackBottom,
                    playbackPaint
                )
            }

            // Editing caret: Guitar Pro-like beat guide. It follows the selected
            // musical beat through scrolling and page re-layout; it is not tied to
            // playback timing.
            if (editingGuideX.isFinite() &&
                editingGuideTop.isFinite() &&
                editingGuideBottom > editingGuideTop
            ) {
                val guideHalf = (0.8f * density).coerceAtLeast(0.7f)
                canvas.drawRect(
                    editingGuideX - guideHalf,
                    editingGuideTop,
                    editingGuideX + guideHalf,
                    editingGuideBottom,
                    editingGuidePaint
                )
            }

            // Exact note selection: a small rounded orange outline around the
            // tapped TAB number. This is independent from the beat/playback cursor.
            if (noteSelectionLeft.isFinite() &&
                noteSelectionTop.isFinite() &&
                noteSelectionRight > noteSelectionLeft &&
                noteSelectionBottom > noteSelectionTop
            ) {
                // Draw a true circular selection centered on the fret number.
                // Fill is deliberately translucent; the digit remains legible.
                val centerX = (noteSelectionLeft + noteSelectionRight) / 2f
                val centerY = (noteSelectionTop + noteSelectionBottom) / 2f
                val noteW = noteSelectionRight - noteSelectionLeft
                val noteH = noteSelectionBottom - noteSelectionTop
                val radius = (maxOf(noteW, noteH) * 0.72f + 3f * density)
                    .coerceAtLeast(7f * density)
                val selectionCircle = RectF(
                    centerX - radius,
                    centerY - radius,
                    centerX + radius,
                    centerY + radius
                )
                canvas.drawOval(selectionCircle, noteSelectionFillPaint)
                canvas.drawOval(selectionCircle, noteSelectionPaint)
            }

            // Earam string-level handle: hollow yellow square, 0.9 * string
            // spacing, centered on the selected TAB string.
            if (caretCenterX.isFinite() && caretHalf > 0f) {
                canvas.drawRect(
                    caretCenterX - caretHalf,
                    caretCenterY - caretHalf,
                    caretCenterX + caretHalf,
                    caretCenterY + caretHalf,
                    caretPaint
                )
            }

            canvas.restore()
            if (playbackX.isFinite() || caretCenterX.isFinite()) postInvalidateOnAnimation()

            if(!debugEnabled)return
            canvas.save()
            for((i,bar) in debugBars.withIndex()){
                canvas.drawRect(bar,debugBarPaint)
                canvas.drawText("BAR "+(i+1),bar.left+3f,bar.top+12f,debugTextPaint)
            }
            canvas.restore()
            if(debugBanner.isNotBlank() || diagnosticBanner.isNotBlank()){
                val pad=8f*density
                val lineH=13f*resources.displayMetrics.scaledDensity
                val banner=if(diagnosticBanner.isBlank()) debugBanner else if(debugBanner.isBlank()) diagnosticBanner else debugBanner+"\n"+diagnosticBanner
                val lines=banner.split("\n")
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
            val simple = score.tracks.toList().flatMap { it.staves.toList() }.flatMap { it.bars.toList() }.flatMap { it.voices.toList() }.flatMap { it.beats.toList() }
                .firstOrNull { it.dots <= 0.0 && it.tupletNumerator < 0.0 && it.tupletDenominator < 0.0 } ?: return
            val multiplier = when (simple.duration) {
                Duration.DoubleWhole -> 8.0; Duration.QuadrupleWhole -> 16.0; Duration.Whole -> 4.0
                Duration.Half -> 2.0; Duration.Quarter -> 1.0; Duration.Eighth -> 0.5
                Duration.Sixteenth -> 0.25; Duration.ThirtySecond -> 0.125; Duration.SixtyFourth -> 0.0625
                Duration.OneHundredTwentyEighth -> 0.03125; Duration.TwoHundredFiftySixth -> 0.015625
            }
            quarterTicks = kotlin.math.round(simple.displayDuration / multiplier).toLong().coerceAtLeast(1L)
        }
        fun durationTicks(duration: Duration): Long = when (duration) {
            Duration.QuadrupleWhole -> quarterTicks * 16; Duration.DoubleWhole -> quarterTicks * 8
            Duration.Whole -> quarterTicks * 4; Duration.Half -> quarterTicks * 2; Duration.Quarter -> quarterTicks
            Duration.Eighth -> (quarterTicks / 2).coerceAtLeast(1L); Duration.Sixteenth -> (quarterTicks / 4).coerceAtLeast(1L)
            Duration.ThirtySecond -> (quarterTicks / 8).coerceAtLeast(1L); Duration.SixtyFourth -> (quarterTicks / 16).coerceAtLeast(1L)
            Duration.OneHundredTwentyEighth -> (quarterTicks / 32).coerceAtLeast(1L); Duration.TwoHundredFiftySixth -> (quarterTicks / 64).coerceAtLeast(1L)
        }
        fun beatTicks(beat: Beat): Long {
            var ticks = durationTicks(beat.duration)
            when (beat.dots.toInt()) { 1 -> ticks += ticks / 2; 2 -> ticks += (ticks / 4) * 3 }
            if (beat.tupletNumerator >= 0 && beat.tupletDenominator > 0) ticks = (ticks * beat.tupletDenominator.toLong()) / beat.tupletNumerator.toLong()
            return ticks.coerceAtLeast(1L)
        }
        fun barCapacityTicks(bar: Bar): Long = barCapacityTicksForMaster(bar.masterBar)
        fun barCapacityTicksForMaster(m: MasterBar): Long {
            val numerator = m.timeSignatureNumerator.toLong().coerceAtLeast(1L)
            val denominator = m.timeSignatureDenominator.toLong().coerceAtLeast(1L)
            return (numerator * quarterTicks * 4L) / denominator
        }
        fun barUsedTicks(bar: Bar, voiceIndex: Int = 0, excluding: Beat? = null): Long =
            bar.voices.toList().getOrNull(voiceIndex)?.beats?.toList()?.filter { it !== excluding }?.sumOf { beatTicks(it) } ?: 0L
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
            beat.duration = duration; beat.dots = dots.coerceIn(0, 2).toDouble()
            beat.tupletNumerator = tupletNumerator.toDouble(); beat.tupletDenominator = tupletDenominator.toDouble()
        }
        data class Quintuple(val first: Duration, val second: Int, val third: Int, val fourth: Int, val fifth: Long)
        fun largestEmptyBeatSpec(ticks: Long): Quintuple? {
            if (ticks <= 0L) return null
            val candidates = listOf(
                Quintuple(Duration.DoubleWhole,0,-1,-1,quarterTicks*8), Quintuple(Duration.Whole,0,-1,-1,quarterTicks*4),
                Quintuple(Duration.Half,1,-1,-1,quarterTicks*3), Quintuple(Duration.Half,0,-1,-1,quarterTicks*2),
                Quintuple(Duration.Quarter,1,-1,-1,quarterTicks*3/2), Quintuple(Duration.Quarter,0,-1,-1,quarterTicks),
                Quintuple(Duration.Eighth,1,-1,-1,quarterTicks*3/4), Quintuple(Duration.Eighth,0,-1,-1,quarterTicks/2),
                Quintuple(Duration.Sixteenth,1,-1,-1,quarterTicks*3/8), Quintuple(Duration.Sixteenth,0,-1,-1,quarterTicks/4),
                Quintuple(Duration.ThirtySecond,0,-1,-1,quarterTicks/8), Quintuple(Duration.SixtyFourth,0,-1,-1,quarterTicks/16),
                Quintuple(Duration.Eighth,0,3,2,quarterTicks/3), Quintuple(Duration.Sixteenth,0,3,2,quarterTicks/6),
                Quintuple(Duration.ThirtySecond,0,3,2,quarterTicks/12)
            ).filter { it.fifth > 0L }.sortedByDescending { it.fifth }
            return candidates.firstOrNull { it.fifth <= ticks }
        }
        private fun newEmptyBeat(spec: Quintuple): Beat = Beat().apply {
            duration = spec.first; dots = spec.second.toDouble(); tupletNumerator = spec.third.toDouble()
            tupletDenominator = spec.fourth.toDouble(); isEmpty = true
        }
        fun fillVoiceToBarCapacity(bar: Bar, voiceIndex: Int = 0): Boolean {
            val voice = bar.voices.toList().getOrNull(voiceIndex) ?: return false
            val capacity = barCapacityTicks(bar); var used = barUsedTicks(bar, voiceIndex)
            while (used < capacity) {
                val spec = largestEmptyBeatSpec(capacity - used) ?: return false
                val rest = newEmptyBeat(spec); voice.addBeat(rest); used += beatTicks(rest)
            }
            while (used > capacity) {
                val beats = voice.beats.toList(); val last = beats.lastOrNull() ?: break
                if (!last.isEmpty || last.notes.toList().isNotEmpty()) return false
                val excess = used - capacity; val lastTicks = beatTicks(last)
                if (lastTicks <= excess) {
                    voice.beats.splice(beats.lastIndex.toDouble(), 1.0); used -= lastTicks
                } else {
                    val target = lastTicks - excess; val spec = largestEmptyBeatSpec(target) ?: return false
                    apply(last, spec.first, spec.second, spec.third, spec.fourth); last.isEmpty = true
                    used = used - lastTicks + beatTicks(last)
                }
            }
            return used == capacity
        }
        fun changeBeatDuration(bar: Bar, voiceIndex: Int, beat: Beat, duration: Duration, dots: Int, tupletNumerator: Int, tupletDenominator: Int): Boolean {
            val voice = bar.voices.toList().getOrNull(voiceIndex) ?: return false
            val beats = voice.beats.toList(); val index = beats.indexOf(beat); if (index < 0) return false
            val oldTicks = beatTicks(beat); val newTicks = candidateTicks(duration,dots,tupletNumerator,tupletDenominator)
            val capacity = barCapacityTicks(bar); val usedWithoutCurrent = barUsedTicks(bar,voiceIndex,beat)
            if (usedWithoutCurrent + newTicks > capacity) return false
            if (newTicks < oldTicks) {
                apply(beat,duration,dots,tupletNumerator,tupletDenominator)
                var remainder = oldTicks-newTicks; var after = beat
                while (remainder > 0L) {
                    val spec = largestEmptyBeatSpec(remainder) ?: return false
                    val rest = newEmptyBeat(spec); voice.insertBeat(after,rest); after=rest; remainder-=beatTicks(rest)
                }
                return true
            }
            if (newTicks > oldTicks) {
                val needed = newTicks-oldTicks; var available=0L; var scanIndex=index+1
                while (available < needed) {
                    val current=voice.beats.toList().getOrNull(scanIndex) ?: return false
                    if (!current.isEmpty || current.notes.toList().isNotEmpty()) return false
                    available += beatTicks(current); scanIndex++
                }
                var remaining=needed; var removeIndex=index+1
                while (remaining > 0L) {
                    val current=voice.beats.toList().getOrNull(removeIndex) ?: return false
                    val currentTicks=beatTicks(current)
                    if (currentTicks <= remaining) { voice.beats.splice(removeIndex.toDouble(),1.0); remaining-=currentTicks }
                    else {
                        val restTicks=currentTicks-remaining; val spec=largestEmptyBeatSpec(restTicks) ?: return false
                        apply(current,spec.first,spec.second,spec.third,spec.fourth); current.isEmpty=true; remaining=0L
                    }
                }
                apply(beat,duration,dots,tupletNumerator,tupletDenominator); return true
            }
            apply(beat,duration,dots,tupletNumerator,tupletDenominator); return true
        }
        fun prepareNextRestGrid(bar: Bar, voiceIndex: Int, beat: Beat): Boolean {
            val voice=bar.voices.toList().getOrNull(voiceIndex) ?: return false
            val beats=voice.beats.toList(); val index=beats.indexOf(beat)
            if (index < 0 || index+1 >= beats.size) return false
            val next=beats[index+1]; if (!next.isEmpty || next.notes.toList().isNotEmpty()) return false
            val desired=candidateTicks(beat.duration,beat.dots.toInt(),if(beat.tupletNumerator>=0) beat.tupletNumerator.toInt() else -1,if(beat.tupletDenominator>0) beat.tupletDenominator.toInt() else -1)
            val nextTicks=beatTicks(next); if (nextTicks <= desired) return false
            val remainder=nextTicks-desired; val spec=largestEmptyBeatSpec(remainder) ?: return false
            apply(next,beat.duration,beat.dots.toInt(),beat.tupletNumerator.toInt(),beat.tupletDenominator.toInt())
            voice.insertBeat(next,newEmptyBeat(spec)); return true
        }
        fun nextBeat(bar: Bar, beat: Beat, voiceIndex: Int = 0): Beat? {
            val beats=bar.voices.toList().getOrNull(voiceIndex)?.beats?.toList() ?: return null
            val i=beats.indexOf(beat); return if (i>=0 && i+1<beats.size) beats[i+1] else null
        }
    }

    private data class ClipboardNote(
        val string: Double, val fret: Double,
        val isHammerPullOrigin: Boolean, val isPalmMute: Boolean,
        val isLetRing: Boolean, val isGhost: Boolean, val isDead: Boolean,
        val isStaccato: Boolean, val vibrato: alphaTab.model.VibratoType,
        val isLeftHandTapped: Boolean
    )

    private data class ClipboardBeat(
        val duration: Duration, val dots: Double,
        val tupletNumerator: Double, val tupletDenominator: Double,
        val isEmpty: Boolean, val notes: List<ClipboardNote>,
        val slap: Boolean, val pop: Boolean, val tap: Boolean,
        val deadSlapped: Boolean, val fadeIn: Boolean, val slashed: Boolean,
        val showTimer: Boolean, val text: String?
    )

    private data class ClipboardBar(
        val beats: List<ClipboardBeat>, val timeNumerator: Int, val timeDenominator: Int
    )

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

        // The TAB is the editor surface. Commands always resolve against its current target.
        private var selectionTarget: SelectionTarget = SelectionTarget.BEAT
        private var selectionAnchorBeat: alphaTab.model.Beat? = null
        private var selectionFocusBeat: alphaTab.model.Beat? = null
        private var selectionDragActive = false
        private var selectionDragMoved = false

        val currentBarIndex: Int get() = caret.measureIndex
        val selectedBarIndex: Int get() = caret.measureIndex
        val currentBeatIndex: Int get() = caret.beatIndex
        val currentStringIndex: Int get() = caret.stringIndex
        val currentTrackIndex: Int get() = caret.trackIndex
        var currentVoiceIndex: Int = 0
            private set
        private var copiedFret: Int? = null
        private var copiedBar: Bar? = null
        private var selectionClipboardNote: ClipboardNote? = null
        private var selectionClipboardBeat: ClipboardBeat? = null
        private var selectionClipboardBar: ClipboardBar? = null
        // A copied range is independent from the current selection/caret. The caret
        // may move to the paste destination after Copy, so Paste must never resolve
        // the destination from the original selection.
        private var selectionClipboardRange: List<ClipboardBeat>? = null
        // The copied payload is independent from the current selection.
        private var clipboardKind: ClipboardKind? = null
        // Explicit paste destination: the last beat the user actually tapped after Copy.
        private var pasteDestinationCaret: Caret? = null
        // Strong destination anchor: the actual AlphaTab Beat tapped after Copy.
        // This is independent of the mutable caret and cannot fall back to the source beat.
        private var pasteDestinationBeat: alphaTab.model.Beat? = null
        // After copying a range, the NEXT real TAB tap is an explicit paste destination.
        // This prevents the source selection from being reused as the destination.
        private var awaitingPasteDestination = false
        // True while the post-Copy destination tap gesture is being consumed.
        // Prevents MOVE/UP from falling back into normal range-selection logic.
        private var pasteDestinationTouchActive = false
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
            // Navigation always owns the paste destination through the live caret.
            if (clipboardKind == ClipboardKind.RANGE) {
                pasteDestinationCaret = null
            }
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
        // Exact AlphaTab bounds captured from the note the user actually tapped.
        // Used as a fallback if boundsLookup has not populated this note yet.
        private var tappedNoteBounds: RectF? = null
        // The selection must refer to the exact AlphaTab Note object, not merely
        // the caret's string index. This is essential for deleting one note from a chord.
        private var selectedNoteRef: alphaTab.model.Note? = null
        private var lastRawCaretX = Float.NaN
        private var lastRawCaretY = Float.NaN
        fun alphaTabContentOriginInOverlay(): Pair<Float, Float> { return try { val sl=IntArray(2); val ol=IntArray(2); score.getLocationOnScreen(sl); overlay.getLocationOnScreen(ol); Pair((sl[0]-ol[0]).toFloat(),(sl[1]-ol[1]).toFloat()) } catch(t:Throwable){ 0f to 0f } }
        fun attachAlphaTabCursorLayer() {
            // AlphaTab is the sole owner of playback cursor geometry.
            // There is deliberately no Earam caret layer.
            alphaTabCaret = null
            score.settings.player.enableCursor = true
            score.settings.player.enableAnimatedBeatCursor = true
            score.settings.player.enableElementHighlighting = true
            android.util.Log.i("EARAM_ALPHA_CURSOR", "AlphaTab native playback cursor enabled; animated + element highlighting")
        }
        fun setCiTestCaret(){caret=Caret(0,0,0,2);session.caret=caret;currentVoiceIndex=0;armed=true;pendingFret=""}
        private var armed = false
        private var pendingFret: String = ""
        private var pendingAtMs: Long = 0L
        private var inputGeneration: Long = 0L

        fun attach() {
            score.isFocusable = true
            score.isFocusableInTouchMode = true

            val keyHandler: (View, Int, android.view.KeyEvent) -> Boolean = { _, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN || event.repeatCount > 0) false
                else if ((event.isCtrlPressed || event.isMetaPressed) && keyCode == android.view.KeyEvent.KEYCODE_C) {
                    copyCurrentSelectionFromUi(); true
                } else if ((event.isCtrlPressed || event.isMetaPressed) && keyCode == android.view.KeyEvent.KEYCODE_V) {
                    pasteCurrentSelectionFromUi(); true
                } else if ((event.isCtrlPressed || event.isMetaPressed) && keyCode == android.view.KeyEvent.KEYCODE_Z) {
                    undoFromUi(); true
                } else if ((event.isCtrlPressed || event.isMetaPressed) && keyCode == android.view.KeyEvent.KEYCODE_Y) {
                    redoFromUi(); true
                } else when (keyCode) {
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
            // AlphaTab remains the owner of the normal touch stream. We only observe
            // the same rendered TAB surface to implement a real long-press BAR action.
            // The listener ALWAYS returns false, so AlphaTab keeps receiving the gesture.
            var barLongPressRunnable: Runnable? = null
            var barLongPressActivated = false
            var barDownX = 0f
            var barDownY = 0f

            fun cancelBarLongPress() {
                barLongPressRunnable?.let { activity.window.decorView.removeCallbacks(it) }
                barLongPressRunnable = null
                if (!barLongPressActivated) return
                barLongPressActivated = false
            }

            fun beginManualRangeSelection(beat: alphaTab.model.Beat) {
                selectionAnchorBeat = beat
                selectionFocusBeat = beat
                selectionDragActive = true
                selectionDragMoved = false
                selectionTarget = SelectionTarget.BEAT
            }

            fun updateManualRangeSelection(beat: alphaTab.model.Beat) {
                val anchor = selectionAnchorBeat ?: return
                if (!selectionDragActive) return
                selectionDragMoved = true
                selectionFocusBeat = beat
                selectionTarget = SelectionTarget.RANGE
                try {
                    score.api.highlightPlaybackRange(anchor, beat)
                    updateStatus("NOTE SELECTION")
                    onSelectionChanged?.invoke()
                } catch (t: Throwable) {
                    android.util.Log.e("EARAM_SELECTION", "range highlight failed", t)
                }
            }

            fun finishManualRangeSelection() {
                if (!selectionDragActive) return
                val anchor = selectionAnchorBeat
                val focus = selectionFocusBeat
                val moved = selectionDragMoved
                selectionDragActive = false
                selectionDragMoved = false
                if (!moved || anchor == null || focus == null) {
                    selectionAnchorBeat = null
                    selectionFocusBeat = null
                    return
                }
                selectionTarget = SelectionTarget.RANGE
                try { score.api.highlightPlaybackRange(anchor, focus) } catch (_: Throwable) { }
                updateStatus("NOTES SELECTED")
                onSelectionChanged?.invoke()
            }

            fun clearManualSelection() {
                selectionAnchorBeat = null
                selectionFocusBeat = null
                selectionDragActive = false
                selectionDragMoved = false
                if (selectionTarget == SelectionTarget.RANGE) selectionTarget = SelectionTarget.BEAT
                try { score.api.clearPlaybackRangeHighlight() } catch (_: Throwable) { }
            }

            // Android owns the complete selection gesture. Returning false on ACTION_DOWN
            // lets AlphaTab take ownership of the stream, which can prevent subsequent
            // ACTION_MOVE events from reaching this listener. We therefore consume the
            // gesture from DOWN through UP and explicitly perform tap/drag/bar actions.
            var manualTouchDown = false
            var manualTouchMoved = false

            score.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        cancelBarLongPress()
                        barLongPressActivated = false
                        barDownX = event.x
                        barDownY = event.y
                        manualTouchDown = true
                        manualTouchMoved = false

                        // After Copy RANGE, the next tap is exclusively the paste destination.
                        // Do not let the normal selection handler consume it.
                        if (awaitingPasteDestination && clipboardKind == ClipboardKind.RANGE) {
                            pasteDestinationTouchActive = true
                            manualTouchDown = false
                            manualTouchMoved = false
                            cancelBarLongPress()

                            val destination = resolvePasteDestinationAtPoint(event.x, event.y)
                            if (destination != null) {
                                val destinationMeasure = destination.first
                                val destinationBeatIndex = destination.second
                                caret = Caret(
                                    currentTrackIndex,
                                    destinationMeasure,
                                    destinationBeatIndex,
                                    currentStringIndex
                                )
                                session.caret = caret
                                pasteDestinationCaret = caret
                                awaitingPasteDestination = false
                                selectionTarget = SelectionTarget.BEAT
                                selectionDragActive = false
                                selectionDragMoved = false
                                selectionAnchorBeat = null
                                selectionFocusBeat = null
                                armed = true
                                pendingFret = ""
                                updateCursor()
                                android.util.Log.d(
                                    "EARAM_PASTE",
                                    "DESTINATION TAP LOCKED bar=" + (destinationMeasure + 1) +
                                        " beat=" + (destinationBeatIndex + 1)
                                )
                                updateStatus(
                                    "PASTE DESTINATION • BAR " + (destinationMeasure + 1) +
                                        " • BEAT " + (destinationBeatIndex + 1)
                                )
                                score.requestFocus()
                            } else {
                                updateStatus("Tap the destination measure/beat before Paste")
                            }
                            return@setOnTouchListener true
                        }

                        // IMPORTANT: never choose the nearest rendered beat globally.
                        // That made a tap in measure N activate whichever beat happened to be
                        // closest (often the previous/source measure). The TAB point itself is
                        // the authority: handleScoreTouch() performs exact note/beat hit-testing
                        // against AlphaTab's real rendered bounds.
                        val hit = handleScoreTouch(event.x, event.y)

                        if (hit) {
                            // handleScoreTouch() already selected the exact real measure.
                            // Do NOT overwrite BAR with NOTE here: that was the hidden reason
                            // Copy/Paste/Clear/Delete kept acting on the wrong target after a
                            // normal TAB tap. Keep the beat as the caret anchor only; a MOVE
                            // can promote the gesture to RANGE explicitly.
                            val anchor = currentBeat()
                            if (anchor != null) {
                                selectionAnchorBeat = anchor
                                selectionFocusBeat = anchor
                                selectionDragActive = false
                                selectionDragMoved = false
                            }
                        }

                        barLongPressRunnable = Runnable {
                            val index = hitRenderedBarAtPoint(barDownX, barDownY)
                            if (index >= 0 && manualTouchDown && !manualTouchMoved) {
                                barLongPressActivated = true
                                manualTouchDown = false
                                selectionDragActive = false
                                selectionAnchorBeat = null
                                selectionFocusBeat = null
                                selectionTarget = SelectionTarget.BAR
                                selectBarFromUi(index)
                                updateStatus("TAB BAR SELECTED • BAR " + (index + 1) + " • BAR ACTIONS READY")
                            }
                        }.also { activity.window.decorView.postDelayed(it, 500L) }

                        // Consume DOWN so Android guarantees MOVE/UP delivery to this listener.
                        true
                    }

                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (pasteDestinationTouchActive) return@setOnTouchListener true
                        if (!manualTouchDown || barLongPressActivated) return@setOnTouchListener true

                        val slop = android.view.ViewConfiguration.get(score.context).scaledTouchSlop
                        val moved = kotlin.math.hypot(
                            (event.x - barDownX).toDouble(),
                            (event.y - barDownY).toDouble()
                        ) > slop.toDouble()

                        if (moved) {
                            manualTouchMoved = true
                            cancelBarLongPress()

                            val targetBeat = hitRangeBeatAtPoint(event.x, event.y)
                            if (targetBeat != null) {
                                if (!selectionDragActive) {
                                    val anchor = selectionAnchorBeat ?: currentBeat()
                                    if (anchor != null) beginManualRangeSelection(anchor)
                                }
                                updateManualRangeSelection(targetBeat)
                            }
                        }
                        true
                    }

                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        if (pasteDestinationTouchActive) {
                            pasteDestinationTouchActive = false
                            manualTouchDown = false
                            manualTouchMoved = false
                            cancelBarLongPress()
                            return@setOnTouchListener true
                        }
                        val wasBarSelection = barLongPressActivated
                        val wasMoved = manualTouchMoved
                        manualTouchDown = false
                        cancelBarLongPress()

                        if (!wasBarSelection) {
                            if (wasMoved) {
                                finishManualRangeSelection()
                            } else {
                                // A normal tap was already resolved by handleScoreTouch().
                                selectionDragActive = false
                                selectionAnchorBeat = null
                                selectionFocusBeat = null
                            }
                        }

                        barLongPressActivated = false
                        true
                    }

                    else -> true
                }
            }


            // AUTHORITATIVE TAB EDIT TARGET: AlphaTab already resolves the tapped
            // screen position to the real Beat. Do not reconstruct bar coordinates from
            // Android pixels (density/scale/scroll guesses). This event also fires on rests,
            // so empty measures are selectable exactly like measures containing notes.
            score.api.beatMouseDown.on { beat ->
                try {
                    val song = score.api.score ?: return@on
                    val clickedBar = beat.voice.bar
                    val clickedTrack = clickedBar.staff.track
                    val clickedTrackIndex = clickedTrack.index.toInt()
                        .coerceIn(0, song.tracks.toList().lastIndex)
                    val staff = clickedTrack.staves.firstOrNull() ?: return@on
                    val barIndex = staff.bars.toList().indexOf(clickedBar)
                    val beatIndex = beat.voice.beats.toList().indexOf(beat)
                    if (barIndex < 0 || beatIndex < 0) return@on

                    currentVoiceIndex = beat.voice.index.toInt().coerceIn(0, 3)
                    caret = Caret(
                        clickedTrackIndex,
                        barIndex,
                        beatIndex,
                        currentStringIndex.coerceIn(1, maxStringIndex())
                    )
                    session.caret = caret
                    selectionTarget = SelectionTarget.BAR
                    selectionAnchorBeat = beat
                    selectionFocusBeat = beat
                    selectionDragActive = false
                    selectionDragMoved = false
                    armed = true
                    pendingFret = ""
                    pasteDestinationCaret = caret
                    pasteDestinationBeat = beat
                    awaitingPasteDestination = false

                    try {
                        score.api.stop()
                        score.api.tickPosition = beat.absolutePlaybackStart
                        session.tickPosition = score.api.tickPosition
                    } catch (_: Throwable) { }

                    android.util.Log.d(
                        "EARAM_BAR_SELECTION",
                        "ALPHATAB BEAT TARGET bar=" + (barIndex + 1) +
                            " beat=" + (beatIndex + 1) +
                            " track=" + (clickedTrackIndex + 1) +
                            " voice=" + (currentVoiceIndex + 1)
                    )
                    updateCursor()
                    score.requestFocus()
                    updateStatus(
                        "TAB BEAT SELECTED • B" + (barIndex + 1) +
                            " • BEAT " + (beatIndex + 1)
                    )
                    onSelectionChanged?.invoke()
                } catch (t: Throwable) {
                    android.util.Log.e("EARAM_SELECTION", "beat selection failed", t)
                }
            }

            score.api.noteMouseDown.on { note ->
                // Do NOT cancel the long-press timer here. noteMouseDown and beatMouseDown
                // can both fire for the same TAB number. Release is handled centrally above,
                // so holding directly on a fret can still promote the containing bar.
                try {
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
                    caret = Caret(clickedTrackIndex, barIndex, beatIndex, uiString)
                    session.caret = caret
                    // AlphaTab's noteMouseDown identifies the exact TAB number touched.
                    // Keep NOTE as the authoritative target so Delete removes only this
                    // string/note from a chord, not the whole Beat or measure.
                    // Range selection is handled exclusively by the Android touch MOVE path.
                    selectionAnchorBeat = note.beat
                    selectionFocusBeat = note.beat
                    selectionDragActive = false
                    selectionDragMoved = false
                    selectionTarget = SelectionTarget.NOTE
                    selectedNoteRef = note
                    // Note model objects do not expose rendered bounds. Resolve the
                    // exact tapped note through AlphaTab's renderer bounds lookup instead.
                    val lookup = score.api.renderer.boundsLookup
                    val allBeatBounds = lookup?.let {
                        runCatching { it.findBeats(note.beat)?.toList().orEmpty() }.getOrDefault(emptyList())
                    }.orEmpty()
                    val tabHit = beatHits.firstOrNull { it.measure == barIndex && it.beat == beatIndex && !it.virtual }
                    val expectedTabY = tabHit?.let { hit ->
                        hit.tabTopY + (uiString - 1).coerceAtLeast(0) * hit.stringSpacing
                    }
                    // The same Beat can have separate rendered bounds for standard notation
                    // and TAB. Pick this note's bounds closest to its expected TAB string,
                    // rather than the first result returned by findBeat().
                    val tappedBounds = allBeatBounds.asSequence()
                        .flatMap { it.notes?.toList().orEmpty().asSequence() }
                        .filter { it.note === note }
                        .minByOrNull { nb ->
                            val centerY = nb.noteHeadBounds.y.toDouble() + nb.noteHeadBounds.h.toDouble() / 2.0
                            kotlin.math.abs(centerY - (expectedTabY?.toDouble() ?: centerY))
                        }
                        ?.noteHeadBounds
                    tappedNoteBounds = tappedBounds?.let { bounds ->
                        RectF(
                            bounds.x.toFloat(),
                            bounds.y.toFloat(),
                            (bounds.x + bounds.w).toFloat(),
                            (bounds.y + bounds.h).toFloat()
                        )
                    }
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
                    updateStatus("TAB NOTE SELECTED • B" + (currentBarIndex + 1) +
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

            // IMPORTANT: noteHeadBounds may refer to standard-notation noteheads,
            // not the corresponding fret-number glyph in the TAB staff. Inferring
            // the TAB origin from those noteheads shifts the orange selection into
            // the gap between notation and TAB (the regression shown in the device
            // screenshot). Derive TAB geometry from AlphaTab's rendered bar bounds
            // instead; these bounds include the complete Score+TAB staff system.
            for ((system, barsInSystem) in barMeta.groupBy { it.system }) {
                val tops = barsInSystem
                    .map { it.y + it.h * 0.61f }
                    .filter { it.isFinite() }
                    .sorted()
                val spacings = barsInSystem
                    .map { (it.h * 0.075f).coerceIn(6f, 24f) }
                    .filter { it.isFinite() }
                    .sorted()
                if (tops.isNotEmpty()) systemTop[system] = tops[tops.size / 2]
                if (spacings.isNotEmpty()) systemSpacing[system] = spacings[spacings.size / 2]
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
                // There are no virtual measures. normalizeImportedTracks() and
                // ensureBarEditable() materialize every empty measure into real rests.
                // If a malformed bar still reaches this point, skip it rather than
                // fabricating a synthetic editing target.
                if (beats.isEmpty()) continue

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
                            beatRef = beat,
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

        /**
         * Exact measure hit-test for the long-press BAR action.
         *
         * The coordinates come from the Android AlphaTabView touch event. AlphaTab's
         * renderer bounds are layout-space coordinates, so we convert the touch point
         * into that same coordinate system using the real AlphaTab scroll container.
         *
         * There is intentionally NO nearest-bar/nearest-beat fallback: if the point is
         * outside the rendered Bar rectangle, the long press is rejected.
         */
        // Every rendered measure is an independent editing target. This resolver
        // deliberately works from the Score/AlphaTab bar bounds, not from the current
        // caret or from beatHits, so no measure can become a "secondary" target.
        private fun resolveRenderedBarAtPoint(x: Float, y: Float): Int {
            return try {
                val scroll = actualScrollOffsetsLayout()

                val point = alphaTabContentPoint(x, y, scroll)

                val contentX = point.first

                val contentY = point.second
                val staff = score.api.score?.tracks?.toList()
                    ?.getOrNull(currentTrackIndex)?.staves?.firstOrNull() ?: return -1
                val lookup = score.api.renderer.boundsLookup ?: return -1
                for ((index, bar) in staff.bars.toList().withIndex()) {
                    val mb = lookup.findMasterBar(bar.masterBar) ?: continue
                    val bounds = mb.bars.toList().firstOrNull { it.bar === bar } ?: continue
                    val r = bounds.realBounds
                    if (contentX >= r.x.toDouble() &&
                        contentX <= (r.x + r.w).toDouble() &&
                        contentY >= r.y.toDouble() &&
                        contentY <= (r.y + r.h).toDouble()) {
                        android.util.Log.d(
                            "EARAM_BAR_SELECTION",
                            "RENDERED BAR TARGET index=" + (index + 1) +
                                " content=" + contentX + "," + contentY +
                                " bounds=" + r +
                                " totalBars=" + staff.bars.toList().size
                        )
                        return index
                    }
                }
                -1
            } catch (t: Throwable) {
                android.util.Log.e("EARAM_BAR_SELECTION", "rendered bar resolver failed", t)
                -1
            }
        }

        private fun ensureBarEditable(index: Int): Boolean {
            val bs = bars() ?: return false
            if (index !in bs.indices) return false
            val bar = bs[index]
            var changed = false
            var voice = bar.voices.toList().getOrNull(currentVoiceIndex)
            if (voice == null) {
                voice = alphaTab.model.Voice()
                bar.addVoice(voice)
                changed = true
            }
            if (voice.beats.toList().isEmpty()) {
                addEmptyBeatsForTimeSignature(
                    voice,
                    bar.masterBar.timeSignatureNumerator.toInt().coerceIn(1, 32),
                    bar.masterBar.timeSignatureDenominator.toInt().coerceIn(1, 32)
                )
                changed = true
            } else if (!AlphaTabRhythmEngine.fillVoiceToBarCapacity(bar, currentVoiceIndex)) {
                return false
            }
            if (changed) {
                score.api.score?.finish(score.settings)
                renderAndLog("materialize-edit-target")
                buildBeatHits()
            }
            return voice.beats.toList().isNotEmpty()
        }

        private fun activateBarAsEditingTarget(index: Int): Boolean {
            val bs = bars() ?: return false
            if (index !in bs.indices) return false
            if (!ensureBarEditable(index)) return false
            caret = caret.copy(measureIndex = index, beatIndex = 0)
            session.caret = caret
            armed = true
            pendingFret = ""
            selectionTarget = SelectionTarget.BEAT
            updateCursor()
            onSelectionChanged?.invoke()
            android.util.Log.d(
                "EARAM_BAR_SELECTION",
                "EDIT TARGET ACTIVE bar=" + (index + 1) +
                    " beat=1 voice=" + (currentVoiceIndex + 1) +
                    " totalBars=" + bs.size
            )
            return true
        }

        private fun alphaTabContentPoint(x: Float, y: Float, scroll: Pair<Float, Float>): Pair<Float, Float> {
            val density = activity.resources.displayMetrics.density.coerceAtLeast(0.01f)
            val scale = score.settings.display.scale.toFloat().coerceIn(0.1f, 4f)
            return Pair(
                x / (density * scale) + scroll.first,
                y / (density * scale) + scroll.second
            )
        }

        private fun hitRenderedBarAtPoint(x: Float, y: Float): Int {
            return try {
                val scroll = actualScrollOffsetsLayout()

                val point = alphaTabContentPoint(x, y, scroll)

                val contentX = point.first

                val contentY = point.second

                val song = score.api.score ?: return -1
                val staff = song.tracks.toList()
                    .getOrNull(currentTrackIndex)
                    ?.staves
                    ?.firstOrNull() ?: return -1
                val lookup = score.api.renderer.boundsLookup ?: return -1

                staff.bars.toList().forEachIndexed { index, bar ->
                    val masterBounds = lookup.findMasterBar(bar.masterBar) ?: return@forEachIndexed
                    val barBounds = masterBounds.bars.toList()
                        .firstOrNull { it.bar === bar } ?: return@forEachIndexed

                    val left = barBounds.realBounds.x.toDouble()
                    val top = barBounds.realBounds.y.toDouble()
                    val right = left + barBounds.realBounds.w.toDouble()
                    val bottom = top + barBounds.realBounds.h.toDouble()

                    if (contentX.toDouble() >= left &&
                        contentX.toDouble() <= right &&
                        contentY.toDouble() >= top &&
                        contentY.toDouble() <= bottom
                    ) {
                        android.util.Log.d(
                            "EARAM_BAR_SELECTION",
                            "EXACT BAR HIT index=" + (index + 1) +
                                " touchPx=" + x + "," + y +
                                " content=" + contentX + "," + contentY +
                                " bounds=" + left + "," + top + "," + right + "," + bottom +
                                " density=" + activity.resources.displayMetrics.density +
                                " scrollLayout=" + scroll.first + "," + scroll.second
                        )
                        return index
                    }
                }
                -1
            } catch (t: Throwable) {
                android.util.Log.e("EARAM_BAR_SELECTION", "exact bar hit-test failed", t)
                -1
            }
        }

        private fun hitTest(x: Float, y: Float): BeatHit? {
            val scroll = actualScrollOffsetsLayout()
            val point = alphaTabContentPoint(x, y, scroll)
            val contentX = point.first
            val contentY = point.second
            return beatHits.firstOrNull { hit ->
                contentX >= hit.rect.left && contentX <= hit.rect.right &&
                    contentY >= hit.tabTopY &&
                    contentY <= hit.tabTopY + 5f * hit.stringSpacing
            }
        }

        // Manual range selection needs a forgiving finger target. Resolve against the
        // entire rendered Beat rectangle rather than only the six TAB string lines.
        // Paste destination hit-test is intentionally BAR-first. A rendered TAB page
        // can contain systems/bounds that are not reliable beat hit targets after scrolling.
        // The measure itself is the authoritative destination; once its bar is known,
        // choose the beat whose rendered bounds contain the tap, otherwise use beat 0.
        private fun resolvePasteDestinationAtPoint(x: Float, y: Float): Pair<Int, Int>? {
            return try {
                val scroll = actualScrollOffsetsLayout()

                val point = alphaTabContentPoint(x, y, scroll)

                val contentX = point.first

                val contentY = point.second
                val song = score.api.score ?: return null
                val staff = song.tracks.toList().getOrNull(currentTrackIndex)
                    ?.staves?.firstOrNull() ?: return null
                val lookup = score.api.renderer.boundsLookup ?: return null

                var targetBarIndex = -1
                var targetBar: alphaTab.model.Bar? = null
                for ((mi, bar) in staff.bars.toList().withIndex()) {
                    val mb = lookup.findMasterBar(bar.masterBar) ?: continue
                    val bb = mb.bars.toList().firstOrNull { it.bar === bar } ?: continue
                    val r = bb.realBounds
                    if (contentX >= r.x.toDouble() &&
                        contentX <= (r.x + r.w).toDouble() &&
                        contentY >= r.y.toDouble() &&
                        contentY <= (r.y + r.h).toDouble()) {
                        targetBarIndex = mi
                        targetBar = bar
                        break
                    }
                }
                val bar = targetBar ?: return null
                var beats = bar.voices.toList().getOrNull(currentVoiceIndex)
                    ?.beats?.toList().orEmpty()
                if (beats.isEmpty()) {
                    if (!ensureBarEditable(targetBarIndex)) return null
                    beats = bar.voices.toList().getOrNull(currentVoiceIndex)
                        ?.beats?.toList().orEmpty()
                }
                if (beats.isEmpty()) return null

                var targetBeatIndex = 0
                for ((bi, beat) in beats.withIndex()) {
                    val b = lookup.findBeat(beat) ?: continue
                    val r = b.realBounds
                    if (contentX >= r.x.toDouble() &&
                        contentX <= (r.x + r.w).toDouble() &&
                        contentY >= r.y.toDouble() &&
                        contentY <= (r.y + r.h).toDouble()) {
                        targetBeatIndex = bi
                        break
                    }
                }
                android.util.Log.d(
                    "EARAM_PASTE",
                    "DESTINATION BAR HIT bar=" + (targetBarIndex + 1) +
                        " beat=" + (targetBeatIndex + 1) +
                        " content=" + contentX + "," + contentY +
                        " beatsInBar=" + beats.size +
                        " scrollLayout=" + scroll.first + "," + scroll.second
                )
                Pair(targetBarIndex, targetBeatIndex)
            } catch (t: Throwable) {
                android.util.Log.e("EARAM_PASTE", "bar-first destination hit failed", t)
                null
            }
        }

        private fun hitRangeBeatAtPoint(x: Float, y: Float): Beat? {
            return try {
                val scroll = actualScrollOffsetsLayout()

                val point = alphaTabContentPoint(x, y, scroll)

                val contentX = point.first

                val contentY = point.second

                val hit = beatHits.firstOrNull { candidate ->
                    !candidate.virtual &&
                        contentX >= candidate.rect.left &&
                        contentX <= candidate.rect.right &&
                        contentY >= candidate.rect.top &&
                        contentY <= candidate.rect.bottom
                } ?: return null

                android.util.Log.v(
                    "EARAM_SELECTION",
                    "MOVE x=" + x + " y=" + y +
                        " content=" + contentX + "," + contentY +
                        " bar=" + (hit.measure + 1) +
                        " beat=" + (hit.beat + 1)
                )
                hit.beatRef
            } catch (t: Throwable) {
                android.util.Log.w("EARAM_SELECTION", "range beat hit-test failed", t)
                null
            }
        }

        private fun handleScoreTouch(x: Float, y: Float): Boolean {
            val scroll = actualScrollOffsetsLayout()

            val point = alphaTabContentPoint(x, y, scroll)

            val contentX = point.first

            val contentY = point.second

            // First hit the actual rendered TAB note/fret. The visible note is the
            // primary editor target, not an approximate screen-space caret.
            try {
                val lookup = score.api.renderer.boundsLookup
                val song = score.api.score
                val staff = song?.tracks?.toList()?.getOrNull(currentTrackIndex)?.staves?.firstOrNull()
                if (lookup != null && staff != null) {
                    for ((mi, bar) in staff.bars.toList().withIndex()) {
                        val voice = bar.voices.toList().getOrNull(currentVoiceIndex) ?: continue
                        for ((bi, beat) in voice.beats.toList().withIndex()) {
                            val beatBounds = runCatching { lookup.findBeats(beat)?.toList().orEmpty() }
                                .getOrDefault(listOfNotNull(lookup.findBeat(beat)))
                            val tabHit = beatHits.firstOrNull { it.measure == mi && it.beat == bi && !it.virtual }
                            for (nb in beatBounds.flatMap { it.notes?.toList().orEmpty() }) {
                                val r = nb.noteHeadBounds
                                val uiString = (maxStringIndex() + 1 - nb.note.string.toInt())
                                    .coerceIn(1, maxStringIndex())
                                val expectedTabY = tabHit?.let { it.tabTopY + (uiString - 1) * it.stringSpacing }
                                val centerY = r.y.toFloat() + r.h.toFloat() / 2f
                                val onTabString = expectedTabY == null ||
                                    kotlin.math.abs(centerY - expectedTabY) <= ((tabHit?.stringSpacing ?: 0f) * 0.7f).coerceAtLeast(7f)
                                if (onTabString &&
                                    contentX >= r.x.toFloat() - 7f &&
                                    contentX <= (r.x + r.w).toFloat() + 7f &&
                                    contentY >= r.y.toFloat() - 5f &&
                                    contentY <= (r.y + r.h).toFloat() + 5f) {
                                    caret = Caret(currentTrackIndex, mi, bi, uiString)
                                    session.caret = caret
                                    // A direct TAB-number tap selects that exact NOTE.
                                    // Keep the model object itself: a chord may contain several
                                    // notes in one Beat, and Delete must remove only this one.
                                    selectionTarget = SelectionTarget.NOTE
                                    selectedNoteRef = nb.note
                                    tappedNoteBounds = RectF(
                                        r.x.toFloat(), r.y.toFloat(),
                                        (r.x + r.w).toFloat(), (r.y + r.h).toFloat()
                                    )
                                    armed = true
                                    pendingFret = ""
                                    try {
                                        score.api.stop()
                                        score.api.tickPosition = beat.absolutePlaybackStart
                                        session.tickPosition = score.api.tickPosition
                                    } catch (_: Throwable) { }
                                    updateCursor()
                                    pasteDestinationCaret = caret
                                    pasteDestinationBeat = beat
                                    awaitingPasteDestination = false
                                    android.util.Log.d("EARAM_BAR_SELECTION", "TAB NOTE INSIDE BAR TARGET bar=" + (mi + 1) + " beat=" + (bi + 1) + " string=" + uiString + " fret=" + nb.note.fret.toInt())
                                    updateStatus("SELECTED BAR • " + (mi+1) + " • BEAT " + (bi+1) + " • STRING " + uiString)
                                    onSelectionChanged?.invoke()
                                    score.requestFocus()
                                    return true
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("EARAM_SELECTION", "direct TAB note hit-test failed", t)
            }

            // A TAB tap that did not hit an actual note selects the WHOLE
            // rendered measure. A measure is the primary editing target in Earam:
            // Copy/Paste/Clear/Delete/Duplicate and all Bar actions must operate on
            // the exact visible measure, never on an inferred/nearest beat.
            val renderedBar = resolveRenderedBarAtPoint(x, y)
            if (renderedBar >= 0) {
                caret = Caret(currentTrackIndex, renderedBar, 0, 1)
                session.caret = caret
                selectionTarget = SelectionTarget.BAR
                selectedNoteRef = null
                tappedNoteBounds = null
                overlay.hideNoteSelection()
                armed = true
                pendingFret = ""
                pasteDestinationCaret = caret
                pasteDestinationBeat = null
                awaitingPasteDestination = false
                android.util.Log.d(
                    "EARAM_BAR_SELECTION",
                    "DIRECT BAR TARGET index=" + (renderedBar + 1) +
                        " totalBars=" + (bars()?.size ?: 0)
                )
                updateCursor()
                updateStatus("SELECTED BAR • " + (renderedBar + 1))
                onSelectionChanged?.invoke()
                score.requestFocus()
                return true
            }

            // If the touch is not inside a real rendered measure, reject it.
            // Never select a nearest beat or another measure.
            return false
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
            // A writable measure must have its complete rhythmic rest grid, not a
            // single fabricated beat. This keeps fret entry valid for every real measure.
            addEmptyBeatsForTimeSignature(
                voice,
                bar.masterBar.timeSignatureNumerator.toInt().coerceIn(1, 32),
                bar.masterBar.timeSignatureDenominator.toInt().coerceIn(1, 32)
            )
            caret = caret.copy(beatIndex = caret.beatIndex.coerceIn(0, voice.beats.toList().lastIndex.coerceAtLeast(0)))
            session.caret = caret
            score.api.score?.finish(score.settings)
            renderAndLog("materialize-empty-measure")
            return voice.beats.toList().getOrNull(caret.beatIndex)
        }

        private fun writeFretInternal(beat: Beat, fret: Int) {
            val song = score.api.score ?: return
            val alphaString = alphaTabString(caret.stringIndex)
            val existing = beat.getNoteOnString(alphaString.toDouble())
            val writtenNote = if (existing != null) {
                existing.fret = fret.toDouble()
                existing.finish(score.settings, null)
                existing
            } else {
                Note().apply {
                    string = alphaString.toDouble()
                    this.fret = fret.toDouble()
                    beat.addNote(this)
                    finish(score.settings, null)
                }
            }

            // The selection represents the actual note object, not a decorative
            // caret. Immediately select the fret just written on this string/beat,
            // so the orange outline follows the number as soon as it appears.
            selectionTarget = SelectionTarget.NOTE
            selectedNoteRef = writtenNote
            tappedNoteBounds = null
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

        private fun snapshotNoteForClipboard(note: Note): ClipboardNote = ClipboardNote(
            note.string, note.fret, note.isHammerPullOrigin, note.isPalmMute,
            note.isLetRing, note.isGhost, note.isDead, note.isStaccato,
            note.vibrato, note.isLeftHandTapped
        )

        private fun snapshotBeatForClipboard(beat: Beat): ClipboardBeat = ClipboardBeat(
            beat.duration, beat.dots, beat.tupletNumerator, beat.tupletDenominator,
            beat.isEmpty, beat.notes.toList().map(::snapshotNoteForClipboard),
            beat.slap, beat.pop, beat.tap, beat.deadSlapped, beat.fadeIn,
            beat.slashed, beat.showTimer, beat.text
        )

        private fun applyClipboardBeat(target: Beat, source: ClipboardBeat) {
            target.notes.toList().forEach { target.removeNote(it) }
            target.duration = source.duration
            target.dots = source.dots
            target.tupletNumerator = source.tupletNumerator
            target.tupletDenominator = source.tupletDenominator
            target.slap = source.slap
            target.pop = source.pop
            target.tap = source.tap
            target.deadSlapped = source.deadSlapped
            target.fadeIn = source.fadeIn
            target.slashed = source.slashed
            target.showTimer = source.showTimer
            target.text = source.text
            source.notes.forEach { src ->
                target.addNote(Note().apply {
                    string = src.string
                    fret = src.fret
                    isHammerPullOrigin = src.isHammerPullOrigin
                    isPalmMute = src.isPalmMute
                    isLetRing = src.isLetRing
                    isGhost = src.isGhost
                    isDead = src.isDead
                    isStaccato = src.isStaccato
                    vibrato = src.vibrato
                    isLeftHandTapped = src.isLeftHandTapped
                })
            }
            target.isEmpty = source.isEmpty || source.notes.isEmpty()
            target.notes.toList().forEach { it.finish(score.settings, null) }
            target.finish(score.settings, null)
        }

        private fun snapshotBarForClipboard(bar: Bar): ClipboardBar {
            val voice = bar.voices.toList().getOrNull(currentVoiceIndex)
            return ClipboardBar(
                voice?.beats?.toList()?.map(::snapshotBeatForClipboard).orEmpty(),
                bar.masterBar.timeSignatureNumerator.toInt(),
                bar.masterBar.timeSignatureDenominator.toInt()
            )
        }

        private fun snapshotSelectedRange(): List<ClipboardBeat> {
            val anchor = selectionAnchorBeat ?: return emptyList()
            val focus = selectionFocusBeat ?: return emptyList()
            val start = minOf(anchor.absolutePlaybackStart, focus.absolutePlaybackStart)
            val end = maxOf(anchor.absolutePlaybackStart, focus.absolutePlaybackStart)
            val voice = currentVoiceIndex
            val result = mutableListOf<ClipboardBeat>()
            val bs = bars().orEmpty()
            for (bar in bs) {
                val beats = bar.voices.toList().getOrNull(voice)?.beats?.toList().orEmpty()
                for (beat in beats) {
                    val tick = beat.absolutePlaybackStart
                    if (tick >= start && tick <= end) {
                        result.add(snapshotBeatForClipboard(beat))
                    }
                }
            }
            return result
        }

        private fun clearClipboardExcept(kind: String) {
            if (kind != "note") selectionClipboardNote = null
            if (kind != "beat") selectionClipboardBeat = null
            if (kind != "bar") {
                selectionClipboardBar = null
                copiedBar = null
            }
            if (kind != "range") selectionClipboardRange = null
            clipboardKind = when (kind) {
                "note" -> ClipboardKind.NOTE
                "beat" -> ClipboardKind.BEAT
                "bar" -> ClipboardKind.BAR
                "range" -> ClipboardKind.RANGE
                else -> null
            }
        }

        fun copyCurrentSelectionFromUi() {
            when (selectionTarget) {
                SelectionTarget.NOTE -> {
                    val note = selectedNote() ?: run { updateStatus("Select a fret on the TAB first"); return }
                    clearClipboardExcept("note")
                    selectionClipboardNote = snapshotNoteForClipboard(note)
                    copiedFret = note.fret.toInt()
                    updateStatus("Copied NOTE • F" + copiedFret + " • TAB")
                }
                SelectionTarget.BEAT -> {
                    val beat = requireTabSelection() ?: return
                    clearClipboardExcept("beat")
                    selectionClipboardBeat = snapshotBeatForClipboard(beat)
                    updateStatus("Copied BEAT • B" + (currentBarIndex + 1) + " • " + (currentBeatIndex + 1))
                }
                SelectionTarget.BAR -> {
                    val bar = bars()?.getOrNull(currentBarIndex) ?: return
                    clearClipboardExcept("bar")
                    selectionClipboardBar = snapshotBarForClipboard(bar)
                    copiedBar = bar
                    updateStatus("Copied BAR " + (currentBarIndex + 1) + " • TAB")
                }
                SelectionTarget.RANGE -> {
                    val range = snapshotSelectedRange()
                    if (range.isEmpty()) {
                        updateStatus("Nothing to copy in selected range")
                        return
                    }
                    clearClipboardExcept("range")
                    selectionClipboardRange = range
                    // RANGE copy enters an explicit destination-selection state.
                    // The next TAB tap is resolved against the rendered bar/beat map,
                    // so the source selection can never be reused as the paste target.
                    pasteDestinationCaret = null
                    pasteDestinationBeat = null
                    awaitingPasteDestination = true
                    updateStatus("Copied RANGE • " + range.size + " BEATS • TAP DESTINATION THEN PASTE")
                }
            }
        }

        fun pasteCurrentSelectionFromUi() {
            // Paste follows the copied payload, not selectionTarget.
            // The whole command is guarded here because this is a UI entry point:
            // an invalid AlphaTab mutation must never be allowed to terminate Earam.
            try {
                android.util.Log.d(
                    "EARAM_PASTE",
                    "PASTE command kind=" + clipboardKind +
                        " caretBar=" + (currentBarIndex + 1) +
                        " caretBeat=" + (currentBeatIndex + 1) +
                        " voice=" + (currentVoiceIndex + 1)
                )
                when (clipboardKind) {
                    ClipboardKind.NOTE -> pasteCopiedNote()
                    ClipboardKind.BEAT -> pasteCopiedBeat()
                    ClipboardKind.BAR -> pasteCopiedBar()
                    ClipboardKind.RANGE -> {
                        val srcRange = selectionClipboardRange
                        if (srcRange == null || srcRange.isEmpty()) {
                            updateStatus("Copied range is empty")
                            return
                        }
                        pasteRangeAtCurrentCaret(srcRange)
                    }
                    null -> updateStatus("Clipboard is empty")
                }
            } catch (t: Throwable) {
                android.util.Log.e("EARAM_PASTE", "UNCAUGHT paste failure", t)
                updateStatus("Paste failed • " + (t.message ?: t.javaClass.simpleName))
            }
        }

        private fun pasteCopiedNote() {
            val src = selectionClipboardNote ?: run {
                updateStatus("Clipboard is empty")
                return
            }
            val beat = ensureRealBeatForCaret() ?: return
            pushUndoSnapshot()
            val target = beat.getNoteOnString(alphaTabString(currentStringIndex).toDouble())
            if (target == null) {
                beat.addNote(Note().apply {
                    string = alphaTabString(currentStringIndex).toDouble()
                    fret = src.fret
                    isHammerPullOrigin = src.isHammerPullOrigin
                    isPalmMute = src.isPalmMute
                    isLetRing = src.isLetRing
                    isGhost = src.isGhost
                    isDead = src.isDead
                    isStaccato = src.isStaccato
                    vibrato = src.vibrato
                    isLeftHandTapped = src.isLeftHandTapped
                })
            } else {
                target.fret = src.fret
                target.isHammerPullOrigin = src.isHammerPullOrigin
                target.isPalmMute = src.isPalmMute
                target.isLetRing = src.isLetRing
                target.isGhost = src.isGhost
                target.isDead = src.isDead
                target.isStaccato = src.isStaccato
                target.vibrato = src.vibrato
                target.isLeftHandTapped = src.isLeftHandTapped
            }
            beat.isEmpty = false
            beat.finish(score.settings, null)
            score.api.score?.finish(score.settings)
            renderAndLog("paste-note")
            updateStatus(
                "Pasted NOTE • BAR " + (currentBarIndex + 1) +
                    " • BEAT " + (currentBeatIndex + 1) +
                    " • F" + src.fret.toInt()
            )
            onSelectionChanged?.invoke()
        }

        private fun pasteCopiedBeat() {
            val src = selectionClipboardBeat ?: run {
                updateStatus("No beat copied")
                return
            }
            val target = ensureRealBeatForCaret() ?: return
            pushUndoSnapshot()
            applyClipboardBeat(target, src)
            score.api.score?.finish(score.settings)
            renderAndLog("paste-beat")
            updateStatus(
                "Pasted BEAT • BAR " + (currentBarIndex + 1) +
                    " • BEAT " + (currentBeatIndex + 1)
            )
            onSelectionChanged?.invoke()
        }

        private fun pasteCopiedBar() {
            try {
                val src = selectionClipboardBar ?: run {
                    updateStatus("No bar copied")
                    return
                }
                val bs = bars() ?: return
                val targetBarIndex = currentBarIndex
                if (targetBarIndex !in bs.indices) {
                    updateStatus("Invalid paste destination")
                    return
                }

                val targetBar = bs[targetBarIndex]
                val sourceBeats = src.beats
                var voice = targetBar.voices.toList().getOrNull(currentVoiceIndex)
                if (voice == null) {
                    voice = alphaTab.model.Voice()
                    targetBar.addVoice(voice)
                }

                pushUndoSnapshot()

                // Do NOT destroy and recreate the destination Voice. AlphaTab keeps
                // parent/renderer state on Beat objects, and rebuilding the whole
                // collection can crash during the following render/finish cycle.
                // Reuse the real destination beats whenever possible.
                var targetBeats = voice.beats.toList()

                // Make sure the destination is a real editable measure first.
                if (targetBeats.isEmpty()) {
                    addEmptyBeatsForTimeSignature(
                        voice,
                        targetBar.masterBar.timeSignatureNumerator.toInt().coerceIn(1, 32),
                        targetBar.masterBar.timeSignatureDenominator.toInt().coerceIn(1, 32)
                    )
                    targetBeats = voice.beats.toList()
                }

                // Apply copied beats onto existing AlphaTab beats.
                val common = minOf(targetBeats.size, sourceBeats.size)
                for (i in 0 until common) {
                    applyClipboardBeat(targetBeats[i], sourceBeats[i])
                }

                // If the copied bar has more beats, append real Beat objects.
                if (sourceBeats.size > targetBeats.size) {
                    for (i in targetBeats.size until sourceBeats.size) {
                        val tb = Beat()
                        applyClipboardBeat(tb, sourceBeats[i])
                        voice.addBeat(tb)
                    }
                }

                // If the destination had extra beats, remove only the surplus tail.
                // This preserves the existing Beat objects used by the renderer.
                while (voice.beats.toList().size > sourceBeats.size && sourceBeats.isNotEmpty()) {
                    val last = voice.beats.toList().lastIndex
                    voice.beats.splice(last.toDouble(), 1.0)
                }

                score.api.score?.finish(score.settings)
                caret = caret.copy(measureIndex = targetBarIndex, beatIndex = 0)
                session.caret = caret
                selectionTarget = SelectionTarget.BAR
                armed = true
                pendingFret = ""
                renderAndLog("paste-bar")
                buildBeatHits()
                highlightSelectedBar()
                updateCursor()
                updateStatus("Pasted BAR • BAR " + (targetBarIndex + 1))
                onSelectionChanged?.invoke()
            } catch (t: Throwable) {
                android.util.Log.e("EARAM_PASTE", "BAR paste failed", t)
                updateStatus("Paste bar failed • " + (t.message ?: t.javaClass.simpleName))
            }
        }

        private fun pasteRangeAtCurrentCaret(srcRange: List<ClipboardBeat>) {
            val bs = bars() ?: return
            // The editor caret is the only paste destination.
            // Never use a stale Beat/object or a remembered source location.
            val destination = caret
            var barIndex = destination.measureIndex
            var beatIndex = destination.beatIndex
            if (barIndex !in bs.indices) {
                updateStatus("Invalid paste destination")
                return
            }
            if (!ensureBarEditable(barIndex)) {
                updateStatus("Target measure is not editable")
                return
            }

            val startBar = barIndex
            val startBeat = beatIndex
            android.util.Log.d(
                "EARAM_PASTE",
                "PASTE RANGE START destination=BAR " + (startBar + 1) +
                    " BEAT " + (startBeat + 1) +
                    " sourceBeats=" + srcRange.size
            )

            pushUndoSnapshot()
            var pasted = 0
            for (src in srcRange) {
                while (barIndex < bs.size) {
                    if (!ensureBarEditable(barIndex)) break
                    val voice = bs[barIndex].voices.toList().getOrNull(currentVoiceIndex)
                        ?: break
                    val beats = voice.beats.toList()
                    if (beatIndex < beats.size) {
                        applyClipboardBeat(beats[beatIndex], src)
                        pasted++
                        beatIndex++
                        break
                    }
                    barIndex++
                    beatIndex = 0
                }
                if (barIndex >= bs.size) break
            }

            if (pasted == 0) {
                updateStatus("Nothing pasted")
                return
            }

            score.api.score?.finish(score.settings)
            renderAndLog("paste-range")
            caret = caret.copy(
                measureIndex = startBar,
                beatIndex = startBeat
            )
            session.caret = caret
            pasteDestinationCaret = null
            awaitingPasteDestination = false
            updateCursor()
            onSelectionChanged?.invoke()
            updateStatus(
                "Pasted RANGE • BAR " + (startBar + 1) +
                    " • BEAT " + (startBeat + 1) +
                    " • " + pasted + " BEATS"
            )
        }

        fun copyCurrentNoteFromUi() = copyCurrentSelectionFromUi()
        fun pasteCurrentNoteFromUi() = pasteCurrentSelectionFromUi()

        private fun currentVoice(): alphaTab.model.Voice? =
            bars()?.getOrNull(currentBarIndex)?.voices?.toList()?.getOrNull(currentVoiceIndex)

        /** The TAB selection is the single source of truth for edit commands. */
        private fun selectedBeat(): alphaTab.model.Beat? {
            val bs = bars() ?: return null
            val bar = bs.getOrNull(caret.measureIndex) ?: return null
            val voice = bar.voices.toList().getOrNull(currentVoiceIndex) ?: return null
            return voice.beats.toList().getOrNull(caret.beatIndex)
        }

        private fun selectedNote(): alphaTab.model.Note? {
            val beat = selectedBeat() ?: return null
            val exact = selectedNoteRef
            if (selectionTarget == SelectionTarget.NOTE && exact != null &&
                exact.beat === beat && beat.notes.toList().any { it === exact }) {
                return exact
            }
            return beat.getNoteOnString(alphaTabString(caret.stringIndex).toDouble())
        }

        private fun requireTabSelection(): alphaTab.model.Beat? {
            val beat = selectedBeat()
            if (beat == null) {
                updateStatus("Select a beat or fret on the TAB first")
            }
            return beat
        }

        private fun currentBeat(): alphaTab.model.Beat? = selectedBeat()

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
                        val oldNumerator = master.timeSignatureNumerator
                        val oldDenominator = master.timeSignatureDenominator
                        val oldCommon = master.timeSignatureCommon
                        master.timeSignatureNumerator = n.toDouble()
                        master.timeSignatureDenominator = d.toDouble()
                        master.timeSignatureCommon = n == 4 && d == 4
                        if (!syncBarRhythmToTimeSignature(selectedBarIndex)) {
                            master.timeSignatureNumerator = oldNumerator
                            master.timeSignatureDenominator = oldDenominator
                            master.timeSignatureCommon = oldCommon
                            throw IllegalArgumentException("Time signature is smaller than the existing musical content")
                        }
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

        private fun syncBarRhythmToTimeSignature(barIndex: Int): Boolean {
            val song=score.api.score ?: return false
            for(track in song.tracks.toList()) for(staff in track.staves.toList()) {
                val bar=staff.bars.toList().getOrNull(barIndex) ?: continue
                for(voiceIndex in bar.voices.toList().indices) {
                    if(!AlphaTabRhythmEngine.fillVoiceToBarCapacity(bar,voiceIndex)) return false
                }
            }
            return true
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
                        slap = sourceBeat.slap
                        pop = sourceBeat.pop
                        tap = sourceBeat.tap
                        deadSlapped = sourceBeat.deadSlapped
                        fadeIn = sourceBeat.fadeIn
                        slashed = sourceBeat.slashed
                        showTimer = sourceBeat.showTimer
                        text = sourceBeat.text
                        pickStroke = sourceBeat.pickStroke
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
                            vibrato = sourceNote.vibrato
                            isLeftHandTapped = sourceNote.isLeftHandTapped
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
                val master = song.masterBars.toList().getOrNull(selectedBarIndex)
                    ?: throw IllegalStateException("No current master bar")
                copiedBar = cloneBarForScore(source, master)
                selectionTarget = SelectionTarget.BAR
                caret = caret.copy(measureIndex = selectedBarIndex, beatIndex = 0)
                session.caret = caret
                armed = true
                pendingFret = ""
                updateCursor()
                onSelectionChanged?.invoke()
                updateStatus("Copied BAR " + (selectedBarIndex + 1) + " • TRACK " + (currentTrackIndex + 1))
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
                val staff = song.tracks.toList()
                    .getOrNull(currentTrackIndex)
                    ?.staves?.firstOrNull()
                    ?: run {
                        updateStatus("No active track")
                        return
                    }
                val target = staff.bars.toList().getOrNull(targetIndex)
                    ?: run {
                        updateStatus("Invalid target measure")
                        return
                    }

                pushUndoSnapshot()

                // Bar paste is track-local: only the selected measure of the active
                // track is modified. Other tracks are never overwritten accidentally.
                val sourceVoices = source.voices.toList()
                while (target.voices.toList().size < sourceVoices.size) {
                    target.addVoice(alphaTab.model.Voice())
                }

                for (vi in target.voices.toList().indices) {
                    val tv = target.voices.toList()[vi]
                    val sv = sourceVoices.getOrNull(vi)

                    tv.beats.toList().forEach { beat ->
                        beat.notes.toList().forEach { note -> beat.removeNote(note) }
                    }
                    while (tv.beats.toList().isNotEmpty()) {
                        tv.beats.splice((tv.beats.toList().lastIndex).toDouble(), 1.0)
                    }
                    if (sv == null) continue

                    for (sb in sv.beats.toList()) {
                        val tb = Beat().apply {
                            duration = sb.duration
                            dots = sb.dots
                            tupletNumerator = sb.tupletNumerator
                            tupletDenominator = sb.tupletDenominator
                            slap = sb.slap
                            pop = sb.pop
                            tap = sb.tap
                            deadSlapped = sb.deadSlapped
                            fadeIn = sb.fadeIn
                            slashed = sb.slashed
                            showTimer = sb.showTimer
                            text = sb.text
                            isEmpty = sb.isEmpty
                        }
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
                                vibrato = sn.vibrato
                                isLeftHandTapped = sn.isLeftHandTapped
                            })
                        }
                        tb.isEmpty = sb.isEmpty || tb.notes.toList().isEmpty()
                        tb.finish(score.settings, null)
                        tv.addBeat(tb)
                    }
                }

                song.finish(score.settings)
                caret = caret.copy(measureIndex = targetIndex, beatIndex = 0)
                session.caret = caret
                selectionTarget = SelectionTarget.BAR
                armed = true
                pendingFret = ""
                renderAndLog("paste-bar")
                buildBeatHits()
                highlightSelectedBar()
                updateCursor()
                onSelectionChanged?.invoke()
                updateStatus("Pasted BAR " + (targetIndex + 1) + " • TRACK " + (currentTrackIndex + 1))
            } catch (t: Throwable) {
                android.util.Log.e("EARAM_BAR_CLIPBOARD", "Paste bar failed", t)
                updateStatus("Paste bar failed • " + (t.message ?: t.javaClass.simpleName))
            }
        }

        fun addMeasureFromUi() {
            if (createNextMeasures(1)) {
                val total = score.api.score?.masterBars?.toList()?.size ?: 0
                val newIndex = (total - 1).coerceAtLeast(0)
                caret = caret.copy(measureIndex = newIndex, beatIndex = 0)
                session.caret = caret
                selectionTarget = SelectionTarget.BAR
                armed = true
                pendingFret = ""
                buildBeatHits()
                highlightSelectedBar()
                updateCursor()
                updateStatus("Measure added • BAR " + (newIndex + 1) + " • total " + total)
                onSelectionChanged?.invoke()
            } else {
                updateStatus("Could not add measure")
            }
        }

        fun duplicateCurrentBarToEndFromUi() {
            try {
                val song = score.api.score ?: return
                val sourceIndex = selectedBarIndex
                val sourceMaster = song.masterBars.toList().getOrNull(sourceIndex)
                    ?: throw IllegalStateException("No selected measure")

                // Duplicate immediately AFTER the selected measure, not at song end.
                // The selected BAR is the source of truth for this command.
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

                pushUndoSnapshot()
                song.masterBars.splice((sourceIndex + 1).toDouble(), 0.0, newMaster)

                for (track in song.tracks.toList()) {
                    for (staff in track.staves.toList()) {
                        val sourceBar = staff.bars.toList().getOrNull(sourceIndex)
                            ?: continue
                        val clone = cloneBarForScore(sourceBar, newMaster)
                        staff.bars.splice((sourceIndex + 1).toDouble(), 0.0, clone)
                    }
                }

                song.finish(score.settings)
                caret = caret.copy(
                    measureIndex = sourceIndex + 1,
                    beatIndex = 0,
                    stringIndex = 1
                )
                session.caret = caret
                selectionTarget = SelectionTarget.BAR
                armed = true
                pendingFret = ""
                renderAndLog("duplicate-bar")
                highlightSelectedBar()
                updateCursor()
                onSelectionChanged?.invoke()
                updateStatus("Duplicated BAR " + (sourceIndex + 1) + " → BAR " + (selectedBarIndex + 1))
            } catch (t: Throwable) {
                updateStatus("Duplicate bar failed • " + (t.message ?: t.javaClass.simpleName))
            }
        }

        fun clearCurrentBarFromUi() {
            try {
                val song = score.api.score ?: return
                val index = selectedBarIndex
                if (song.masterBars.toList().getOrNull(index) == null) {
                    updateStatus("No selected measure")
                    return
                }

                pushUndoSnapshot()
                var cleared = 0
                // A measure belongs to the score, so clear that measure across every track.
                for (track in song.tracks.toList()) {
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
                }

                song.finish(score.settings)
                selectionTarget = SelectionTarget.BAR
                caret = caret.copy(measureIndex = index, beatIndex = 0)
                session.caret = caret
                renderAndLog("clear-bar")
                highlightSelectedBar()
                updateCursor()
                onSelectionChanged?.invoke()
                updateStatus("Cleared BAR " + (index + 1) + " • " + cleared + " staff(s)")
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
                updateStatus("Deleted BAR " + (index + 1) + " • BAR " + (selectedBarIndex + 1) + " is now selected")
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
            val beat = requireTabSelection() ?: return false
            val bar = bars()?.getOrNull(caret.measureIndex) ?: return false
            pushUndoSnapshot()
            if (!AlphaTabRhythmEngine.changeBeatDuration(bar,currentVoiceIndex,beat,duration,dots,tupletNumerator,tupletDenominator)) {
                updateStatus("Duration does not fit • " + bar.masterBar.timeSignatureNumerator.toInt() + "/" + bar.masterBar.timeSignatureDenominator.toInt())
                return false
            }
            if (beat.notes.toList().isEmpty()) beat.isEmpty = true
            if (!AlphaTabRhythmEngine.fillVoiceToBarCapacity(bar,currentVoiceIndex)) {
                updateStatus("Cannot complete measure • rhythm exceeds bar capacity"); return false
            }
            score.api.score?.finish(score.settings)
            renderAndLog("duration")
            updateCursor()

            // The duration button is a live editor control. Refresh its drawn
            // notation immediately so 8th/16th/32nd/dotted selections are visible
            // without requiring the user to move the caret first.
            onSelectionChanged?.invoke()
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
            val bs=bars() ?: return
            val bar=bs.getOrNull(currentBarIndex) ?: return
            val beat=currentBeat() ?: return
            if(AlphaTabRhythmEngine.nextBeat(bar,beat,currentVoiceIndex)!=null) {
                caret=caret.copy(beatIndex=caret.beatIndex+1); session.caret=caret; updateCursor(); updateStatus(); return
            }
            val used=AlphaTabRhythmEngine.barUsedTicks(bar,currentVoiceIndex)
            val capacity=AlphaTabRhythmEngine.barCapacityTicks(bar)
            if(used<capacity) {
                if(!AlphaTabRhythmEngine.fillVoiceToBarCapacity(bar,currentVoiceIndex)) return
                val count=bar.voices.toList().getOrNull(currentVoiceIndex)?.beats?.toList()?.size ?: return
                if(caret.beatIndex+1<count) {
                    caret=caret.copy(beatIndex=caret.beatIndex+1); session.caret=caret; updateCursor(); updateStatus(); return
                }
            }
            if(currentBarIndex==bs.lastIndex) { if(!createNextMeasures(4)) return }
            val nextMeasure=currentBarIndex+1
            caret=caret.copy(measureIndex=nextMeasure,beatIndex=0); session.caret=caret; updateCursor()
            updateStatus("Measure " + nextMeasure + " • Beat 1")
        }

        /** Arrow navigation never creates a measure. It only moves inside existing Score beats. */
        fun moveBeatFromUi(delta: Int) = moveBeat(delta)
        fun moveStringFromUi(delta: Int) = moveString(delta)
        fun enterDigitFromUi(digit: Int) = acceptDigit(digit)
        fun enterFretFromUi(fret: Int) = writeFret(fret)
        fun writeFretFromUi(fret: Int) = writeFret(fret)
        fun deleteCurrentNoteFromUi() {
            // Delete is a NOTE/BEAT operation. A BAR selection is only a fallback
            // target produced when the user taps empty space inside a measure; it
            // must never turn the Delete key/button into "Clear bar".
            when (selectionTarget) {
                SelectionTarget.BAR -> deleteCurrentNote()
                SelectionTarget.RANGE -> {
                    val a = selectionAnchorBeat ?: return
                    val b = selectionFocusBeat ?: return
                    val lo = minOf(a.absolutePlaybackStart, b.absolutePlaybackStart)
                    val hi = maxOf(a.absolutePlaybackStart, b.absolutePlaybackStart)
                    val song = score.api.score ?: return
                    pushUndoSnapshot()
                    for (track in song.tracks.toList()) {
                        for (staff in track.staves.toList()) {
                            for (bar in staff.bars.toList()) {
                                for (voice in bar.voices.toList()) {
                                    for (beat in voice.beats.toList()) {
                                        val t = beat.absolutePlaybackStart
                                        if (t >= lo && t <= hi) {
                                            beat.notes.toList().forEach { beat.removeNote(it) }
                                            beat.isEmpty = true
                                            beat.finish(score.settings, null)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    song.finish(score.settings)
                    selectionAnchorBeat = null
                    selectionFocusBeat = null
                    selectionDragActive = false
                    selectionDragMoved = false
                    if (selectionTarget == SelectionTarget.RANGE) selectionTarget = SelectionTarget.BEAT
                    try { score.api.clearPlaybackRangeHighlight() } catch (_: Throwable) { }
                    renderAndLog("delete-selection")
                    onSelectionChanged?.invoke()
                }
                SelectionTarget.NOTE,
                SelectionTarget.BEAT -> deleteCurrentNote()
            }
        }

        fun selectTrackFromUi(index: Int) {
            val tracks = score.api.score?.tracks?.toList().orEmpty()
            if (index !in tracks.indices) return
            try {
                caret = Caret(index, 0, 0, 1)
                session.caret = caret
                armed = true
                pendingFret = ""
                // Track selection changes the edit target only. It must never hide
                // the other real tracks from the score view.
                renderAllTracks(score.api.score ?: return)
                score.api.render()
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

        private fun moveBeat(delta: Int) {
            val bs=bars() ?: return
            if (bs.isEmpty()) return
            var b=currentBarIndex.coerceIn(0,bs.lastIndex); var beat=currentBeatIndex
            val step=if(delta<0) -1 else 1
            repeat(kotlin.math.abs(delta)) {
                val bar=bs.getOrNull(b) ?: return@repeat
                val voice=bar.voices.toList().getOrNull(currentVoiceIndex) ?: return@repeat
                val beats=voice.beats.toList()
                if (step<0) {
                    val candidate=beat-1
                    if(candidate>=0) beat=candidate
                    else {
                        if(b==0) return@repeat
                        b--
                        val prev=bs[b].voices.toList().getOrNull(currentVoiceIndex)?.beats?.toList().orEmpty()
                        if(prev.isEmpty()) return@repeat
                        beat=prev.lastIndex
                    }
                    return@repeat
                }
                val candidate=beat+1
                if(candidate<beats.size) { beat=candidate; return@repeat }
                val used=AlphaTabRhythmEngine.barUsedTicks(bar,currentVoiceIndex)
                val capacity=AlphaTabRhythmEngine.barCapacityTicks(bar)
                if(used<capacity) {
                    if(!AlphaTabRhythmEngine.fillVoiceToBarCapacity(bar,currentVoiceIndex)) return@repeat
                    val refreshed=bar.voices.toList().getOrNull(currentVoiceIndex)?.beats?.toList().orEmpty()
                    if(candidate<refreshed.size) { beat=candidate; return@repeat }
                    return@repeat
                }
                if(b==bs.lastIndex) { if(!createNextMeasures(4)) return@repeat }
                b++; beat=0
            }
            caret=Caret(currentTrackIndex,b,beat,currentStringIndex); session.caret=caret
            val movedBeat = bs.getOrNull(b)?.voices?.toList()?.getOrNull(currentVoiceIndex)?.beats?.toList()?.getOrNull(beat)
            val hasMovedNote = movedBeat?.getNoteOnString(alphaTabString(currentStringIndex).toDouble()) != null
            selectionTarget = if (hasMovedNote) SelectionTarget.NOTE else SelectionTarget.BEAT
            tappedNoteBounds = null
            armed=true; pendingFret=""
            updateCursor()
            if(lastRawCaretX.isFinite()&&lastRawCaretY.isFinite()) ensureCaretVisible(lastRawCaretX,lastRawCaretY)
            updateStatus(); onSelectionChanged?.invoke()
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
            if (lastRawCaretX.isFinite() && lastRawCaretY.isFinite()) {
                ensureCaretVisible(lastRawCaretX, lastRawCaretY)
            }
            updateStatus()
        }

        /** Up/down changes the TAB string only; it never changes the rhythmic beat. */
        private fun moveString(delta: Int) {
            caret = caret.copy(stringIndex = (caret.stringIndex + delta).coerceIn(1, maxStringIndex()))
            session.caret = caret
            val beat = currentBeat()
            selectionTarget = if (beat?.getNoteOnString(alphaTabString(caret.stringIndex).toDouble()) != null) SelectionTarget.NOTE else SelectionTarget.BEAT
            tappedNoteBounds = null
            armed = true
            pendingFret = ""
            updateCursor()
            if (lastRawCaretX.isFinite() && lastRawCaretY.isFinite()) {
                ensureCaretVisible(lastRawCaretX, lastRawCaretY)
            }
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
                val bar = bars()?.getOrNull(caret.measureIndex)
                if (bar != null) AlphaTabRhythmEngine.prepareNextRestGrid(bar, currentVoiceIndex, beat)
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
            // NOTE selection is object-based. Never infer the note to delete from a
            // moving caret when the user explicitly selected a fret in a chord.
            val note = if (selectionTarget == SelectionTarget.NOTE) {
                selectedNote()?.takeIf { candidate -> beat.notes.toList().any { it === candidate } }
            } else {
                beat.getNoteOnString(alphaTabString(currentStringIndex).toDouble())
            }

            if (note == null) {
                // Delete in a notation editor must be non-destructive when the
                // selected string is already empty. Never move the caret as a side effect.
                updateStatus("No note on selected string")
                return
            }

            pushUndoSnapshot()
            beat.removeNote(note)
            beat.isEmpty = beat.notes.toList().isEmpty()
            beat.finish(score.settings, null)
            score.api.score?.finish(score.settings)
            selectedNoteRef = null
            tappedNoteBounds = null
            selectionTarget = SelectionTarget.BEAT
            overlay.hideNoteSelection()
            renderAndLog("delete-selected-note")
            updateCursor()
            onSelectionChanged?.invoke()
            updateStatus(
                if (beat.isEmpty) "Beat is now rest"
                else "Selected note deleted • remaining chord notes preserved"
            )
        }

        private fun hitExpectedTabY(measureIndex: Int, beatIndex: Int, stringIndex: Int): Float? {
            val hit = beatHits.firstOrNull {
                it.measure == measureIndex && it.beat == beatIndex && !it.virtual
            } ?: return null
            return hit.tabTopY + (stringIndex - 1).coerceAtLeast(0) * hit.stringSpacing
        }

        fun refreshVisualCursor() {
            try {
                val lookup=score.api.renderer.boundsLookup; val song=score.api.score
                val track=song?.tracks?.toList()?.getOrNull(currentTrackIndex); val staff=track?.staves?.firstOrNull()
                val bar=staff?.bars?.toList()?.getOrNull(caret.measureIndex)
                val beat=bar?.voices?.toList()?.getOrNull(currentVoiceIndex)?.beats?.toList()?.getOrNull(caret.beatIndex)
                val bb=beat?.let{lookup?.findBeat(it)}
                if(lookup==null){
                    if (BuildConfig.DEBUG && coordinateDebugEnabled) overlay.setDiagnosticBanner("lookup=null")
                } else if(beat==null){
                    if (BuildConfig.DEBUG && coordinateDebugEnabled) overlay.setDiagnosticBanner("beat=null")
                } else if(bb==null){
                    if (BuildConfig.DEBUG && coordinateDebugEnabled) overlay.setDiagnosticBanner("bb=null")
                } else if(beat!=null&&bb!=null){
                    val rawBar=lookup.findMasterBar(bb.beat.voice.bar.masterBar)?.bars?.toList()?.firstOrNull()?.realBounds ?: bb.barBounds.masterBarBounds.visualBounds
                    val rawX=bb.onNotesX.toFloat()

                    // Prefer the exact AlphaTab note bound for the selected string.
                    // This removes the old estimated "system top + string spacing"
                    // calculation whenever a real TAB note exists. onNotesX is the
                    // official beat-center X used by AlphaTab's cursor.
                    val selectedAlphaString = alphaTabString(caret.stringIndex).toDouble()
                    val allBeatBounds = runCatching { lookup.findBeats(beat)?.toList().orEmpty() }
                        .getOrDefault(listOf(bb))
                    val expectedTabY = hitExpectedTabY(caret.measureIndex, caret.beatIndex, caret.stringIndex)
                    val hit = beatHits.firstOrNull {
                        it.measure == caret.measureIndex && it.beat == caret.beatIndex && !it.virtual
                    }
                    val fallbackTop = hit?.tabTopY ?: (rawBar.y.toFloat() + rawBar.h.toFloat() * 0.61f)
                    val spacing = hit?.stringSpacing ?: stringSpacing
                    // Resolve the selected Note's rendered bounds from the TAB staff.
                    // A Beat can expose separate NoteBounds for standard notation and TAB;
                    // choose the matching Note whose vertical center is closest to this
                    // note's expected TAB string row, then center the selection on its glyph.
                    val selectedTabNoteBounds = if (selectionTarget == SelectionTarget.NOTE && selectedNoteRef != null) {
                        allBeatBounds.asSequence()
                            .flatMap { it.notes?.toList().orEmpty().asSequence() }
                            .filter { it.note === selectedNoteRef }
                            .minByOrNull { nb ->
                                val centerY = nb.noteHeadBounds.y.toDouble() + nb.noteHeadBounds.h.toDouble() / 2.0
                                kotlin.math.abs(centerY - (expectedTabY?.toDouble() ?: centerY))
                            }?.noteHeadBounds
                    } else null
                    val selectedNoteRect = if (selectionTarget == SelectionTarget.NOTE && selectedNoteRef != null) {
                        val tabY = expectedTabY
                            ?: (fallbackTop + (caret.stringIndex - 1).coerceAtLeast(0) * spacing)
                        val noteBounds = selectedTabNoteBounds
                        val selectionCenterX = if (noteBounds != null && noteBounds.w > 0.0)
                            (noteBounds.x + noteBounds.w / 2.0).toFloat()
                        else rawX.toFloat()
                        val halfWidth = (spacing * 0.62f).coerceIn(5f, 11f)
                        val halfHeight = (spacing * 0.46f).coerceIn(4f, 8f)
                        RectF(selectionCenterX - halfWidth, tabY - halfHeight,
                            selectionCenterX + halfWidth, tabY + halfHeight)
                    } else null
                    val rawY = if (selectedNoteRect != null) {
                        selectedNoteRect.centerY()
                    } else {
                        fallbackTop+(caret.stringIndex-1).coerceAtLeast(0)*spacing
                    }

                    lastRawCaretX = rawX
                    lastRawCaretY = rawY
                    val dm=activity.resources.displayMetrics
                    val d=dm.density.coerceAtLeast(0.01f)
                    // AlphaTab hit-testing converts touch pixels with density * display.scale.
                    // Its rendered bounds and scroll must use that exact same transform.
                    val displayScale=score.settings.display.scale.toFloat().coerceIn(0.1f,4f)
                    val coordinateScale=d*displayScale
                    val half=((spacing*.9f).coerceAtLeast(4f)/2f)*coordinateScale
                    val origin=alphaTabContentOriginInOverlay()
                    val finalX=rawX*coordinateScale+origin.first
                    val finalY=rawY*coordinateScale+origin.second
                    val guideTop=rawBar.y.toFloat()*coordinateScale+origin.second
                    val guideBottom=(rawBar.y+rawBar.h).toFloat()*coordinateScale+origin.second

                    var diagnosticNoteLeft = Float.NaN
                    var diagnosticNoteRight = Float.NaN
                    if (selectionTarget == SelectionTarget.NOTE && selectedNoteRect != null) {
                        val noteLeft = selectedNoteRect.left * coordinateScale + origin.first
                        val noteTop = selectedNoteRect.top * coordinateScale + origin.second
                        val noteRight = selectedNoteRect.right * coordinateScale + origin.first
                        val noteBottom = selectedNoteRect.bottom * coordinateScale + origin.second
                        diagnosticNoteLeft = noteLeft
                        diagnosticNoteRight = noteRight
                        overlay.showNoteSelectionContent(noteLeft, noteTop, noteRight, noteBottom)
                    } else {
                        overlay.hideNoteSelection()
                    }

                    if (BuildConfig.DEBUG && coordinateDebugEnabled) overlay.setDiagnosticRaw(rawX,rawY,d)
                    overlay.showGuitarProGuideContent(finalX,guideTop,guideBottom)
                    overlay.showBeatCaretContent(finalX,finalY,half)
                    lastCaretPosition=Triple(finalX,finalY,half)
                    lastCaretRect=RectF(finalX-half,finalY-half,finalX+half,finalY+half)

                    // One diagnostic line per visual-cursor refresh; DEBUG only.
                    // Logging does not alter cursor or selection calculations.
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "EARAM_X_DIAG",
                            "rawX=$rawX density=$d displayScale=$displayScale coordinateScale=$coordinateScale " +
                                "origin=(${origin.first},${origin.second}) finalX=$finalX " +
                                "noteLeft=$diagnosticNoteLeft noteRight=$diagnosticNoteRight " +
                                "caretCenterX=$finalX caretCenterY=$finalY"
                        )
                    }

                    if (BuildConfig.DEBUG && coordinateDebugEnabled) {
                        val scLayout=actualScrollOffsetsLayout()
                        val scPx=actualScrollOffsets()
                        overlay.setDiagnosticBanner("OK raw=("+rawX+","+rawY+") d="+d+" scale="+displayScale+" coordScale="+coordinateScale+" final=("+finalX+","+finalY+") scrollPx=("+scPx.first+","+scPx.second+") overlay="+overlay.width+"x"+overlay.height+" AlphaTabView="+score.width+"x"+score.height)
                        android.util.Log.d("EARAM_COORD","caret BAR1 raw barBounds.realBounds="+rawBar+" beatRealBounds="+bb.realBounds+" onNotesX(rawLayout)="+rawX+" selectedNoteRect="+selectedNoteRect+" tabY(rawLayout)="+rawY+" | density="+d+" displayScale="+displayScale+" coordinateScale="+coordinateScale+" | scrollLayout="+scLayout.first+","+scLayout.second+" scrollPx="+scPx.first+","+scPx.second+" | contentOriginPx="+origin.first+","+origin.second+" | caretFinalPx="+finalX+","+finalY+" halfPx="+half)
                    }
                    updateDebugOverlay()
                } else overlay.hideCursor()
            }catch(t:Throwable){
                if (BuildConfig.DEBUG && coordinateDebugEnabled) {
                    overlay.setDiagnosticBanner("EXCEPTION "+(t.message ?: t.javaClass.simpleName))
                    android.util.Log.e("EARAM_ALPHA_CURSOR","overlay caret positioning failed",t)
                }
            }
        }
        private fun updateDebugBanner(x:Double,y:Double,w:Double,h:Double,onNotesX:Double,l:Float,t:Float,r:Float,b:Float){
            if(!coordinateDebugEnabled)return
            val scroll=actualScrollOffsets();val sl=IntArray(2);val ol=IntArray(2);score.getLocationOnScreen(sl);overlay.getLocationOnScreen(ol)
            overlay.setDebugBanner("Bar1 raw x=$x y=$y w=$w h=$h\nBeat.onNotesX=$onNotesX caret=[$l,$t,$r,$b]\nAlphaTab scroll=(${scroll.first},${scroll.second})\nAlphaTabView screen=(${sl[0]},${sl[1]}) overlay screen=(${ol[0]},${ol[1]})")
        }
        fun logOfficialPlaybackCursor(playedBeat:Beat){
            try{ val b=score.api.renderer.boundsLookup?.findBeat(playedBeat)?:return; val bar=score.api.renderer.boundsLookup?.findMasterBar(b.beat.voice.bar.masterBar)?.realBounds ?: b.barBounds.masterBarBounds.visualBounds; val o=alphaTabContentOriginInOverlay(); val d=activity.resources.displayMetrics.density.coerceAtLeast(0.01f); val displayScale=score.settings.display.scale.toFloat().coerceIn(0.1f,4f); val coordinateScale=d*displayScale; val rawX=b.onNotesX.toFloat(); val x=rawX*coordinateScale+o.first; val top=bar.y.toFloat()*coordinateScale+o.second; val bottom=(bar.y+bar.h).toFloat()*coordinateScale+o.second; overlay.showPlaybackCursorContent(x,top,bottom); if (BuildConfig.DEBUG && coordinateDebugEnabled) { val scLayout=actualScrollOffsetsLayout(); val scPx=actualScrollOffsets(); android.util.Log.d("EARAM_COORD","playback BAR1 raw barBounds.realBounds="+bar+" beatRealBounds="+b.realBounds+" onNotesX(rawLayout)="+rawX+" | density="+d+" displayScale="+displayScale+" coordinateScale="+coordinateScale+" | scrollLayout="+scLayout.first+","+scLayout.second+" scrollPx="+scPx.first+","+scPx.second+" | contentOriginPx="+o.first+","+o.second+" | playbackFinalPx="+x+","+top+" playbackBottomPx="+bottom) } }catch(t:Throwable){if (BuildConfig.DEBUG && coordinateDebugEnabled) android.util.Log.e("EARAM_ALPHA_CURSOR","overlay playback cursor failed",t)}
        }
        fun showPlaybackBeat(playedBeat:Beat)=logOfficialPlaybackCursor(playedBeat)
        fun hidePlaybackCursor(){
            // Kept for command compatibility. AlphaTab owns the playback cursor.
        }
        fun invalidateCaretOverlay(reason:String){ /* custom caret removed */ }
        fun restoreCaretFromSession(){caret=session.caret;currentVoiceIndex=0;armed=true;pendingFret="";updateStatus()}
        fun rebuildBeatHitsAfterLayout(){buildBeatHits()}
        fun isDebugMode():Boolean=coordinateDebugEnabled
        fun setDebugModeFromUi(enabled:Boolean){if (!BuildConfig.DEBUG) return; coordinateDebugEnabled=enabled;buildBeatHits();updateDebugOverlay();updateStatus(if(enabled)"DEBUG ON • long-press title to disable" else "DEBUG OFF");refreshVisualCursor();logCoordinateDiagnostic("debug-toggle")}
        fun actualScrollOffsetsLayout():Pair<Float,Float>{return try{val s=score.api.uiFacade.getScrollContainer();Pair(s.scrollLeft.toFloat(),s.scrollTop.toFloat())}catch(t:Throwable){val d=activity.resources.displayMetrics.density.coerceAtLeast(0.01f);Pair(score.scrollX.toFloat()/d,score.scrollY.toFloat()/d)}}
        fun actualScrollOffsets():Pair<Float,Float>{val d=activity.resources.displayMetrics.density.coerceAtLeast(0.01f);val scale=score.settings.display.scale.toFloat().coerceIn(0.1f,4f);val factor=d*scale;val raw=actualScrollOffsetsLayout();return Pair(raw.first*factor,raw.second*factor)}
        private fun diagnosticMember(obj: Any?, name: String): Any? {
            if (obj == null) return null
            val suffix = name.substring(0, 1).uppercase() + name.substring(1)
            val getter = obj.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "get$suffix" || it.name == "is$suffix" || it.name == name)
            }
            if (getter != null) return runCatching { getter.invoke(obj) }.getOrNull()
            var type: Class<*>? = obj.javaClass
            while (type != null) {
                val currentType = type
                val field = runCatching { currentType.getDeclaredField(name) }.getOrNull()
                if (field != null) {
                    return runCatching { field.isAccessible = true; field.get(obj) }.getOrNull()
                }
                type = currentType.superclass
            }
            return null
        }

        private fun diagnosticBounds(bounds: Any?): String {
            if (bounds == null) return "null"
            return "x=${diagnosticMember(bounds, "x")}, y=${diagnosticMember(bounds, "y")}, " +
                "w=${diagnosticMember(bounds, "w")}, h=${diagnosticMember(bounds, "h")}"
        }

        private fun diagnosticItems(obj: Any?): List<Any?> {
            if (obj == null) return emptyList()
            if (obj is Iterable<*>) return obj.toList()
            if (obj is Array<*>) return obj.toList()
            val toListMethod = obj.javaClass.methods.firstOrNull { it.name == "toList" && it.parameterCount == 0 }
            val converted = if (toListMethod != null) runCatching { toListMethod.invoke(obj) }.getOrNull() else null
            if (converted is Iterable<*>) return converted.toList()
            if (converted is Array<*>) return converted.toList()
            val size = ((diagnosticMember(obj, "size") ?: diagnosticMember(obj, "length")) as? Number)?.toInt() ?: 0
            val getMethod = obj.javaClass.methods.firstOrNull { it.name == "get" && it.parameterCount == 1 }
            if (getMethod != null && size in 1..10000) {
                return (0 until size).map { index -> runCatching { getMethod.invoke(obj, index) }.getOrNull() }
            }
            return emptyList()
        }

        fun showBoundsDiagnosticDialog() {
            if (!BuildConfig.DEBUG) return
            val report = StringBuilder()
            try {
                val lookup = score.api.renderer.boundsLookup
                val song = score.api.score
                val track = song?.tracks?.toList()?.getOrNull(currentTrackIndex)
                val staff = track?.staves?.firstOrNull()
                val bar = staff?.bars?.toList()?.getOrNull(caret.measureIndex)
                val beat = bar?.voices?.toList()?.getOrNull(currentVoiceIndex)?.beats?.toList()?.getOrNull(caret.beatIndex)
                val single = if (lookup != null && beat != null) lookup.findBeat(beat) else null
                val many = if (lookup != null && beat != null) runCatching { lookup.findBeats(beat)?.toList().orEmpty() }.getOrDefault(emptyList()) else emptyList()
                val dm = activity.resources.displayMetrics
                val density = dm.density.coerceAtLeast(0.01f)
                val displayScale = score.settings.display.scale.toFloat().coerceIn(0.1f, 4f)
                val coordinateScale = density * displayScale
                val origin = alphaTabContentOriginInOverlay()
                val scroll = actualScrollOffsets()
                val scrollLayout = actualScrollOffsetsLayout()
                val last = lastCaretPosition
                val overlayLocation = IntArray(2)
                val scoreLocation = IntArray(2)
                overlay.getLocationOnScreen(overlayLocation)
                score.getLocationOnScreen(scoreLocation)
                val scoreXInOverlay = scoreLocation[0] - overlayLocation[0]
                val scoreYInOverlay = scoreLocation[1] - overlayLocation[1]
                val selectedCandidateNotes = single?.let { diagnosticItems(diagnosticMember(it, "notes")) }.orEmpty()
                val selectedNoteBoundsEntry = selectedCandidateNotes.firstOrNull { entry ->
                    diagnosticMember(entry, "note") === selectedNoteRef
                } ?: selectedCandidateNotes.firstOrNull { entry ->
                    val note = diagnosticMember(entry, "note")
                    diagnosticMember(note, "string")?.toString() == alphaTabString(caret.stringIndex).toString()
                }
                val selectedNoteObject = selectedNoteBoundsEntry?.let { diagnosticMember(it, "note") }
                val selectedNoteHead = selectedNoteBoundsEntry?.let { diagnosticMember(it, "noteHeadBounds") }
                val selectedFret = selectedNoteObject?.let { diagnosticMember(it, "fret") }
                val selectedHeadX = selectedNoteHead?.let { diagnosticMember(it, "x") }
                val selectedHeadW = selectedNoteHead?.let { diagnosticMember(it, "w") }
                report.appendLine("EARAM ALPHATAB BOUNDS DIAGNOSTIC")
                report.appendLine("DEBUG only; drawing logic was not changed by this diagnostic.")
                report.appendLine("versionName=${BuildConfig.VERSION_NAME}; versionCode=${BuildConfig.VERSION_CODE}; currentTrackIndex=$currentTrackIndex, measureIndex=${caret.measureIndex}, beatIndex=${caret.beatIndex}, voiceIndex=$currentVoiceIndex, stringIndex=${caret.stringIndex}")
                report.appendLine("screenPx=(width=${dm.widthPixels}, height=${dm.heightPixels}); density=$density")
                report.appendLine("overlayPx=(width=${overlay.width}, height=${overlay.height}, screenX=${overlayLocation[0]}, screenY=${overlayLocation[1]})")
                report.appendLine("alphaTabViewPx=(width=${score.width}, height=${score.height}, screenX=${scoreLocation[0]}, screenY=${scoreLocation[1]}, insideOverlayX=$scoreXInOverlay, insideOverlayY=$scoreYInOverlay)")
                report.appendLine("displayScale=$displayScale; coordinateScale=$coordinateScale; originOverlayPx=(${origin.first}, ${origin.second})")
                report.appendLine("scrollLayout=(${scrollLayout.first}, ${scrollLayout.second}); scrollPx=(${scroll.first}, ${scroll.second}); score.scroll=(x=${score.scrollX}, y=${score.scrollY})")
                report.appendLine("sameBeatX: rawX/onNotesX=${single?.let { diagnosticMember(it, "onNotesX") }}; beatRealBounds={${diagnosticBounds(diagnosticMember(single, "realBounds"))}}")
                report.appendLine("selected fret note: selectionTarget=$selectionTarget; stringIndex=${caret.stringIndex}; alphaTabString=${alphaTabString(caret.stringIndex)}; fret=$selectedFret; noteHeadBounds.x=$selectedHeadX; noteHeadBounds.w=$selectedHeadW; selectedNoteRefMatched=${selectedNoteObject === selectedNoteRef}")
                report.appendLine("lastRawCaret=(x=$lastRawCaretX, y=$lastRawCaretY)")
                report.appendLine("lastFinalCaret=(x=${last?.first}, y=${last?.second}, half=${last?.third}); caretCenterX=${last?.first}; caretCenterY=${last?.second}; lastCaretRect=$lastCaretRect")
                report.appendLine("drawn orange selection: ${overlay.diagnosticNoteSelectionBounds()}")
                report.appendLine("origin math: finalX = rawX * density * displayScale + originX; finalY = rawY * density * displayScale + originY")
                report.appendLine("lookupPresent=${lookup != null}; selectedBeatPresent=${beat != null}; findBeatCount=${if (single == null) 0 else 1}; findBeatsCount=${many.size}")

                if (track != null) {
                    report.appendLine("selected track=$currentTrackIndex; trackStaffCount=${track.staves.toList().size}")
                    track.staves.toList().forEachIndexed { si, st ->
                        report.appendLine("track[$currentTrackIndex].staff[$si]: showStandardNotation=${st.showStandardNotation}, showTablature=${st.showTablature}, bars=${st.bars.toList().size}")
                    }
                } else report.appendLine("selected track/staff: null")
                if (bar != null) {
                    report.appendLine("selected model bar: masterBarIndex=${song?.masterBars?.toList()?.indexOfFirst { it === bar.masterBar }}, barVoiceCount=${bar.voices.toList().size}")
                } else report.appendLine("selected model bar: null")
                if (beat != null) report.appendLine("selected model beat: notes=${beat.notes.toList().size}, isEmpty=${beat.isEmpty}") else report.appendLine("selected model beat: null")

                fun locateBar(barObject: Any?): String {
                    if (barObject == null || song == null) return "unresolved"
                    val found = mutableListOf<String>()
                    song.tracks.toList().forEachIndexed { ti, tr ->
                        tr.staves.toList().forEachIndexed { si, st ->
                            st.bars.toList().forEachIndexed { bi, candidate ->
                                if (candidate === barObject) found.add("track=$ti staff=$si bar=$bi(tab=${st.showTablature},score=${st.showStandardNotation})")
                            }
                        }
                    }
                    return found.joinToString("; ").ifEmpty { "no model identity match" }
                }

                fun dumpCandidate(label: String, candidate: Any?) {
                    if (candidate == null) {
                        report.appendLine("$label: null")
                        return
                    }
                    val barBounds = diagnosticMember(candidate, "barBounds")
                    val barObject = diagnosticMember(barBounds, "bar")
                    report.appendLine("$label: visualBounds={${diagnosticBounds(diagnosticMember(candidate, "visualBounds"))}}")
                    report.appendLine("$label: realBounds={${diagnosticBounds(diagnosticMember(candidate, "realBounds"))}}")
                    report.appendLine("$label: onNotesX=${diagnosticMember(candidate, "onNotesX")}")
                    report.appendLine("$label: barBounds.visual={${diagnosticBounds(diagnosticMember(barBounds, "visualBounds"))}}")
                    report.appendLine("$label: barBounds.real={${diagnosticBounds(diagnosticMember(barBounds, "realBounds"))}}; barMatch=${locateBar(barObject)}")
                    report.appendLine("$label: candidate.staffBounds={${diagnosticBounds(diagnosticMember(candidate, "staffBounds"))}}; barBounds.staffBounds={${diagnosticBounds(diagnosticMember(barBounds, "staffBounds"))}}; staffSystemBounds=${diagnosticMember(barBounds, "staffSystemBounds")}")
                    val masterBounds = diagnosticMember(barBounds, "masterBarBounds")
                    report.appendLine("$label: masterBarBounds.visual={${diagnosticBounds(diagnosticMember(masterBounds, "visualBounds"))}}; real={${diagnosticBounds(diagnosticMember(masterBounds, "realBounds"))}}")
                    val notes = diagnosticItems(diagnosticMember(candidate, "notes"))
                    if (notes.isEmpty()) {
                        report.appendLine("$label.notes: empty/null/unreadable")
                    } else {
                        notes.forEachIndexed { ni, nb ->
                            val note = diagnosticMember(nb, "note")
                            report.appendLine("$label.note[$ni]: noteHeadBounds={${diagnosticBounds(diagnosticMember(nb, "noteHeadBounds"))}}; string=${diagnosticMember(note, "string")}; fret=${diagnosticMember(note, "fret")}; noteId=${diagnosticMember(note, "id")}")
                        }
                    }
                }

                dumpCandidate("findBeat", single)
                many.forEachIndexed { i, candidate -> dumpCandidate("findBeats[$i]", candidate) }

                val masterBar = bar?.masterBar
                val masterBounds = if (lookup != null && masterBar != null) lookup.findMasterBar(masterBar) else null
                report.appendLine("masterBarBounds: visual={${diagnosticBounds(diagnosticMember(masterBounds, "visualBounds"))}}; real={${diagnosticBounds(diagnosticMember(masterBounds, "realBounds"))}}; staffSystem={${diagnosticMember(masterBounds, "staffSystemBounds")}}")
                val barCandidates = diagnosticItems(diagnosticMember(masterBounds, "bars"))
                if (barCandidates.isNotEmpty()) {
                    barCandidates.forEachIndexed { i, candidate ->
                        val barObject = diagnosticMember(candidate, "bar")
                        report.appendLine("masterBar.bars[$i]: visual={${diagnosticBounds(diagnosticMember(candidate, "visualBounds"))}}; real={${diagnosticBounds(diagnosticMember(candidate, "realBounds"))}}; staffBounds={${diagnosticBounds(diagnosticMember(candidate, "staffBounds"))}}; modelMatch=${locateBar(barObject)}")
                    }
                } else report.appendLine("masterBar.bars: unavailable/empty")

                report.appendLine("END DIAGNOSTIC")
            } catch (t: Throwable) {
                report.appendLine("DIAGNOSTIC ERROR: ${t.javaClass.name}: ${t.message}")
                report.appendLine(android.util.Log.getStackTraceString(t))
            }

            val textView = TextView(activity).apply {
                text = report.toString()
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(activity.dp(12f), activity.dp(8f), activity.dp(12f), activity.dp(8f))
            }
            val copyButton = Button(activity).apply { text = "نسخ" }
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(android.widget.ScrollView(activity).apply {
                    addView(textView)
                    layoutParams = LinearLayout.LayoutParams(-1, activity.dp(380f))
                })
                addView(copyButton, LinearLayout.LayoutParams(-1, -2))
                setPadding(activity.dp(8f), activity.dp(4f), activity.dp(8f), activity.dp(4f))
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle("Bounds Diagnostic • Debug")
                .setView(panel)
                .setNegativeButton("إغلاق", null)
                .create()
            dialog.show()
            copyButton.setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Earam bounds diagnostic", report.toString()))
                Toast.makeText(activity, "تم نسخ نتيجة التشخيص", Toast.LENGTH_SHORT).show()
            }
        }

        fun logCoordinateDiagnostic(reason:String){if (!BuildConfig.DEBUG || !coordinateDebugEnabled) return; try{val s=score.api.uiFacade.getScrollContainer();val sl=IntArray(2);val ol=IntArray(2);score.getLocationOnScreen(sl);overlay.getLocationOnScreen(ol);android.util.Log.d("EARAM_SCROLL","reason="+reason+" scroller="+s.javaClass.name+" actualScroll="+s.scrollLeft+","+s.scrollTop+" scoreScroll="+score.scrollX+","+score.scrollY+" scoreLoc="+sl[0]+","+sl[1]+" overlayLoc="+ol[0]+","+ol[1]);val lookup=score.api.renderer.boundsLookup?:return;val song=score.api.score?:return;val bar=song.tracks.toList().getOrNull(currentTrackIndex)?.staves?.firstOrNull()?.bars?.toList()?.getOrNull(caret.measureIndex);val bb=bar?.voices?.toList()?.getOrNull(currentVoiceIndex)?.beats?.toList()?.getOrNull(caret.beatIndex)?.let{lookup.findBeat(it)};val safeBarRect=bar?.let{lookup.findMasterBar(it.masterBar)?.realBounds}; android.util.Log.d("EARAM_SCROLL","raw barRect="+safeBarRect+" beatRect="+bb?.realBounds+" onNotesX="+bb?.onNotesX+" nativeCaretRect="+lastCaretRect+" parent=AlphaTab.selectionWrapper")}catch(t:Throwable){android.util.Log.e("EARAM_SCROLL","coordinate diagnostic failed",t)}}
        private fun updateDebugOverlay(){val lookup=score.api.renderer.boundsLookup?:return;val song=score.api.score?:return;val staff=song.tracks.toList().getOrNull(currentTrackIndex)?.staves?.firstOrNull()?:return;val bars=staff.bars.toList().mapNotNull{bar->lookup.findMasterBar(bar.masterBar)?.realBounds?.let{RectF(it.x.toFloat(),it.y.toFloat(),(it.x+it.w).toFloat(),(it.y+it.h).toFloat())}};val sc=actualScrollOffsets();overlay.setDebugData(coordinateDebugEnabled,bars,"bar="+(caret.measureIndex+1)+" beat="+(caret.beatIndex+1)+" string="+caret.stringIndex+" cx="+(lastCaretPosition?.first?:-1f)+" cy="+(lastCaretPosition?.second?:-1f)+" scrollY="+sc.second);lastCaretRect?.let{r->val bb=currentBeat()?.let{score.api.renderer.boundsLookup?.findBeat(it)};val br=bb?.let{lookup.findMasterBar(it.beat.voice.bar.masterBar)?.realBounds};if(bb!=null&&br!=null)updateDebugBanner(br.x.toDouble(),br.y.toDouble(),br.w.toDouble(),br.h.toDouble(),bb.onNotesX.toDouble(),r.left,r.top,r.right,r.bottom)}}
        private fun ensureCaretVisible(contentX:Float,contentY:Float){try{val s=score.api.uiFacade.getScrollContainer();val mx=(s.width*.12).coerceAtLeast(24.0);val my=(s.height*.10).coerceAtLeast(24.0);var x=s.scrollLeft;var y=s.scrollTop;if(contentX-x<mx)x=(contentX-mx).coerceAtLeast(0.0)else if(contentX-x>s.width-mx)x=(contentX-s.width+mx).coerceAtLeast(0.0);if(contentY-y<my)y=(contentY-my).coerceAtLeast(0.0)else if(contentY-y>s.height-my)y=(contentY-s.height+my).coerceAtLeast(0.0);s.scrollLeft=x;s.scrollTop=y}catch(t:Throwable){android.util.Log.e("EARAM_SCROLL","official scroll failed",t)}}
        private fun updateCursor(){ refreshVisualCursor() }

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
            if (!ensureBarEditable(index)) return

            val beats = bs[index].voices.toList().getOrNull(currentVoiceIndex)?.beats?.toList().orEmpty()
            val selectedBeat = beats.firstOrNull()

            // A BAR target is a real measure target, not just the current beat index.
            // Always anchor it to beat 1 so every bar command resolves deterministically
            // against this exact measure.
            selectionTarget = SelectionTarget.BAR
            caret = caret.copy(measureIndex = index, beatIndex = 0)
            session.caret = caret
            armed = true
            pendingFret = ""

            // Selecting a measure also seeks playback to the beginning of that measure.
            if (awaitingPasteDestination && clipboardKind == ClipboardKind.RANGE) {
                pasteDestinationCaret = caret
                awaitingPasteDestination = false
                android.util.Log.d("EARAM_PASTE", "DESTINATION BAR SELECT bar=" + (index + 1) + " beat=1")
            }

            if (selectedBeat != null) {
                try {
                    score.api.stop()
                    score.api.tickPosition = selectedBeat.absolutePlaybackStart
                    session.tickPosition = score.api.tickPosition
                } catch (_: Throwable) { }
            }

            highlightSelectedBar()
            updateCursor()
            updateStatus("TAB BAR SELECTED • BAR " + (selectedBarIndex + 1) + " • BAR ACTIONS READY")
            onSelectionChanged?.invoke()
        }

        private fun highlightSelectedBar() {
            try {
                val bar = bars()?.getOrNull(selectedBarIndex) ?: return
                val beats = bar.voices.toList().getOrNull(currentVoiceIndex)?.beats?.toList().orEmpty()
                val first = beats.firstOrNull() ?: return
                val last = beats.lastOrNull() ?: first
                // Official AlphaTab selection API: highlight the real beat range that spans
                // the whole selected measure. No coordinate overlay is involved.
                score.api.highlightPlaybackRange(first, last)
                android.util.Log.d(
                    "EARAM_BAR_SELECTION",
                    "BAR selected index=" + selectedBarIndex +
                        " voice=" + currentVoiceIndex +
                        " beats=" + beats.size +
                        " first=" + first.absolutePlaybackStart +
                        " last=" + last.absolutePlaybackStart
                )
            } catch (t: Throwable) {
                android.util.Log.e("EARAM_BAR_SELECTION", "bar highlight failed", t)
            }
        }

        fun playCurrentBeatFromUi() {
            val beat = requireTabSelection() ?: return
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