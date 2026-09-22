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
import android.view.ViewConfiguration
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.LinearLayout
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import alphaTab.AlphaTabView
import alphaTab.LayoutMode
import alphaTab.PlayerMode
import alphaTab.model.Note
import com.earam.tabs.music.PickingEngine
import com.earam.tabs.music.PickingMode
import com.earam.tabs.music.StrumPattern
import com.earam.tabs.music.StrokeDirection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
    private var projectName = "Untitled"
    private var instrument = "Guitar"
    private var stringCount = 6
    private var tuning = "Standard"
    private var bpm = 120
    private var timeSig = "4/4"
    private var keySig = "C"
    private var notation = "BOTH"
    private var selectedFret = 0
    private var savedColumn = 0
    private var savedRow = 0
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
    private val columnCount = 256

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            projectName = it.getString("projectName", projectName)
            instrument = it.getString("instrument", instrument)
            stringCount = it.getInt("stringCount", stringCount).coerceIn(3, 10)
            tuning = it.getString("tuning", tuning)
            bpm = it.getInt("bpm", bpm)
            timeSig = it.getString("timeSig", timeSig)
            keySig = it.getString("keySig", keySig)
            notation = it.getString("notation", notation)
            savedColumn = it.getInt("column", 0)
            savedRow = it.getInt("row", 0)
            cells = Array(stringCount) { mutableMapOf() }
            val notes = it.getStringArrayList("notes")
            notes?.forEachIndexed { s, encoded ->
                encoded.split("|").forEach { pair ->
                    val p = pair.split("=", limit = 2)
                    if (p.size == 2 && s < cells.size) cells[s][p[0].toIntOrNull() ?: return@forEach] = p[1]
                }
            }
        }
        // Open directly into the music editor. There is no intermediate Home screen.
        openEditor()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("projectName", projectName)
        outState.putString("instrument", instrument)
        outState.putInt("stringCount", stringCount)
        outState.putString("tuning", tuning)
        outState.putInt("bpm", bpm)
        outState.putString("timeSig", timeSig)
        outState.putString("keySig", keySig)
        outState.putString("notation", notation)
        outState.putInt("column", editor?.selectedColumn() ?: savedColumn)
        outState.putInt("row", savedRow)
        outState.putStringArrayList("notes", ArrayList(cells.map { map -> map.entries.joinToString("|") { "${it.key}=${it.value}" } }))
        super.onSaveInstanceState(outState)
    }

    private fun showHome() {
        // Home is no longer a separate landing screen; return to the editor.
        openEditor()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun newProject() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28f), dp(8f), dp(28f), 0)
        }

        val name = EditText(this).apply { hint = "Project name" }
        val selectedInstrument = arrayOf("Electric Guitar")
        val instrumentButton = Button(this).apply { text = "GUITARS  •  Electric Guitar" }

        instrumentButton.setOnClickListener {
            showInstrumentBrowser { category, type ->
                selectedInstrument[0] = type
                instrumentButton.text = "$category  •  $type"
            }
        }

        val strings = spinner((3..10).map(Int::toString).toTypedArray(), 3)
        val tune = spinner(arrayOf("Standard", "Drop D", "Drop C", "Custom"))
        val sig = spinner(arrayOf("2/4", "3/4", "4/4", "5/4", "6/8", "7/8", "9/8", "12/8"))
        val key = spinner(arrayOf("C", "G", "D", "A", "E", "F", "Am", "Em"))
        val tempo = EditText(this).apply {
            hint = "BPM"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("120")
        }

        listOf(
            TextView(this).apply { text = "Instrument family / type" },
            instrumentButton,
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
                instrument = selectedInstrument[0]
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

    private fun showInstrumentBrowser(onSelected: (String, String) -> Unit) {
        val families = arrayOf(
            "GUITARS",
            "BASSES",
            "KEYBOARDS",
            "STRINGS",
            "WOODWINDS",
            "BRASS",
            "WORLD / AFRICAN",
            "WORLD / ASIAN",
            "WORLD / MIDDLE EAST",
            "WORLD / BRAZILIAN",
            "MALLETS / PERCUSSION",
            "FOLK / PLUCKED",
            "SYNTHS",
            "DRUMS / PERCUSSION"
        )

        val types = mapOf(
            "GUITARS" to arrayOf(
                "Acoustic Guitar",
                "Classical Guitar",
                "Electric Guitar",
                "Distortion Guitar",
                "Clean Electric Guitar",
                "12-String Guitar",
                "7-String Guitar",
                "8-String Guitar",
                "Baritone Guitar"
            ),
            "BASSES" to arrayOf(
                "Bass",
                "5-String Bass",
                "6-String Bass",
                "Fretless Bass"
            ),
            "KEYBOARDS" to arrayOf(
                "Piano",
                "Electric Piano",
                "Organ"
            ),
            "STRINGS" to arrayOf(
                "Violin",
                "Viola",
                "Cello",
                "Double Bass"
            ),
            "WOODWINDS" to arrayOf(
                "Flute",
                "Clarinet",
                "Oboe",
                "Saxophone"
            ),
            "BRASS" to arrayOf(
                "Trumpet", "Trombone", "French Horn", "Euphonium", "Tuba",
                "Cornet", "Flugelhorn", "Baritone Horn"
            ),
            "WORLD / AFRICAN" to arrayOf(
                "Djembe", "Dundun", "Talking Drum", "African Conga", "African Shekere",
                "African Agogo", "African Bell", "Udu", "Kalimba"
            ),
            "WORLD / ASIAN" to arrayOf(
                "Hang Drum", "Tabla", "Taiko", "Bodhrán", "Cajón", "Darabuka",
                "Riq", "Bendir", "Frame Drum", "Chinese Gong", "Japanese Kotsuzumi",
                "Japanese Shime-daiko"
            ),
            "WORLD / MIDDLE EAST" to arrayOf(
                "Tabla / Doumbek", "Darbuka", "Riq", "Bendir", "Daf", "Mizhar",
                "Zarb", "Sagat / Finger Cymbals"
            ),
            "WORLD / BRAZILIAN" to arrayOf(
                "Surdo", "Pandeiro", "Repinique", "Tamborim", "Caixa",
                "Agogô", "Berimbau", "Atabaque"
            ),
            "MALLETS / PERCUSSION" to arrayOf(
                "Marimba", "Xylophone", "Vibraphone", "Glockenspiel",
                "Tubular Bells", "Steel Tongue Drum", "Handpan"
            ),
            "FOLK / PLUCKED" to arrayOf(
                "Banjo",
                "Mandolin",
                "Ukulele",
                "Harp",
                "Harmonica"
            ),
            "SYNTHS" to arrayOf(
                "Synth Lead",
                "Synth Pad"
            ),
            "DRUMS / PERCUSSION" to arrayOf(
                "Drum Kit", "Acoustic Drum Kit", "Rock Drum Kit", "Jazz Drum Kit",
                "Metal Drum Kit", "Electronic Drum Kit", "Orchestral Percussion",
                "Concert Bass Drum", "Snare Drum", "Kick Drum", "Tom-Tom", "Floor Tom",
                "Hi-Hat", "Ride Cymbal", "Crash Cymbal", "China Cymbal", "Splash Cymbal",
                "Cowbell", "Tambourine", "Claves", "Castanets", "Triangle",
                "Wood Block", "Shaker"
            )
        )

        AlertDialog.Builder(this)
            .setTitle("INSTRUMENTS")
            .setItems(families) { _, familyIndex ->
                val family = families[familyIndex]
                val choices = types[family] ?: emptyArray()
                AlertDialog.Builder(this)
                    .setTitle(family)
                    .setItems(choices) { _, typeIndex ->
                        onSelected(family, choices[typeIndex])
                    }
                    .setNegativeButton("Back") { _, _ -> showInstrumentBrowser(onSelected) }
                    .show()
            }
            .setNegativeButton("Cancel", null)
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

            val chordJson = JSONObject()
            chords.forEach { (k, v) -> chordJson.put(k.toString(), v) }
            root.put("chords", chordJson)

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
                "Distortion Guitar" -> GuitarSoundEngine.Voice.DISTORTION_GUITAR
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
            while (offset < data.size && playing) {                val written = audio.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
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
    private data class ChordShape(val name: String, val notes: List<String>, val frets: List<Int>)

    private val commonChords = listOf(
        "C", "Cm", "C7", "Cmaj7", "Cm7", "C5", "Csus2", "Csus4", "Cadd9",
        "D", "Dm", "D7", "Dmaj7", "Dm7", "D5", "Dsus2", "Dsus4", "Dadd9",
        "E", "Em", "E7", "Emaj7", "Em7", "E5", "Esus2", "Esus4", "Eadd9",
        "F", "Fm", "F7", "Fmaj7", "Fm7", "F5", "Fsus2", "Fsus4", "Fadd9",
        "G", "Gm", "G7", "Gmaj7", "Gm7", "G5", "Gsus2", "Gsus4", "Gadd9",
        "A", "Am", "A7", "Amaj7", "Am7", "A5", "Asus2", "Asus4", "Aadd9",
        "B", "Bm", "B7", "Bmaj7", "Bm7", "B5", "Bsus2", "Bsus4", "Badd9"
    )

    private fun chordIntervals(symbol: String): Pair<String, IntArray>? {
        val m = Regex("^([A-Ga-g])([#b]?)(.*)$").find(symbol.trim()) ?: return null
        val root = m.groupValues[1].uppercase() + m.groupValues[2]
        val q = m.groupValues[3].lowercase()
        val intervals = when {
            q.startsWith("maj13") -> intArrayOf(0,4,7,11,14,17)
            q.startsWith("13") -> intArrayOf(0,4,7,10,14,17)
            q.startsWith("maj11") -> intArrayOf(0,4,7,11,14,17)
            q.startsWith("11") -> intArrayOf(0,4,7,10,14,17)
            q.startsWith("maj9") -> intArrayOf(0,4,7,11,14)
            q.startsWith("9") -> intArrayOf(0,4,7,10,14)
            q.startsWith("maj7") -> intArrayOf(0,4,7,11)
            q.startsWith("m7") || q.startsWith("min7") -> intArrayOf(0,3,7,10)
            q.startsWith("7") -> intArrayOf(0,4,7,10)
            q.startsWith("dim7") -> intArrayOf(0,3,6,9)
            q.startsWith("dim") -> intArrayOf(0,3,6)
            q.startsWith("aug") || q.startsWith("+") -> intArrayOf(0,4,8)
            q.startsWith("sus4") -> intArrayOf(0,5,7)
            q.startsWith("sus2") -> intArrayOf(0,2,7)
            q.startsWith("add9") -> intArrayOf(0,4,7,14)
            q.startsWith("m") || q.startsWith("min") -> intArrayOf(0,3,7)
            q.startsWith("5") -> intArrayOf(0,7)
            else -> intArrayOf(0,4,7)
        }
        return root to intervals
    }

    private fun noteClass(name: String): Int = when (name) {
        "C" -> 0; "C#" -> 1; "Db" -> 1; "D" -> 2; "D#" -> 3; "Eb" -> 3
        "E" -> 4; "F" -> 5; "F#" -> 6; "Gb" -> 6; "G" -> 7; "G#" -> 8
        "Ab" -> 8; "A" -> 9; "A#" -> 10; "Bb" -> 10; "B" -> 11; else -> 0
    }

    private fun buildChordShape(symbol: String): ChordShape? {
        val parsed = chordIntervals(symbol) ?: return null
        val rootPc = noteClass(parsed.first)
        val target = parsed.second.map { (rootPc + it) % 12 }.toSet()
        val candidates = Array(stringCount) { stringIndex ->
            (0..12).filter { fret -> (midi(stringIndex, fret) % 12) in target }.take(7)
        }
        var best: List<Int>? = null
        var bestScore = Int.MAX_VALUE
        fun search(s: Int, shape: MutableList<Int>, minFret: Int, maxFret: Int) {
            if (s == stringCount) {
                val sounded = shape.withIndex().filter { it.value >= 0 }
                if (sounded.size < 3) return
                val pcs = sounded.map { midi(it.index, it.value) % 12 }.toSet()
                if (!pcs.contains(rootPc) || !target.all { it in pcs }) return
                val score = (maxFret - minFret) * 8 + shape.count { it < 0 } * 3 + shape.sumOf { if (it > 0) it else 0 }
                if (score < bestScore) { bestScore = score; best = shape.toList() }
                return
            }
            for (fret in listOf(-1) + candidates[s]) {
                val nextMin = if (fret >= 0) minOf(minFret, fret) else minFret
                val nextMax = if (fret >= 0) maxOf(maxFret, fret) else maxFret
                if (nextMax - nextMin > 5) continue
                shape.add(fret)
                search(s + 1, shape, nextMin, nextMax)
                shape.removeAt(shape.lastIndex)
            }
        }
        search(0, mutableListOf(), 99, 0)
        val shape = best ?: return null
        val noteNames = shape.withIndex().filter { it.value >= 0 }.map { midiToNoteName(midi(it.index, it.value)) }.distinct()
        return ChordShape(symbol, noteNames, shape)
    }

    private fun midiToNoteName(value: Int): String {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        return names[(value % 12 + 12) % 12]
    }

    private fun applyChord(shape: ChordShape, columnIndex: Int) {
        chords[columnIndex] = shape.name
        shape.frets.forEachIndexed { stringIndex, fret ->
            if (stringIndex >= cells.size) return@forEachIndexed
            if (fret < 0) cells[stringIndex].remove(columnIndex)
            else cells[stringIndex][columnIndex] = fret.toString()
        }
        editor?.selectCell(0, columnIndex)
        editor?.invalidate()
    }

    private fun showChordDialog() {
        val input = EditText(this).apply {
            hint = "Type any chord, e.g. Am7, F#maj7, Cadd9"
            setSingleLine(true)
            setText(chords[editor?.selectedColumn() ?: 0] ?: "")
        }
        val preview = TextView(this).apply {
            text = "Type a chord to see its notes and TAB fingering."
            setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
        }
        val list = ArrayAdapter(this, android.R.layout.simple_list_item_1, commonChords.toTypedArray())
        val chordList = android.widget.ListView(this).apply {
            adapter = list
            layoutParams = LinearLayout.LayoutParams(-1, dp(180f))
            setOnItemClickListener { _, _, position, _ ->
                input.setText(commonChords[position])
                input.setSelection(input.text.length)
                updateChordPreview(input, preview)
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateChordPreview(input, preview)
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(16f), 0)
            addView(input)
            addView(preview)
            addView(chordList)
        }
        AlertDialog.Builder(this)
            .setTitle("CHORDS — NOTES + TAB")
            .setView(box)
            .setNegativeButton("Close", null)
            .setPositiveButton("PUT ON TAB") { _, _ ->
                val value = input.text.toString().trim()
                val shape = buildChordShape(value)
                if (shape == null) Toast.makeText(this, "Chord not recognized or no playable voicing found", Toast.LENGTH_LONG).show()
                else applyChord(shape, editor?.selectedColumn() ?: 0)
            }
            .show()
        updateChordPreview(input, preview)
    }

    private fun updateChordPreview(input: EditText, preview: TextView) {
        val value = input.text.toString().trim()
        if (value.isBlank()) {
            preview.text = "Type a chord to see its notes and TAB fingering."
            return
        }
        val shape = buildChordShape(value)
        preview.text = if (shape == null) "No playable voicing found for: " + value
        else {
            val fingering = shape.frets.joinToString(" ") { if (it < 0) "x" else it.toString() }
            "NOTES: " + shape.notes.joinToString("  ") + "\\nTAB:  " + fingering + "\\n\\nPUT ON TAB places the chord at the selected beat."
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
                    0 -> newProject()
                    1 -> openProject()
                    2 -> saveProject()
                    3 -> saveProjectAs()
                    4 -> importTab()
                    5 -> exportProject()
                    6 -> closeToHome()
                    7 -> updateManager.checkForUpdates()
                }
            }
            .show()
    }

    private fun importTab() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    }
        startActivityForResult(intent, 4107)
    }

    @OptIn(kotlin.contracts.ExperimentalContracts::class)
    private fun showAlphaTabPreview(uri: Uri, fileName: String) {
        val dialog = AlertDialog.Builder(this).create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF777777.toInt())
        }

        val title = TextView(this).apply {
            text = "Earam  •  " + fileName.substringBeforeLast('.')
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            setBackgroundColor(0xFF191C1F.toInt())
        }

        val status = TextView(this).apply {
            text = "Loading TAB…"
            setTextColor(0xFFE8E8E8.toInt())
            textSize = 12f
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            setBackgroundColor(0xFF25292D.toInt())
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
            setPadding(dp(6f), dp(5f), dp(6f), dp(5f))
            setBackgroundColor(0xFF25292D.toInt())
        }

        fun control(text: String): Button = Button(this).apply {
            this.text = text
            isAllCaps = false
            minWidth = 0
            setPadding(dp(6f), 0, dp(6f), 0)
        }

        val play = control("▶")
        val stop = control("■")
        val slower = control("0.5×")
        val slow = control("0.75×")
        val normal = control("1.0×")
        val fast = control("1.25×")
        val faster = control("1.5×")

        listOf(play, stop, slower, slow, normal, fast, faster).forEach { b ->
            controls.addView(b, LinearLayout.LayoutParams(0, dp(44f), 1f))
        }

        val score = AlphaTabView(this, null)
        score.setBackgroundColor(0xFFFFFFFF.toInt())
        score.settings.display.layoutMode = LayoutMode.Page
        score.settings.display.barsPerRow = 3.0
        score.settings.display.barCount = -1.0
        score.settings.display.startBar = 1.0
        score.settings.display.scale = 0.80
        score.settings.display.stretchForce = 0.75
        score.settings.core.includeNoteBounds = true
        score.settings.player.playerMode = PlayerMode.EnabledSynthesizer
        score.settings.player.enablePlayer = true
        score.settings.player.enableUserInteraction = true
        score.settings.player.enableCursor = true
        score.settings.player.enableElementHighlighting = true
        score.api.updateSettings()

        val noteEditor = AlphaTabNoteEditor(this, score, status)
        noteEditor.attach()

        fun setSpeed(value: Double) {
            score.api.playbackSpeed = value
            status.text = "Playback: " + String.format(java.util.Locale.US, "%.0f%%", value * 100.0)
        }

        fun refreshBarsForWidth(widthPx: Int) {
            val widthDp = widthPx / resources.displayMetrics.density
            val desired = when {
                widthDp >= 900f -> 5.0
                widthDp >= 700f -> 4.0
                else -> 3.0
            }
            if (score.settings.display.barsPerRow != desired) {
                score.settings.display.barsPerRow = desired
                score.api.updateSettings()
                score.api.render()
            }
        }

        score.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            refreshBarsForWidth(right - left)
        }

        // AlphaTab does not provide a built-in player toolbar; these controls are
        // deliberately part of the Earam import window.
        play.setOnClickListener {
            score.api.playPause()
        }
        stop.setOnClickListener {
            score.api.stop()
            play.text = "▶"
        }
        slower.setOnClickListener { setSpeed(0.50) }
        slow.setOnClickListener { setSpeed(0.75) }
        normal.setOnClickListener { setSpeed(1.00) }
        fast.setOnClickListener { setSpeed(1.25) }
        faster.setOnClickListener { setSpeed(1.50) }

        score.api.playerReady.on {
            runOnUiThread {
                status.text = "Ready • 100% speed"
                play.isEnabled = true
                stop.isEnabled = true
                setSpeed(1.0)
            }
        }
        score.api.playerStateChanged.on {
            runOnUiThread {
                play.text = if (score.api.playerState.toString().contains("Playing", ignoreCase = true)) "Ⅱ" else "▶"
            }
        }
        score.api.soundFontLoaded.on {
            runOnUiThread {
                status.text = "Sound ready • 100% speed"
            }
        }

        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        root.addView(score, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setView(root)
        dialog.show()
        dialog.window?.setLayout(-1, -1)

        play.isEnabled = false
        stop.isEnabled = false

        score.api.scoreLoaded.on { loadedScore ->
            runOnUiThread {
                title.text = "Earam  •  " + loadedScore.title.ifBlank { fileName.substringBeforeLast('.') }
                refreshBarsForWidth(score.width)
                status.text = "TAB loaded • loading sound…"
            }
        }

        Thread {
            try {
                // Use alphaTab's native Android loading path. It accepts an InputStream
                // and dispatches the raw bytes to the correct importer by file format.
                // This is important for legacy binary Guitar Pro 3/4/5 files (.gp3/.gp4/.gp5).
                val loaded = contentResolver.openInputStream(uri)?.use { input ->
                    score.api.load(input)
                } ?: throw IllegalStateException("Cannot read TAB file")

                if (!loaded) {
                    throw IllegalStateException("alphaTab rejected this file format")
                }

                // Android alphaTab does not download a SoundFont from a URL automatically.
                // Load the bundled-version-compatible Sonivox bank explicitly, then build MIDI.
                val sfUrl = "https://cdn.jsdelivr.net/npm/@coderline/alphatab@1.8.4/dist/soundfont/sonivox.sf2"
                val connection = (URL(sfUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                }
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("SoundFont download failed: HTTP " + connection.responseCode)
                }
                val soundFontBytes = connection.inputStream.use { it.readBytes() }
                connection.disconnect()

                runOnUiThread {
                    try {
                        val loaded = score.api.loadSoundFont(java.io.ByteArrayInputStream(soundFontBytes), false)
                        if (!loaded) throw IllegalStateException("SoundFont format was rejected")
                        score.api.loadMidiForScore()
                        status.text = "Sound ready • 100% speed"
                        play.isEnabled = true
                        stop.isEnabled = true
                    } catch (e: Exception) {
                        dialog.dismiss()
                        Toast.makeText(
                            this,
                            "Playback setup failed: " + (e.message ?: "SoundFont error"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(
                        this,
                        "TAB import failed: " + (e.message ?: "unsupported or damaged file"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun saveProjectAs() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28f), dp(4f), dp(28f), 0)
        }
        val name = EditText(this).apply {
            hint = "File name"
            setSingleLine(true)
            setText(projectName)
            selectAll()
        }
        box.addView(name)
        AlertDialog.Builder(this)
            .setTitle("SAVE FILE AS")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val value = name.text.toString().trim().ifBlank { projectName }
                projectName = value
                saveProject()
                editor?.invalidate()
            }
            .show()
    }

    private fun exportProject() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "${safeFileName(projectName)}.earam")
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, 4102)
    }

    private fun closeToHome() {
        AlertDialog.Builder(this)
            .setTitle("HOME / CLOSE")
            .setMessage("Earam opens directly in the editor. Close the current editor?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Close") { _, _ -> finish() }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return

        when (requestCode) {
            4107 -> {
                val uri = data.data!!
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "IMPORT TAB"
                Thread {
                    try {
                        runOnUiThread {
                            projectName = name.substringBeforeLast('.').ifBlank { "Imported TAB" }
                            savedColumn = 0
                            savedRow = 0
                            showAlphaTabPreview(uri, name)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "TAB import failed: " + (e.message ?: "unsupported or damaged file"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            }
            4102 -> {
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
                    val chordJson = JSONObject()
                    chords.forEach { (k, v) -> chordJson.put(k.toString(), v) }
                    root.put("chords", chordJson)
                    val durationJson = JSONObject()
                    durations.forEach { (k, v) -> durationJson.put(k.toString(), v) }
                    root.put("durations", durationJson)
                    contentResolver.openOutputStream(data.data!!)?.use { it.write(root.toString(2).toByteArray()) }
                    Toast.makeText(this, "Exported: $projectName", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @OptIn(kotlin.contracts.ExperimentalContracts::class)
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

        private var armed = false
        private var pendingFret: String = ""
        private var pendingAtMs: Long = 0L

        fun attach() {
            score.isFocusableInTouchMode = true
            score.setOnKeyListener { _, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { moveBeat(-1); true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { moveBeat(1); true }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> { moveString(-1); true }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { moveString(1); true }
                    android.view.KeyEvent.KEYCODE_DEL,
                    android.view.KeyEvent.KEYCODE_FORWARD_DEL -> { deleteCurrentNote(); true }
                    else -> {
                        val n = event.unicodeChar
                        if (n in '0'.code..'9'.code) {
                            acceptDigit(n - '0'.code)
                            true
                        } else false
                    }
                }
            }

            score.api.noteMouseDown.on { note ->
                val beat = note.beat
                val track = score.api.score?.tracks?.firstOrNull() ?: return@on
                val staff = track.staves.firstOrNull() ?: return@on
                for (bi in staff.bars.indices) {
                    val beats = staff.bars[bi].voices.firstOrNull()?.beats ?: continue
                    val index = beats.indexOf(beat)
                    if (index >= 0) {
                        currentBarIndex = bi
                        currentBeatIndex = index
                        currentStringIndex = note.string.toInt().coerceAtLeast(1)
                        armed = true
                        updateStatus()
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

        private fun bars() =
            score.api.score?.tracks?.firstOrNull()?.staves?.firstOrNull()?.bars

        private fun currentBeat(): alphaTab.model.Beat? =
            bars()?.getOrNull(currentBarIndex)?.voices?.firstOrNull()?.beats?.getOrNull(currentBeatIndex)

        private fun moveBeat(delta: Int) {
            val bs = bars() ?: return
            var b = currentBarIndex
            var beat = currentBeatIndex + delta
            while (b >= 0 && b < bs.size) {
                val count = bs[b].voices.firstOrNull()?.beats?.size ?: 0
                if (count > 0 && beat in 0 until count) break
                if (beat < 0) {
                    b--
                    beat = (bs.getOrNull(b)?.voices?.firstOrNull()?.beats?.size ?: 1) - 1
                } else {
                    b++
                    beat = 0
                }
            }
            if (b in bs.indices) {
                currentBarIndex = b
                currentBeatIndex = beat.coerceAtLeast(0)
                armed = true
                updateStatus()
            }
        }

        private fun moveString(delta: Int) {
            val maxString = score.api.score?.tracks?.firstOrNull()?.staves?.firstOrNull()?.tuning?.tunings?.size ?: 6
            currentStringIndex = (currentStringIndex + delta).coerceIn(1, maxString)
            armed = true
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
                val note = Note()
                note.string = currentStringIndex.toDouble()
                note.fret = fret.toDouble()
                beat.addNote(note)
            }
            score.api.score?.finish(score.settings)
            score.api.render()
            updateStatus("Fret $fret • Bar " + (currentBarIndex + 1) + " • Beat " + (currentBeatIndex + 1) + " • String " + currentStringIndex)
        }
        private fun deleteCurrentNote() {
            val beat = currentBeat() ?: return
            val note = beat.getNoteOnString(currentStringIndex.toDouble()) ?: return
            beat.removeNote(note)
            score.api.score?.finish(score.settings)
            score.api.render()
            updateStatus("Note deleted")
        }

        private fun updateStatus(message: String? = null) {
            val text = message ?: "EDIT  •  Bar ${currentBarIndex + 1}  •  Beat ${currentBeatIndex + 1}  •  String $currentStringIndex"
            activity.runOnUiThread { status.text = text }
        }
    }

    private var fretInput: EditText? = null

    private fun openEditor() {
        editor = EditorView()
        val frame = FrameLayout(this)
        frame.addView(editor, FrameLayout.LayoutParams(-1, -1))
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            maxLines = 1
            maxWidth = 1
            alpha = 0.01f
            background = null
            visibility = View.INVISIBLE
        }
        fretInput = input
        frame.addView(input, FrameLayout.LayoutParams(1, 1))
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString()?.toIntOrNull() ?: return
                if (value in 0..24) editor?.setSelectedFretFromKeyboard(value)
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        setContentView(frame)
        editor?.restoreSelection(savedRow, savedColumn)
    }

    private fun editFretAt(rowIndex: Int, columnIndex: Int) {
        savedRow = rowIndex
        savedColumn = columnIndex
        editor?.selectCell(rowIndex, columnIndex)
        val input = fretInput ?: return
        input.visibility = View.VISIBLE
        input.setText("")
        input.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }
    private fun finishFretEdit() {
        fretInput?.let {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
            it.visibility = View.INVISIBLE
        }
    }

    private inner class EditorView : View(this) {
        init {
            isFocusableInTouchMode = true
            requestFocus()
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG)
        private var row = 0
        private var column = 0
        private var scrollColumn = 0
        private var scoreScrollY = 0f
        private var downX = 0f
        private var downYRaw = 0f
        private var downY = 0f
        private var dragging = false
        private val touchSlop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
        private var selectedStroke = StrokeDirection.DOWN
        private var mode = PickingMode.MANUAL
        private val pattern = StrumPattern.fromText("↓ ↓ ↑ ↑ ↓ ↑")
        private val undo = ArrayDeque<EditorState>()
        private val redo = ArrayDeque<EditorState>()
        var loop = false
        fun selectedColumn(): Int = column
        fun restoreSelection(r: Int, c: Int) { row = r.coerceIn(0, stringCount - 1); column = c.coerceIn(0, columnCount - 1); scoreScrollY = 0f; invalidate() }
        fun selectCell(r: Int, c: Int) { row = r; column = c; savedRow = r; savedColumn = c; invalidate() }
        fun setSelectedFret(value: Int) { selectedFret=value.coerceIn(0,24); cells[row][column]=selectedFret.toString(); assignPicking(); invalidate() }
        fun setSelectedFretFromKeyboard(value: Int) { setSelectedFret(value) }

        private fun timeNumerator(): Int = timeSig.substringBefore('/').toIntOrNull()?.coerceIn(1, 16) ?: 4
        private fun timeDenominator(): Int = timeSig.substringAfter('/', "4").toIntOrNull()?.let { if (it in setOf(1, 2, 4, 8, 16)) it else 4 } ?: 4
        private fun beatUnitTicks(): Long = 3840L / timeDenominator().toLong()
        private fun barTicks(): Long = timeNumerator().toLong() * beatUnitTicks()

        private fun measureStarts(): List<Int> {
            val result = mutableListOf<Int>()
            val capacity = barTicks()
            var cursor = 0
            while (cursor < columnCount) {
                result += cursor
                var used = 0L
                val start = cursor
                while (cursor < columnCount) {
                    val duration = durations[cursor] ?: 960L
                    if (used + duration > capacity) break
                    used += duration
                    cursor++
                    if (used == capacity) break
                }
                if (cursor == start) cursor++
            }
            return result
        }

        private fun measureEnd(start: Int): Int {
            val capacity = barTicks()
            var used = 0L
            var cursor = start
            while (cursor < columnCount) {
                val duration = durations[cursor] ?: 960L
                if (used + duration > capacity) break
                used += duration
                cursor++
                if (used == capacity) break
            }
            return cursor.coerceAtLeast(start + 1).coerceAtMost(columnCount)
        }

        private fun moveColumn(delta: Int) {
            finishFretEdit()
            column = (column + delta).coerceIn(0, columnCount - 1)
            savedColumn = column
            invalidate()
        }

        private fun moveString(delta: Int) {
            row = (row + delta).coerceIn(0, stringCount - 1)
            savedRow = row
            invalidate()
        }

        override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
            return when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { moveColumn(1); true }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { moveColumn(-1); true }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { moveString(1); true }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> { moveString(-1); true }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xFF111315.toInt())
            val d = resources.displayMetrics.density
            canvas.save()
            canvas.scale(d, d)
            val w = width.toFloat() / d
            val h = height.toFloat() / d

            paint.color = 0xFF191C1F.toInt()
            canvas.drawRect(0f, 0f, w, 64f, paint)
            text(canvas, "E", 18f, 40f, 22f, true)
            paint.textSize = 22f
            paint.typeface = Typeface.DEFAULT_BOLD
            val eWidth = paint.measureText("E")
            paint.color = 0xFFE5E7E8.toInt()
            canvas.drawText("a", 18f + eWidth, 40f, paint)
            val a1Width = paint.measureText("a")
            paint.color = 0xFFE66A2E.toInt()
            canvas.drawText("r", 18f + eWidth + a1Width, 40f, paint)
            val rWidth = paint.measureText("r")
            paint.color = 0xFFE5E7E8.toInt()
            canvas.drawText("am", 18f + eWidth + a1Width + rWidth, 40f, paint)
            text(canvas, projectName, 104f, 39f, 12f, false)
            text(canvas, "$bpm BPM", w - 82f, 39f, 11f, false)

            paint.color = 0xFF202428.toInt()
            canvas.drawRect(0f, 64f, w, 118f, paint)
            val tools = listOf("FILE", "NOTE", "REST", "CHORD", "IMPORT", "UNDO", "REDO", "SAVE", "◀ PREV", "NEXT ▶", "PLAY", "STOP", "LOOP")
            val toolWidth = (w - 16f) / tools.size
            tools.forEachIndexed { index, label -> text(canvas, label, 8f + index * toolWidth, 97f, 9f, index == 8 || index == 9) }
            text(canvas, "$instrument • $stringCount-string • $tuning • $timeSig • $keySig", 18f, 143f, 10f, false)

            val pageLeft = 12f
            val pageRight = w - 12f
            val systemsPerPage = 4
            val barsPerSystem = 4
            val bars = measureStarts()
            val barsPerPage = systemsPerPage * barsPerSystem
            val totalPages = ((bars.size + barsPerPage - 1) / barsPerPage).coerceAtLeast(1)
            val systemHeight = 132f + stringCount * 2.5f
            val pageHeight = systemsPerPage * systemHeight + 58f
            val pageGap = 24f
            val firstPageTop = 158f
            val contentBottom = h - 126f

            canvas.save()
            canvas.clipRect(0f, 151f, w, contentBottom)
            val visibleTop = scoreScrollY
            val visibleBottom = scoreScrollY + (contentBottom - 151f)
            val firstPage = ((visibleTop - firstPageTop) / (pageHeight + pageGap)).toInt().coerceAtLeast(0)
            val lastPage = ((visibleBottom - firstPageTop) / (pageHeight + pageGap)).toInt().coerceAtMost(totalPages - 1)

            for (pageIndex in firstPage..lastPage) {
                if (pageIndex !in 0 until totalPages) continue
                val pageY = firstPageTop + pageIndex * (pageHeight + pageGap) - scoreScrollY
                paint.color = 0xFFFFFFFF.toInt()
                canvas.drawRect(pageLeft, pageY, pageRight, pageY + pageHeight, paint)
                pageText(canvas, projectName, pageLeft + 22f, pageY + 28f, 14f, true)
                pageText(canvas, instrument + " • " + bpm + " BPM • " + timeSig, pageRight - 190f, pageY + 28f, 9f, false)

                for (system in 0 until systemsPerPage) {
                    val barIndex = pageIndex * barsPerPage + system * barsPerSystem
                    if (barIndex >= bars.size) continue
                    val systemTop = pageY + 42f + system * systemHeight
                    val left = pageLeft + 22f
                    val right = pageRight - 22f
                    val systemBars = barIndex until minOf(barIndex + barsPerSystem, bars.size)
                    val systemStart = bars[barIndex]
                    val lastBar = systemBars.last()
                    val systemEnd = if (lastBar + 1 < bars.size) bars[lastBar + 1] else columnCount
                    var totalTicks = 0L
                    for (c in systemStart until systemEnd) totalTicks += durations[c] ?: 960L
                    val usableTicks = totalTicks.coerceAtLeast(1L)
                    val staffTop = systemTop + 4f
                    val tabTop = systemTop + 47f
                    val gap = if (stringCount > 6) 12f else 14f

                    line.color = 0xFF222222.toInt()
                    line.strokeWidth = 1f
                    for (i in 0..4) canvas.drawLine(left, staffTop + i * 7f, right, staffTop + i * 7f, line)
                    pageText(canvas, "𝄞", left + 3f, staffTop + 25f, 24f, false)
                    val tabLineStart = tabTop + 8f
                    for (stringIndex in 0 until stringCount) {
                        canvas.drawLine(left, tabLineStart + stringIndex * gap, right, tabLineStart + stringIndex * gap, line)
                    }

                    var elapsedTicks = 0L
                    for (b in systemBars) {
                        val measureStart = bars[b]
                        val measureEnd = if (b + 1 < bars.size) bars[b + 1] else columnCount
                        var beatCursor = measureStart
                        while (beatCursor < measureEnd && beatCursor < columnCount) {
                            val duration = durations[beatCursor] ?: 960L
                            val x = left + ((elapsedTicks + duration / 2.0) / usableTicks.toDouble() * (right - left)).toFloat()
                            chords[beatCursor]?.let { chord -> pageText(canvas, chord, x - 8f, systemTop - 1f, 10f, true) }
                            strokes[beatCursor]?.let { direction -> pageText(canvas, if (direction == StrokeDirection.DOWN) "↓" else "↑", x - 4f, tabLineStart - 7f, 12f, true) }
                            for (stringIndex in 0 until stringCount) {
                                cells[stringIndex][beatCursor]?.let { value ->
                                    drawTab(canvas, value, x - 5f, tabLineStart + stringIndex * gap + 5f, stringIndex == row && beatCursor == column)
                                }
                            }
                            drawStandard(canvas, beatCursor, x, staffTop)
                            elapsedTicks += duration
                            beatCursor++
                        }
                        val boundaryX = left + (elapsedTicks.toDouble() / usableTicks.toDouble() * (right - left)).toFloat()
                        line.color = 0xFF111111.toInt()
                        canvas.drawLine(boundaryX, staffTop, boundaryX, tabLineStart + (stringCount - 1) * gap + 6f, line)
                        pageText(canvas, (b + 1).toString(), boundaryX + 2f, systemTop - 1f, 7f, false)
                    }
                }
                pageText(canvas, "Earam Tabs", pageLeft + 22f, pageY + pageHeight - 14f, 7f, false)
                pageText(canvas, (pageIndex + 1).toString() + " / " + totalPages, pageRight - 50f, pageY + pageHeight - 14f, 7f, false)
            }
            canvas.restore()

            paint.color = 0xFF1C2023.toInt()
            canvas.drawRect(0f, h - 120f, w, h, paint)
            text(canvas, "DURATION", 14f, h - 94f, 9f, false)
            listOf("𝅝", "𝅗𝅥", "♩", "♪", "𝅘𝅥𝅯").forEachIndexed { index, symbol ->
                chip(canvas, symbol, 12f + index * 42f, h - 78f, 34f, index == durationIndex())
            }
            text(canvas, "MORE", 212f, h - 59f, 9f, false)
            text(canvas, "PICKING", 258f, h - 94f, 9f, false)
            chip(canvas, "↓", 254f, h - 78f, 34f, mode == PickingMode.MANUAL && selectedStroke == StrokeDirection.DOWN)
            chip(canvas, "↑", 292f, h - 78f, 34f, mode == PickingMode.MANUAL && selectedStroke == StrokeDirection.UP)
            chip(canvas, "ALT", 330f, h - 78f, 44f, mode == PickingMode.ALTERNATE)
            chip(canvas, "STR", 378f, h - 78f, 42f, mode == PickingMode.STRUM)
            text(canvas, if (playing) "▶ PLAYING" else "Ready", 365f, h - 58f, 10f, false)

            if (playing) postInvalidateOnAnimation()
            canvas.restore()
        }

        private fun durationIndex(): Int = when (durations[column] ?: 960L) { 3840L -> 0; 1920L -> 1; 960L -> 2; 480L -> 3; 240L -> 4; else -> -1 }

        private fun showDurationMenu() {
            val values = longArrayOf(3840L, 2880L, 1920L, 1440L, 960L, 720L, 480L, 360L, 240L, 120L)
            val labels = arrayOf(
                "Whole  (𝅝)",
                "Dotted Half  (𝅗𝅥.)",
                "Half  (𝅗𝅥)",
                "Dotted Quarter  (♩.)",
                "Quarter  (♩)",
                "Dotted Eighth  (♪.)",
                "Eighth  (♪)",
                "Dotted Sixteenth  (𝅘𝅥𝅯.)",
                "Sixteenth  (𝅘𝅥𝅯)",
                "Thirty-second  (𝅘𝅥𝅰)"
            )
            val current = durations[column] ?: 960L
            val checked = values.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("NOTE DURATION")
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    setDuration(values[which])
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, bold: Boolean) {
            paint.color = 0xFFE5E7E8.toInt()
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(value, x, y, paint)
        }

        private fun pageText(canvas: Canvas, value: String, x: Float, y: Float, size: Float, bold: Boolean) {
            paint.color = 0xFF171717.toInt()
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
            pageText(canvas, value, x, y, 15f, true)
            if (active) {
                paint.style = Paint.Style.STROKE
                paint.color = 0xFFE66A2E.toInt()
                canvas.drawCircle(x + 5f, y - 5f, 11f, paint)
                paint.style = Paint.Style.FILL
            }
        }

        private fun drawStandard(canvas: Canvas, beat: Int, x: Float, top: Float) {
            if (cells.none { it.containsKey(beat) }) return
            val values = cells.indices.filter { cells[it].containsKey(beat) }
                .map { midi(it, cells[it][beat]?.toIntOrNull() ?: 0) }
            if (values.isEmpty()) return
            val average = values.average()
            val y = top + 36f - (average - 60.0) * 2.0
            val ticks = durations[beat] ?: 960L
            val stemUp = average < 66.0
            paint.color = 0xFF171717.toInt()
            if (ticks >= 3840L) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawCircle(x, y.toFloat(), 5.5f, paint)
                paint.style = Paint.Style.FILL
            } else {
                canvas.drawCircle(x, y.toFloat(), 5.5f, paint)
            }
            line.color = 0xFFE5E7E8.toInt()
            line.strokeWidth = 2f
            val stemX = if (stemUp) x + 5.5f else x - 5.5f
            val stemEnd = if (stemUp) y - 25.0 else y + 25.0
            canvas.drawLine(stemX, y.toFloat(), stemX, stemEnd.toFloat(), line)
            val flags = when {
                ticks <= 240L -> 2
                ticks <= 480L -> 1
                else -> 0
            }
            if (flags > 0) {
                for (f in 0 until flags) {
                    val yy = if (stemUp) y - 25.0 + f * 7.0 else y + 25.0 - f * 7.0
                    canvas.drawLine(stemX, yy.toFloat(), stemX + if (stemUp) 8f else -8f, (yy + if (stemUp) 5.0 else -5.0).toFloat(), line)
                }
            }
            if (ticks == 2880L || ticks == 1440L || ticks == 720L || ticks == 360L) {
                paint.color = 0xFFE5E7E8.toInt()
                canvas.drawCircle(x + if (stemUp) 9f else -9f, y.toFloat(), 2f, paint)
            }
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
            val capacity = barTicks()
            val start = measureStarts().lastOrNull { it <= column } ?: 0
            var used = 0L
            var cursor = start
            while (cursor < column) {
                used += durations[cursor] ?: 960L
                cursor++
            }
            val old = durations[column] ?: 960L
            val remaining = capacity - used + old
            if (value > remaining) {
                Toast.makeText(this@MainActivity, "That duration does not fit in " + timeSig + ".", Toast.LENGTH_SHORT).show()
                return
            }
            remember()
            durations[column] = value
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val d = resources.displayMetrics.density
            val x = event.x / d
            val y = event.y / d
            val w = width.toFloat() / d
            val h = height.toFloat() / d

            val pageLeft = 12f
            val pageRight = w - 12f
            val systemsPerPage = 4
            val barsPerSystem = 4
            val bars = measureStarts()
            val barsPerPage = systemsPerPage * barsPerSystem
            val totalPages = ((bars.size + barsPerPage - 1) / barsPerPage).coerceAtLeast(1)
            val systemHeight = 132f + stringCount * 2.5f
            val pageHeight = systemsPerPage * systemHeight + 58f
            val pageGap = 24f
            val firstPageTop = 158f
            val contentBottom = h - 126f
            val maxScroll = maxOf(0f, firstPageTop + totalPages * pageHeight + (totalPages - 1) * pageGap - contentBottom)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x
                    downY = y
                    downYRaw = y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = y - downY
                    if (kotlin.math.abs(dy) > touchSlop) dragging = true
                    if (dragging && downYRaw >= 151f && downYRaw <= contentBottom) {
                        scoreScrollY = (scoreScrollY - dy).coerceIn(0f, maxScroll)
                        downY = y
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        dragging = false
                        return true
                    }
                }
                else -> return true
            }

            if (y in 64f..118f) {
                val toolWidth = (w - 16f) / 13f
                val index = ((x - 8f) / toolWidth).toInt()
                when (index) {
                    0 -> showFileMenu()
                    3 -> showChordDialog()
                    4 -> importTab()
                    5 -> if (undo.isNotEmpty()) { redo.addLast(snapshot()); restore(undo.removeLast()); invalidate() }
                    6 -> if (redo.isNotEmpty()) { undo.addLast(snapshot()); restore(redo.removeLast()); invalidate() }
                    7 -> saveProject()
                    8 -> moveColumn(-1)
                    9 -> moveColumn(1)
                    10 -> startPlayback()
                    11 -> stopPlayback()
                    12 -> { loop = !loop; invalidate() }
                }
                return true
            }

            if (y >= h - 120f) {
                when {
                    x in 12f..58f -> setDuration(3840L)
                    x in 64f..110f -> setDuration(1920L)
                    x in 116f..162f -> setDuration(960L)
                    x in 168f..204f -> setDuration(480L)
                    x in 206f..250f -> showDurationMenu()
                    x in 254f..288f -> { selectedStroke = StrokeDirection.DOWN; mode = PickingMode.MANUAL; remember(); assignPicking() }
                    x in 292f..326f -> { selectedStroke = StrokeDirection.UP; mode = PickingMode.MANUAL; remember(); assignPicking() }
                    x in 330f..374f -> { mode = PickingMode.ALTERNATE; remember(); assignPicking() }
                    x in 378f..420f -> { mode = PickingMode.STRUM; remember(); assignPicking() }
                }
                return true
            }

            if (y >= 151f && y <= contentBottom) {
                val scoreY = y + scoreScrollY
                val pageIndex = ((scoreY - firstPageTop) / (pageHeight + pageGap)).toInt()
                if (pageIndex !in 0 until totalPages) return true
                val pageY = firstPageTop + pageIndex * (pageHeight + pageGap)
                val withinPage = scoreY - pageY
                if (withinPage < 40f || withinPage > pageHeight) return true
                val system = ((withinPage - 42f) / systemHeight).toInt().coerceIn(0, systemsPerPage - 1)
                val barIndex = pageIndex * barsPerPage + system * barsPerSystem
                if (barIndex >= bars.size) return true
                val systemTop = 42f + system * systemHeight
                val tabTop = systemTop + 47f
                val gap = if (stringCount > 6) 12f else 14f
                val left = pageLeft + 22f
                val right = pageRight - 22f
                val firstBar = bars[barIndex]
                val lastBar = minOf(barIndex + barsPerSystem - 1, bars.size - 1)
                val lastBarEnd = if (lastBar + 1 < bars.size) bars[lastBar + 1] else columnCount
                var totalTicks = 0L
                for (c in firstBar until lastBarEnd) totalTicks += durations[c] ?: 960L
                totalTicks = totalTicks.coerceAtLeast(1L)
                if (x in left..right) {
                    val targetTicks = (((x - left) / (right - left)) * totalTicks).toLong()
                    var elapsed = 0L
                    var beat = firstBar
                    while (beat < lastBarEnd) {
                        val dTicks = durations[beat] ?: 960L
                        if (targetTicks < elapsed + dTicks) break
                        elapsed += dTicks
                        beat++
                    }
                    beat = beat.coerceIn(firstBar, lastBarEnd - 1)
                    val stringIndex = ((scoreY - pageY - tabTop - 8f + gap / 2f) / gap).toInt().coerceIn(0, stringCount - 1)
                    column = beat
                    row = stringIndex
                    savedColumn = beat
                    savedRow = stringIndex
                    editFretAt(stringIndex, beat)
                    invalidate()
                }
            }
            return true
        }
    }
}