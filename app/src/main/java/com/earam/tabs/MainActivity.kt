package com.earam.tabs

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.earam.tabs.music.PickingEngine
import com.earam.tabs.music.PickingMode
import com.earam.tabs.music.StrumPattern
import com.earam.tabs.music.StrokeDirection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

data class EditorState(
    val notes: Array<out Map<Int, String>>,
    val strokes: Map<Int, StrokeDirection>,
    val durations: Map<Int, Long>
)

class MainActivity : Activity() {
    private var projectName = "UNTITLED"
    private var instrument = "Guitar"
    private var stringCount = 6
    private var tuning = "Standard"
    private var bpm = 120
    private var timeSig = "4/4"
    private var keySig = "C"
    private var notation = "BOTH"
    private var selectedFret = 0
    private val chords = mutableMapOf<Int, String>()

    private var cells = Array(stringCount) { mutableMapOf<Int, String>() }
    private var strokes = mutableMapOf<Int, StrokeDirection>()
    private var durations = mutableMapOf<Int, Long>()
    private var editor: EditorView? = null
    private val updateManager by lazy { UpdateManager(this) }

    @Volatile private var playing = false
    private var playThread: Thread? = null
    @Volatile private var track: AudioTrack? = null
    private val sampleRate = 44100
    private val guitarEngine = GuitarSoundEngine(sampleRate)
    private val columnCount = 16

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun showHome() {
        stopPlayback()
        setContentView(HomeView())
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun newProject() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28f), dp(8f), dp(28f), 0)
        }
        val name = EditText(this).apply { hint = "Project name" }
        val ins = spinner(arrayOf("Electric Guitar", "Clean Electric Guitar", "Acoustic Guitar", "Classical Guitar", "12-String Guitar", "7-String Guitar", "8-String Guitar", "Baritone Guitar", "Bass", "5-String Bass", "6-String Bass", "Fretless Bass", "Piano", "Electric Piano", "Organ", "Synth Lead", "Synth Pad", "Violin", "Viola", "Cello", "Double Bass", "Flute", "Clarinet", "Oboe", "Saxophone", "Trumpet", "Trombone", "Harmonica", "Banjo", "Mandolin", "Ukulele", "Harp", "Drums"))
        val strings = spinner((3..10).map(Int::toString).toTypedArray(), 3)
        val tune = spinner(arrayOf("Standard", "Drop D", "Drop C", "Custom"))
        val sig = spinner(arrayOf("4/4", "3/4", "6/8", "5/4", "7/8"))
        val key = spinner(arrayOf("C", "G", "D", "A", "E", "F", "Am", "Em"))
        val tempo = EditText(this).apply {
            hint = "BPM"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("120")
        }
        listOf(
            TextView(this).apply { text = "Instrument" }, ins,
            TextView(this).apply { text = "Strings" }, strings,
            TextView(this).apply { text = "Tuning" }, tune,
            TextView(this).apply { text = "Time Signature" }, sig,
            TextView(this).apply { text = "Key" }, key,
            tempo
        ).forEach(box::addView)

        box.setBackgroundColor(0xFF171A1D.toInt())
        AlertDialog.Builder(this)
            .setTitle("NEW EARAM PROJECT")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                projectName = name.text.toString().trim().ifBlank { "UNTITLED" }
                instrument = ins.selectedItem.toString()
                stringCount = strings.selectedItem.toString().toInt()
                tuning = tune.selectedItem.toString()
                timeSig = sig.selectedItem.toString()
                keySig = key.selectedItem.toString()
                bpm = tempo.text.toString().toIntOrNull()?.coerceIn(30, 300) ?: 120
                cells = Array(stringCount) { mutableMapOf() }
                strokes = mutableMapOf()
                durations = mutableMapOf()
                openEditor()
            }
            .show()
    }

    private fun spinner(items: Array<String>, selected: Int = 0): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected)
    }

    private fun safeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "UNTITLED" }

    private fun saveProject() {
        try {
            val root = JSONObject()
                .put("version", 5)
                .put("name", projectName)
                .put("instrument", instrument)
                .put("strings", stringCount)
                .put("tuning", tuning)
                .put("bpm", bpm)
                .put("timeSignature", timeSig)
                .put("key", keySig)
                .put("notation", notation)

            val notes = JSONArray()
            cells.forEach { map ->
                val obj = JSONObject()
                map.forEach { (k, v) -> obj.put(k.toString(), v) }
                notes.put(obj)
            }
            root.put("notes", notes)

            val strokeJson = JSONObject()
            strokes.forEach { (k, v) -> strokeJson.put(k.toString(), if (v == StrokeDirection.UP) "UP" else "DOWN") }
            root.put("strokes", strokeJson)

            val durationJson = JSONObject()
            durations.forEach { (k, v) -> durationJson.put(k.toString(), v) }
            root.put("durations", durationJson)

            File(filesDir, "${safeFileName(projectName)}.earam").writeText(root.toString())
            Toast.makeText(this, "Saved: $projectName", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openProject() {
        val files = filesDir.listFiles()?.filter { it.extension == "earam" } ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, "No Earam projects found", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Open Earam Project")
            .setItems(files.map { it.nameWithoutExtension }.toTypedArray()) { _, index -> loadProject(files[index]) }
            .show()
    }

    private fun loadProject(file: File) {
        try {
            val root = JSONObject(file.readText())
            projectName = root.optString("name", "UNTITLED")
            instrument = root.optString("instrument", "Guitar")
            stringCount = root.optInt("strings", 6).coerceIn(3, 10)
            tuning = root.optString("tuning", "Standard")
            bpm = root.optInt("bpm", 120).coerceIn(30, 300)
            timeSig = root.optString("timeSignature", "4/4")
            keySig = root.optString("key", "C")
            notation = root.optString("notation", "BOTH")
            cells = Array(stringCount) { mutableMapOf() }
            root.optJSONArray("notes")?.let { array ->
                for (stringIndex in 0 until stringCount) {
                    array.optJSONObject(stringIndex)?.let { obj ->
                        obj.keys().forEach { key -> cells[stringIndex][key.toInt()] = obj.getString(key) }
                    }
                }
            }
            strokes = mutableMapOf()
            root.optJSONObject("strokes")?.let { obj ->
                obj.keys().forEach { key ->
                    strokes[key.toInt()] = if (obj.getString(key) == "UP") StrokeDirection.UP else StrokeDirection.DOWN
                }
            }
            durations = mutableMapOf()
            chords.clear()
            root.optJSONObject("chords")?.let { obj -> obj.keys().forEach { key -> chords[key.toInt()] = obj.getString(key) } }
            root.optJSONObject("durations")?.let { obj ->
                obj.keys().forEach { key -> durations[key.toInt()] = obj.getLong(key) }
            }
            openEditor()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open project", Toast.LENGTH_SHORT).show()
        }
    }

    private fun midi(stringIndex: Int, fret: Int): Int {
        val open = when (stringCount) {
            10 -> intArrayOf(79, 74, 69, 64, 59, 55, 50, 45, 40, 35)
            9 -> intArrayOf(74, 69, 64, 59, 55, 50, 45, 40, 35)
            8 -> intArrayOf(69, 64, 59, 55, 50, 45, 40, 35)
            7 -> intArrayOf(64, 59, 55, 50, 45, 40, 35)
            6 -> intArrayOf(64, 59, 55, 50, 45, 40)
            5 -> intArrayOf(67, 62, 57, 52, 47)
            4 -> intArrayOf(43, 38, 33, 28)
            else -> IntArray(stringCount) { 55 - it * 5 }
        }
        var value = open[stringIndex.coerceIn(0, open.lastIndex)] + fret
        if (tuning == "Drop D" && stringIndex == stringCount - 1) value -= 2
        if (tuning == "Drop C" && stringIndex == stringCount - 1) value -= 4
        return value
    }

    private fun columnSeconds(column: Int): Double {
        val ticks = durations[column] ?: 960L
        return ticks.toDouble() / 960.0 * 60.0 / bpm.toDouble()
    }

    private fun cycleSeconds(): Double = (0 until columnCount).sumOf { columnSeconds(it) }

    /** The only source used by the UI for playback position: the audio engine's consumed-frame clock. */
    private fun audioPositionSeconds(): Double? {
        val audio = track ?: return null
        if (!playing) return null
        val frames = audio.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return frames.toDouble() / sampleRate.toDouble()
    }

    /** Maps the AudioTrack clock directly to the current TAB position. No Handler/timer clock is used. */
    private fun audioCursorPosition(): Pair<Int, Double>? {
        val raw = audioPositionSeconds() ?: return null
        val total = cycleSeconds()
        if (total <= 0.0) return null
        val position = if (editor?.loop == true) raw % total else raw.coerceAtMost(total)
        var elapsed = 0.0
        for (column in 0 until columnCount) {
            val duration = columnSeconds(column)
            if (position < elapsed + duration || column == columnCount - 1) {
                val fraction = ((position - elapsed) / duration).coerceIn(0.0, 0.999999)
                return column to fraction
            }
            elapsed += duration
        }
        return null
    }

    private fun playColumn(column: Int, seconds: Double) {
        val notes = mutableListOf<Int>()
        cells.forEachIndexed { stringIndex, map ->
            map[column]?.toIntOrNull()?.let { notes.add(midi(stringIndex, it)) }
        }

        val audio = track ?: return
        val direction = strokes[column] == StrokeDirection.UP
        if (notes.isNotEmpty()) {
            val voice = when (instrument) {
                "Electric Guitar" -> GuitarSoundEngine.Voice.ELECTRIC_GUITAR
                "Clean Electric Guitar" -> GuitarSoundEngine.Voice.CLEAN_ELECTRIC_GUITAR
                "Acoustic Guitar" -> GuitarSoundEngine.Voice.ACOUSTIC_GUITAR
                "Classical Guitar" -> GuitarSoundEngine.Voice.CLASSICAL_GUITAR
                "12-String Guitar" -> GuitarSoundEngine.Voice.TWELVE_STRING_GUITAR
                "7-String Guitar" -> GuitarSoundEngine.Voice.SEVEN_STRING_GUITAR
                "8-String Guitar" -> GuitarSoundEngine.Voice.EIGHT_STRING_GUITAR
                "Baritone Guitar" -> GuitarSoundEngine.Voice.BARITONE_GUITAR
                "Bass" -> GuitarSoundEngine.Voice.BASS
                "5-String Bass" -> GuitarSoundEngine.Voice.FIVE_STRING_BASS
                "6-String Bass" -> GuitarSoundEngine.Voice.SIX_STRING_BASS
                "Fretless Bass" -> GuitarSoundEngine.Voice.FRETLESS_BASS
                "Piano" -> GuitarSoundEngine.Voice.PIANO
                "Electric Piano" -> GuitarSoundEngine.Voice.ELECTRIC_PIANO
                "Organ" -> GuitarSoundEngine.Voice.ORGAN
                "Synth Lead" -> GuitarSoundEngine.Voice.SYNTH_LEAD
                "Synth Pad" -> GuitarSoundEngine.Voice.SYNTH_PAD
                "Violin" -> GuitarSoundEngine.Voice.VIOLIN
                "Viola" -> GuitarSoundEngine.Voice.VIOLA
                "Cello" -> GuitarSoundEngine.Voice.CELLO
                "Double Bass" -> GuitarSoundEngine.Voice.DOUBLE_BASS
                "Flute" -> GuitarSoundEngine.Voice.FLUTE
                "Clarinet" -> GuitarSoundEngine.Voice.CLARINET
                "Oboe" -> GuitarSoundEngine.Voice.OBOE
                "Saxophone" -> GuitarSoundEngine.Voice.SAXOPHONE
                "Trumpet" -> GuitarSoundEngine.Voice.TRUMPET
                "Trombone" -> GuitarSoundEngine.Voice.TROMBONE
                "Harmonica" -> GuitarSoundEngine.Voice.HARMONICA
                "Banjo" -> GuitarSoundEngine.Voice.BANJO
                "Mandolin" -> GuitarSoundEngine.Voice.MANDOLIN
                "Ukulele" -> GuitarSoundEngine.Voice.UKULELE
                "Harp" -> GuitarSoundEngine.Voice.HARP
                "Drums" -> GuitarSoundEngine.Voice.DRUMS
                else -> GuitarSoundEngine.Voice.ELECTRIC_GUITAR
            }
            val data = guitarEngine.render(
                midiNotes = notes,
                durationSeconds = seconds,
                voice = voice,
                velocity = 0.92f,
                upstroke = direction
            )
            var offset = 0
            while (offset < data.size && playing) {
                val written = audio.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) throw IllegalStateException("AudioTrack write failed")
                offset += written
            }
        } else {
            // Keep an intentionally short metronomic cue for an empty column.
            val count = (sampleRate * seconds).toInt().coerceAtLeast(1)
            val data = ShortArray(count)
            val clickLength = (sampleRate * 0.035).toInt()
            for (i in 0 until minOf(clickLength, data.size)) {
                val envelope = 1.0 - i.toDouble() / clickLength.toDouble()
                data[i] = (sin(2.0 * PI * 1200.0 * i / sampleRate) * envelope * 12000.0)
                    .toInt().coerceIn(-32767, 32767).toShort()
            }
            var offset = 0
            while (offset < data.size && playing) {
                val written = audio.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) throw IllegalStateException("AudioTrack write failed")
                offset += written
            }
        }
    }

    private fun createPlaybackTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "Audio output is not supported" }
        val bufferSize = maxOf(minBuffer, sampleRate / 2)
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.setVolume(1.0f) }
    }

    private fun startPlayback() {
        if (playing) return
        playing = true
        editor?.invalidate()
        playThread = Thread {
            var localTrack: AudioTrack? = null
            try {
                localTrack = createPlaybackTrack()
                track = localTrack
                localTrack.play()
                do {
                    for (column in 0 until columnCount) {
                        if (!playing) break
                        playColumn(column, columnSeconds(column))
                    }
                } while (playing && editor?.loop == true)
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Audio playback failed", Toast.LENGTH_LONG).show()
                }
            } finally {
                try { localTrack?.pause() } catch (_: Exception) { }
                try { localTrack?.flush() } catch (_: Exception) { }
                try { localTrack?.stop() } catch (_: Exception) { }
                try { localTrack?.release() } catch (_: Exception) { }
                if (track === localTrack) track = null
                playing = false
                runOnUiThread { editor?.invalidate() }
            }
        }.also { it.start() }
    }

    private fun stopPlayback() {
        playing = false
        playThread?.interrupt()
        playThread = null
        try { track?.stop() } catch (_: Exception) { }
        track?.release()
        track = null
        editor?.invalidate()
    }

    private fun showFretKeypad() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(8f), dp(16f), dp(8f)) }
        val rows = listOf((0..12).toList(), (13..24).toList() + listOf(-1))
        rows.forEach { rowValues ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowValues.forEach { value ->
                val b = Button(this).apply { text = if (value < 0) "⌫" else value.toString() }
                b.setOnClickListener {
                    if (value < 0) selectedFret = (selectedFret / 10).coerceAtLeast(0) else selectedFret = value
                    editor?.setSelectedFret(selectedFret)
                }
                row.addView(b, LinearLayout.LayoutParams(0, dp(48f), 1f))
            }
            box.addView(row)
        }
        AlertDialog.Builder(this).setTitle("TAB NUMBER").setView(box).setNegativeButton("Close", null).show()
    }
    private fun showChordDialog() {
        val input = EditText(this).apply { hint = "Chord (e.g. Am7)" }
        AlertDialog.Builder(this).setTitle("Chord").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Apply") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) { chords[editor?.selectedColumn() ?: 0] = value; editor?.invalidate() }
            }.show()
    }
    private fun importGuitarPro() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/zip"))
        }
        startActivityForResult(intent, 4107)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 4107 || resultCode != RESULT_OK) return
        val uri=data?.data ?: return
        val name=uri.lastPathSegment?.substringAfterLast('/') ?: "IMPORT"
        val ext=name.substringAfterLast('.', "").lowercase()
        if (ext == "gpx") { Toast.makeText(this, "GPX selected — parser hook is ready; full GP5/GPX conversion is next.", Toast.LENGTH_LONG).show() }
        else Toast.makeText(this, "Guitar Pro file selected: .$ext", Toast.LENGTH_SHORT).show()
    }
    private fun openEditor() {
        editor = EditorView()
        setContentView(editor)
    }

    private inner class HomeView : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            canvas.save()
            canvas.scale(d, d)
            val logicalWidth = width / d
            val logicalHeight = height / d
            canvas.drawColor(0xFF0C0E10.toInt())
            paint.color = 0xFFF1F2F3.toInt()
            paint.textSize = 40f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = 0xFFE66A2E.toInt()
            paint.textSize = 13f
            paint.color = 0xFFF1F2F3.toInt(); canvas.drawText("Ea", 34f, 34f, paint); paint.color = 0xFFE66A2E.toInt(); canvas.drawText("r", 48f, 34f, paint); paint.color = 0xFFF1F2F3.toInt(); canvas.drawText("am", 56f, 34f, paint)
            paint.color = 0xFFF1F2F3.toInt()
            paint.textSize = 40f
            canvas.drawText("Music workspace", 34f, 78f, paint)
            paint.color = 0xFF8F969B.toInt()
            paint.textSize = 13f
            paint.typeface = Typeface.DEFAULT
            canvas.drawText("PROFESSIONAL TAB • STANDARD NOTATION • PLAYBACK", 36f, 102f, paint)
            button(canvas, "NEW PROJECT", 36f, 140f, logicalWidth - 72f, 58f)
            button(canvas, "OPEN PROJECT", 36f, 214f, logicalWidth - 72f, 58f)
            button(canvas, "CHECK FOR UPDATES", 36f, 288f, logicalWidth - 72f, 58f)
            paint.color = 0xFF24292D.toInt()
            canvas.drawRoundRect(36f, 376f, logicalWidth - 36f, 377f, 1f, 1f, paint)
            paint.color = 0xFF747C82.toInt(); paint.textSize = 11f
            canvas.drawText("EARAM  •  PROFESSIONAL MUSIC WORKSPACE", 36f, 404f, paint)
            canvas.restore()
        }

        private fun button(canvas: Canvas, text: String, x: Float, y: Float, w: Float, h: Float) {
            paint.color = 0xFF202529.toInt()
            canvas.drawRoundRect(x, y, x + w, y + h, 10f, 10f, paint)
            paint.color = 0xFFF0F1F2.toInt()
            paint.textSize = 15f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(text, x + 18f, y + 36f, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                val d = resources.displayMetrics.density
                val y = event.y / d
                when {
                    y in 130f..205f -> newProject()
                    y in 205f..280f -> openProject()
                    y in 280f..365f -> updateManager.checkForUpdates()
                }
            }
            return true
        }
    }

    private inner class EditorView : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG)
        private var row = 0
        private var column = 0
        private var selectedStroke = StrokeDirection.DOWN
        private var mode = PickingMode.MANUAL
        private val pattern = StrumPattern.fromText("↓ ↓ ↑ ↑ ↓ ↑")
        private val undo = ArrayDeque<EditorState>()
        private val redo = ArrayDeque<EditorState>()
        var loop = false
        fun selectedColumn(): Int = column
        fun setSelectedFret(value: Int) { selectedFret=value.coerceIn(0,24); cells[row][column]=selectedFret.toString(); assignPicking(); invalidate() }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xFF111315.toInt())
            val d = resources.displayMetrics.density
            canvas.save()
            canvas.scale(d, d)
            val w = width.toFloat() / d
            val h = height.toFloat() / d
            paint.color = 0xFF191C1F.toInt()
            canvas.drawRect(0f, 0f, w, 64f, paint)
            text(canvas, "Ea", 18f, 40f, 22f, true); paint.color = 0xFFE66A2E.toInt(); canvas.drawText("r", 43f, 40f, paint); paint.color = 0xFFE5E7E8.toInt(); canvas.drawText("am", 55f, 40f, paint)
            text(canvas, projectName, 104f, 39f, 12f, false)
            text(canvas, "$bpm BPM", w - 82f, 39f, 11f, false)

            paint.color = 0xFF202428.toInt()
            canvas.drawRect(0f, 64f, w, 118f, paint)
            val tools = listOf("NOTE", "REST", "CHORD", "IMPORT", "UNDO", "REDO", "SAVE", "HOME", "PLAY", "STOP", "LOOP")
            val toolWidth = (w - 16f) / tools.size
            tools.forEachIndexed { index, label -> text(canvas, label, 8f + index * toolWidth, 97f, 9f, false) }
            text(canvas, "$instrument • $stringCount-string • $tuning • $timeSig • $keySig", 18f, 143f, 10f, false)

            val staffTop = 164f
            val staffSpacing = 9f
            line.color = 0xFF555A5F.toInt()
            for (i in 0..4) canvas.drawLine(18f, staffTop + i * staffSpacing, w - 18f, staffTop + i * staffSpacing, line)

            val gridX = 54f
            val gridY = 238f
            val stringGap = if (stringCount > 6) 25f else 28f
            val cellWidth = (w - gridX - 12f) / columnCount
            val names = when (stringCount) {
                7 -> arrayOf("e", "B", "G", "D", "A", "E", "B")
                6 -> arrayOf("e", "B", "G", "D", "A", "E")
                5 -> arrayOf("G", "D", "A", "E", "B")
                else -> arrayOf("G", "D", "A", "E")
            }

            for (stringIndex in 0 until stringCount) {
                val y = gridY + stringIndex * stringGap
                text(canvas, names[stringIndex], 18f, y + 4f, 10f, false)
                line.color = 0xFF34383C.toInt()
                canvas.drawLine(gridX, y, w - 12f, y, line)
            }

            for (index in 0..columnCount) {
                val x = gridX + index * cellWidth
                line.color = if (index % (timeSig.substringBefore('/').toIntOrNull() ?: 1).coerceAtLeast(1) == 0) 0xFF666C71.toInt() else 0xFF292D31.toInt()
                canvas.drawLine(x, gridY - 14f, x, gridY + (stringCount - 1) * stringGap + 12f, line)
            }

            val cursor = audioCursorPosition()
            if (cursor != null) {
                val cursorX = (gridX + (cursor.first + cursor.second) * cellWidth).toFloat()
                paint.color = 0xFFB7BEC3.toInt()
                canvas.drawRect(cursorX - 1.5f, gridY - 22f, cursorX + 1.5f, gridY + (stringCount - 1) * stringGap + 22f, paint)
                paint.color = 0xFFB7BEC3.toInt()
                canvas.drawCircle(cursorX, gridY - 25f, 4f, paint)
            }

            for (beat in 0 until columnCount) {
                val x = gridX + beat * cellWidth + cellWidth / 2f
                chords[beat]?.let { chord -> text(canvas, chord, x - 4f, gridY - 38f, 11f, true) }
                strokes[beat]?.let { direction -> text(canvas, if (direction == StrokeDirection.DOWN) "↓" else "↑", x - 5f, gridY - 18f, 16f, true) }
                for (stringIndex in 0 until stringCount) {
                    cells[stringIndex][beat]?.let { value -> drawTab(canvas, value, x - 5f, gridY + stringIndex * stringGap + 5f, stringIndex == row && beat == column) }
                }
                drawStandard(canvas, beat, x, staffTop)
            }

            paint.color = 0xFF1C2023.toInt()
            canvas.drawRect(0f, h - 120f, w, h, paint)
            text(canvas, "DURATION", 14f, h - 94f, 9f, false)
            listOf("½", "♩", "♪", "♬").forEachIndexed { index, symbol -> chip(canvas, symbol, 12f + index * 42f, h - 78f, 34f, index == durationIndex()) }
            text(canvas, "PICKING", 190f, h - 94f, 9f, false)
            chip(canvas, "↓", 186f, h - 78f, 34f, mode == PickingMode.MANUAL && selectedStroke == StrokeDirection.DOWN)
            chip(canvas, "↑", 224f, h - 78f, 34f, mode == PickingMode.MANUAL && selectedStroke == StrokeDirection.UP)
            chip(canvas, "ALT", 262f, h - 78f, 44f, mode == PickingMode.ALTERNATE)
            chip(canvas, "STR", 310f, h - 78f, 42f, mode == PickingMode.STRUM)
            text(canvas, if (cursor != null) "▶ ${cursor.first + 1}/$columnCount" else "Ready", 365f, h - 58f, 10f, false)

            if (playing) postInvalidateOnAnimation()
            canvas.restore()
        }

        private fun durationIndex(): Int = when (durations[column] ?: 960L) {
            1920L -> 0
            960L -> 1
            480L -> 2
            240L -> 3
            else -> 1
        }

        private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, bold: Boolean) {
            paint.color = 0xFFE5E7E8.toInt()
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(value, x, y, paint)
        }

        private fun chip(canvas: Canvas, value: String, x: Float, y: Float, w: Float, active: Boolean) {
            paint.color = if (active) 0xFF3A4248.toInt() else 0xFF252A2E.toInt()
            canvas.drawRoundRect(x, y, x + w, y + 28f, 5f, 5f, paint)
            text(canvas, value, x + 9f, y + 19f, 10f, true)
        }

        private fun drawTab(canvas: Canvas, value: String, x: Float, y: Float, active: Boolean) {
            text(canvas, value, x, y, 15f, true)
            if (active) {
                paint.style = Paint.Style.STROKE
                paint.color = 0xFF9AA1A6.toInt()
                canvas.drawCircle(x + 5f, y - 5f, 11f, paint)
                paint.style = Paint.Style.FILL
            }
        }

        private fun drawStandard(canvas: Canvas, beat: Int, x: Float, top: Float) {
            if (cells.none { it.containsKey(beat) }) return
            val values = cells.indices.filter { cells[it].containsKey(beat) }.map { midi(it, cells[it][beat]?.toIntOrNull() ?: 0) }
            if (values.isEmpty()) return
            val average = values.average()
            val y = top + 36f - (average - 60.0) * 2.0
            paint.color = 0xFFE5E7E8.toInt()
            canvas.drawCircle(x, y.toFloat(), 5f, paint)
            line.color = 0xFFE5E7E8.toInt()
            canvas.drawLine(x + 5f, y.toFloat(), x + 5f, y.toFloat() - 25f, line)
        }

        private fun snapshot(): EditorState = EditorState(Array<Map<Int, String>>(stringCount) { cells[it].toMap() }, strokes.toMap(), durations.toMap())

        private fun restore(state: EditorState) {
            cells = Array(stringCount) { state.notes[it].toMutableMap() }
            strokes = state.strokes.toMutableMap()
            durations = state.durations.toMutableMap()
        }

        private fun remember() {
            undo.addLast(snapshot())
            if (undo.size > 50) undo.removeFirst()
            redo.clear()
        }

        private fun assignPicking() {
            when (mode) {
                PickingMode.MANUAL -> strokes[column] = selectedStroke
                PickingMode.ALTERNATE -> PickingEngine.alternate(columnCount, selectedStroke).forEachIndexed { index, direction -> strokes[index] = direction }
                PickingMode.STRUM -> PickingEngine.applyPattern(pattern, columnCount).forEachIndexed { index, direction -> strokes[index] = direction }
            }
            invalidate()
        }

        private fun setDuration(value: Long) {
            remember()
            durations[column] = value
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            val d = resources.displayMetrics.density
            val x = event.x / d
            val y = event.y / d
            val w = width.toFloat() / d
            val h = height.toFloat() / d

            if (y in 64f..118f) {
                val toolWidth = (w - 16f) / 11f
                val index = ((x - 8f) / toolWidth).toInt()
                when (index) {
                    2 -> showChordDialog()
                    3 -> importGuitarPro()
                    4 -> if (undo.isNotEmpty()) { redo.addLast(snapshot()); restore(undo.removeLast()); invalidate() }
                    5 -> if (redo.isNotEmpty()) { undo.addLast(snapshot()); restore(redo.removeLast()); invalidate() }
                    6 -> saveProject()
                    7 -> showHome()
                    8 -> startPlayback()
                    9 -> stopPlayback()
                    10 -> { loop = !loop; invalidate() }
                }
                return true
            }

            if (y >= h - 120f) {
                when {
                    x in 12f..58f -> setDuration(1920L)
                    x in 64f..110f -> setDuration(960L)
                    x in 116f..162f -> setDuration(480L)
                    x in 168f..214f -> setDuration(240L)
                    x in 190f..230f -> { selectedStroke = StrokeDirection.DOWN; mode = PickingMode.MANUAL; remember(); assignPicking() }
                    x in 235f..275f -> { selectedStroke = StrokeDirection.UP; mode = PickingMode.MANUAL; remember(); assignPicking() }
                    x in 280f..325f -> { mode = PickingMode.ALTERNATE; remember(); assignPicking() }
                    x in 330f..375f -> { mode = PickingMode.STRUM; remember(); assignPicking() }
                }
                return true
            }

            val gridX = 54f
            val cellWidth = (w - gridX - 12f) / columnCount
            val stringGap = if (stringCount > 6) 25f else 28f
            if (x in gridX..(w - 12f) && y in 220f..(238f + (stringCount - 1) * stringGap + 20f)) {
                column = ((x - gridX) / cellWidth).toInt().coerceIn(0, columnCount - 1)
                row = ((y - 238f + stringGap / 2f) / stringGap).toInt().coerceIn(0, stringCount - 1)
                remember()
                val fret = ((x - gridX) / cellWidth * 4f).toInt().coerceIn(0, 24)
                cells[row][column] = fret.toString()
                assignPicking()
                invalidate()
            }
            return true
        }
    }
}
