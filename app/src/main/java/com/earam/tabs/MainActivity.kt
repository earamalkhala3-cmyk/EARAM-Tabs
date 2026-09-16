package com.earam.tabs

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Button
import com.earam.tabs.music.PickingEngine
import com.earam.tabs.music.PickingMode
import com.earam.tabs.music.StrumPattern
import com.earam.tabs.music.StrokeDirection

class MainActivity : Activity() {
    private var projectName = "UNTITLED"
    private var instrument = "Guitar"
    private var stringCount = 6
    private var tuning = "Standard"
    private var bpm = 120

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun showHome() {
        setContentView(HomeView(this))
    }

    private fun newProject() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 12, 48, 0) }
        val name = EditText(this).apply { hint = "Project name"; setText(projectName) }
        val instrumentSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Guitar", "Bass")) }
        val stringsSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("4", "5", "6", "7")); setSelection(2) }
        val tuningSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Standard", "Drop D", "Drop C", "Custom")) }
        val bpmEdit = EditText(this).apply { hint = "BPM"; inputType = InputType.TYPE_CLASS_NUMBER; setText(bpm.toString()) }
        listOf(TextView(this).apply { text = "Instrument" }, instrumentSpinner, TextView(this).apply { text = "Strings" }, stringsSpinner, TextView(this).apply { text = "Tuning" }, tuningSpinner, bpmEdit).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("New Earam Project").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Create") { _, _ ->
            projectName = name.text.toString().ifBlank { "UNTITLED" }
            instrument = instrumentSpinner.selectedItem.toString()
            stringCount = stringsSpinner.selectedItem.toString().toInt()
            tuning = tuningSpinner.selectedItem.toString()
            bpm = bpmEdit.text.toString().toIntOrNull()?.coerceIn(30, 300) ?: 120
            setContentView(EditorView(this))
        }.show()
    }

    private inner class HomeView(private val activity: Activity) : View(activity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            c.drawColor(0xFF0E1012.toInt())
            p.color = 0xFFF1F2F3.toInt(); p.textSize = 38f; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText("Earam", 34f, 90f, p)
            p.color = 0xFF8F969B.toInt(); p.textSize = 14f; p.typeface = Typeface.DEFAULT
            c.drawText("GUITAR / BASS TAB & NOTATION", 36f, 119f, p)
            button(c, "NEW PROJECT", 36f, 170f, width - 72f, 58f)
            button(c, "OPEN PROJECT", 36f, 244f, width - 72f, 58f)
            p.color = 0xFF676E73.toInt(); p.textSize = 12f
            c.drawText("Recent", 36f, 346f, p)
            p.color = 0xFFDDE0E2.toInt(); p.textSize = 16f
            c.drawText(projectName, 36f, 378f, p)
        }
        private fun button(c: Canvas, label: String, x: Float, y: Float, w: Float, h: Float) {
            p.color = 0xFF202529.toInt(); c.drawRoundRect(x, y, x + w, y + h, 10f, 10f, p)
            p.color = 0xFFF0F1F2.toInt(); p.textSize = 15f; p.typeface = Typeface.DEFAULT_BOLD; c.drawText(label, x + 18f, y + 36f, p)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP && e.y in 160f..240f) { newProject(); return true }
            return true
        }
    }

    private inner class EditorView(private val activity: Activity) : View(activity) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cells = Array(stringCount) { mutableMapOf<Int, String>() }
        private val strokes = mutableMapOf<Int, StrokeDirection>()
        private var selectedStroke = StrokeDirection.DOWN
        private var pickingMode = PickingMode.MANUAL
        private val strumPattern = StrumPattern.fromText("↓ ↓ ↑ ↑ ↓ ↑")
        private var selectedTool = "NOTE"
        private var selectedColumn = 0
        private var selectedString = 0
        private val undoStack = ArrayDeque<EditorState>()
        private val redoStack = ArrayDeque<EditorState>()

        data class EditorState(val notes: Array<Map<Int, String>>, val strokes: Map<Int, StrokeDirection>)

        init { bg.color = 0xFF111315.toInt(); line.color = 0xFF34383C.toInt(); line.strokeWidth = 1f }

        override fun onDraw(c: Canvas) {
            c.drawColor(0xFF111315.toInt())
            val w = width.toFloat(); val h = height.toFloat()
            bg.color = 0xFF191C1F.toInt(); c.drawRect(0f, 0f, w, 66f, bg)
            text.color = 0xFFF4F4F4.toInt(); text.textSize = 22f; text.typeface = Typeface.DEFAULT_BOLD; c.drawText("Earam", 20f, 41f, text)
            text.color = 0xFF9FA5AA.toInt(); text.textSize = 12f; text.typeface = Typeface.DEFAULT
            c.drawText(projectName, 108f, 40f, text); c.drawText("$bpm BPM", w - 120f, 40f, text)
            bg.color = 0xFF202428.toInt(); c.drawRect(0f, 66f, w, 122f, bg)
            tool(c, "NOTE", 16f, 78f, selectedTool == "NOTE"); tool(c, "REST", 74f, 78f, selectedTool == "REST"); tool(c, "CHORD", 130f, 78f, selectedTool == "CHORD"); tool(c, "UNDO", w - 124f, 78f, false); tool(c, "REDO", w - 62f, 78f, false)
            text.color = 0xFF8D9499.toInt(); text.textSize = 11f; c.drawText("$instrument  •  $stringCount-string  •  $tuning", 20f, 148f, text)
            val gridTop = 190f; val rowGap = if (stringCount > 6) 27f else 30f; val gridX = 72f; val gridRight = w - 18f; val columns = 8; val colWidth = (gridRight - gridX) / columns
            text.color = 0xFFB8BDC1.toInt(); text.textSize = 12f
            for (s in 0 until stringCount) { val yy = gridTop + s * rowGap; c.drawText(stringLabel(s), 18f, yy + 4f, text); line.color = 0xFF34383C.toInt(); line.strokeWidth = 1f; c.drawLine(gridX, yy, gridRight, yy, line) }
            for (i in 0..columns) { val xx = gridX + i * colWidth; line.color = if (i % 4 == 0) 0xFF555B60.toInt() else 0xFF292D31.toInt(); line.strokeWidth = if (i % 4 == 0) 2f else 1f; c.drawLine(xx, gridTop - 22f, xx, gridTop + (stringCount - 1) * rowGap + 12f, line) }
            for (s in 0 until stringCount) for ((column, value) in cells[s]) drawTab(c, value, gridX + column * colWidth + colWidth / 2f - 5f, gridTop + s * rowGap + 6f, s == selectedString && column == selectedColumn)
            text.color = 0xFFD8DADD.toInt(); text.textSize = 17f
            for (column in 0 until columns) strokes[column]?.let { c.drawText(if (it == StrokeDirection.DOWN) "↓" else "↑", gridX + column * colWidth + colWidth / 2f - 5f, gridTop - 30f, text) }
            bg.color = 0xFF1C2023.toInt(); c.drawRect(0f, h - 116f, w, h, bg)
            text.color = 0xFF8F969B.toInt(); text.textSize = 10f; c.drawText("PICKING", 16f, h - 88f, text)
            control(c, "↓", 16f, h - 70f, 34f, pickingMode == PickingMode.MANUAL && selectedStroke == StrokeDirection.DOWN)
            control(c, "↑", 56f, h - 70f, 34f, pickingMode == PickingMode.MANUAL && selectedStroke == StrokeDirection.UP)
            control(c, "ALT", 96f, h - 70f, 42f, pickingMode == PickingMode.ALTERNATE)
            control(c, "STRUM", 146f, h - 70f, 58f, pickingMode == PickingMode.STRUM)
            text.color = 0xFFE5E7E8.toInt(); text.textSize = 13f; c.drawText("↓ ↓ ↑ ↑ ↓ ↑", 220f, h - 50f, text)
            text.color = 0xFF8F969B.toInt(); text.textSize = 11f; c.drawText("String ${selectedString + 1} • Beat ${selectedColumn + 1}", w - 155f, h - 22f, text)
        }

        private fun stringLabel(s: Int): String = when {
            stringCount == 7 -> arrayOf("e", "B", "G", "D", "A", "E", "B")[s]
            stringCount == 6 -> arrayOf("e", "B", "G", "D", "A", "E")[s]
            stringCount == 5 -> arrayOf("G", "D", "A", "E", "B")[s]
            else -> arrayOf("G", "D", "A", "E")[s]
        }
        private fun snapshot() = EditorState(Array(stringCount) { s -> cells[s].toMap() }, strokes.toMap())
        private fun restore(state: EditorState) { for (s in 0 until stringCount) { cells[s].clear(); cells[s].putAll(state.notes[s]) }; strokes.clear(); strokes.putAll(state.strokes) }
        private fun remember() { undoStack.addLast(snapshot()); if (undoStack.size > 50) undoStack.removeFirst(); redoStack.clear() }
        private fun undo() { if (undoStack.isEmpty()) return; redoStack.addLast(snapshot()); restore(undoStack.removeLast()); invalidate() }
        private fun redo() { if (redoStack.isEmpty()) return; undoStack.addLast(snapshot()); restore(redoStack.removeLast()); invalidate() }
        private fun assignStroke(column: Int) { when (pickingMode) { PickingMode.MANUAL -> strokes[column] = selectedStroke; PickingMode.ALTERNATE -> PickingEngine.alternate(8, selectedStroke).forEachIndexed { i, d -> strokes[i] = d }; PickingMode.STRUM -> PickingEngine.applyPattern(strumPattern, 8).forEachIndexed { i, d -> strokes[i] = d } } }
        private fun editFret(s: Int, col: Int) {
            val input = EditText(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "0–24"; setText(cells[s][col] ?: "") }
            AlertDialog.Builder(activity).setTitle("Fret ${stringLabel(s)} • Beat ${col + 1}").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Apply") { _, _ ->
                val value = input.text.toString().trim(); remember(); if (value.isEmpty()) cells[s].remove(col) else cells[s][col] = value.toIntOrNull()?.coerceIn(0, 24)?.toString() ?: "0"; assignStroke(col); invalidate()
            }.show()
        }
        private fun tool(c: Canvas, label: String, x: Float, y: Float, active: Boolean) { text.color = if (active) 0xFFFFFFFF.toInt() else 0xFFAEB4B8.toInt(); text.textSize = 12f; text.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; c.drawText(label, x, y + 23f, text) }
        private fun control(c: Canvas, label: String, x: Float, y: Float, bw: Float, active: Boolean) { bg.color = if (active) 0xFF3A4248.toInt() else 0xFF252A2E.toInt(); c.drawRoundRect(x, y, x + bw, y + 30f, 6f, 6f, bg); text.color = 0xFFE0E3E5.toInt(); text.textSize = 12f; c.drawText(label, x + 9f, y + 20f, text) }
        private fun drawTab(c: Canvas, value: String, x: Float, y: Float, selected: Boolean) { text.color = 0xFFF0F1F2.toInt(); text.textSize = 16f; text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); c.drawText(value, x, y, text); if (selected) { line.color = 0xFF888F95.toInt(); line.strokeWidth = 2f; c.drawCircle(x + 5f, y - 5f, 12f, line) } }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_UP) return true
            val x = e.x; val y = e.y; val h = height.toFloat(); val w = width.toFloat()
            if (y in 66f..122f) { if (x >= w - 140f && x < w - 70f) undo() else if (x >= w - 70f) redo() else if (x < 65f) selectedTool = "NOTE" else if (x < 120f) selectedTool = "REST" else if (x < 190f) selectedTool = "CHORD"; invalidate(); return true }
            if (y > h - 116f) { when { x < 52f -> { pickingMode = PickingMode.MANUAL; selectedStroke = StrokeDirection.DOWN }; x < 92f -> { pickingMode = PickingMode.MANUAL; selectedStroke = StrokeDirection.UP }; x < 142f -> pickingMode = PickingMode.ALTERNATE; x < 215f -> pickingMode = PickingMode.STRUM }; remember(); assignStroke(selectedColumn); invalidate(); return true }
            val gridTop = 190f; val rowGap = if (stringCount > 6) 27f else 30f; val gridX = 72f; val gridRight = w - 18f; val colWidth = (gridRight - gridX) / 8f
            if (y >= gridTop - 20f && y <= gridTop + (stringCount - 1) * rowGap + 20f && x >= gridX && x <= gridRight) {
                selectedString = ((y - gridTop + rowGap / 2f) / rowGap).toInt().coerceIn(0, stringCount - 1); selectedColumn = ((x - gridX) / colWidth).toInt().coerceIn(0, 7)
                when (selectedTool) { "NOTE" -> editFret(selectedString, selectedColumn); "REST" -> { remember(); cells[selectedString].remove(selectedColumn); invalidate() }; "CHORD" -> { remember(); if (cells[selectedString].containsKey(selectedColumn)) cells[selectedString].remove(selectedColumn) else cells[selectedString][selectedColumn] = "0"; assignStroke(selectedColumn); invalidate() } }
                return true
            }
            return true
        }
    }
}
