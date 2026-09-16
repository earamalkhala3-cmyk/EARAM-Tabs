package com.earam.tabs

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.ArrayMap
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import com.earam.tabs.music.PickingEngine
import com.earam.tabs.music.PickingMode
import com.earam.tabs.music.StrumPattern
import com.earam.tabs.music.StrokeDirection

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(EditorView(this))
    }

    private class EditorView(private val activity: Activity) : View(activity) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strings = arrayOf("e", "B", "G", "D", "A", "E")
        private val cells = Array(6) { mutableMapOf<Int, String>() }
        private val strokes = mutableMapOf<Int, StrokeDirection>()
        private var selectedStroke = StrokeDirection.DOWN
        private var pickingMode = PickingMode.MANUAL
        private var strumPattern = StrumPattern.fromText("↓ ↓ ↑ ↑ ↓ ↑")
        private var selectedTool = "NOTE"
        private var selectedColumn = 0
        private var selectedString = 0
        private val undoStack = ArrayDeque<Array<ArrayMap<Int, String>>>()
        private val redoStack = ArrayDeque<Array<ArrayMap<Int, String>>>()

        init { bg.color = 0xFF111315.toInt(); line.color = 0xFF34383C.toInt(); line.strokeWidth = 1f }

        override fun onDraw(c: Canvas) {
            super.onDraw(c); c.drawColor(0xFF111315.toInt())
            val w = width.toFloat(); val h = height.toFloat()
            bg.color = 0xFF191C1F.toInt(); c.drawRect(0f, 0f, w, 64f, bg)
            text.color = 0xFFF4F4F4.toInt(); text.textSize = 22f; text.typeface = Typeface.DEFAULT_BOLD; c.drawText("Earam", 24f, 40f, text)
            text.color = 0xFF9FA5AA.toInt(); text.textSize = 13f; text.typeface = Typeface.DEFAULT
            c.drawText("UNTITLED", 112f, 39f, text); c.drawText("120 BPM", w - 170f, 39f, text); c.drawText("4/4", w - 90f, 39f, text)
            bg.color = 0xFF202428.toInt(); c.drawRect(0f, 64f, w, 116f, bg)
            drawTool(c, "NOTE", 18f, 78f, selectedTool == "NOTE"); drawTool(c, "REST", 88f, 78f, selectedTool == "REST"); drawTool(c, "CHORD", 154f, 78f, selectedTool == "CHORD"); drawTool(c, "BAR", 232f, 78f, selectedTool == "BAR")
            drawTool(c, "UNDO", w - 150f, 78f, false); drawTool(c, "REDO", w - 78f, 78f, false)
            val scoreTop = 116f; bg.color = 0xFF151719.toInt(); c.drawRect(0f, scoreTop, w, h - 112f, bg)
            text.color = 0xFF8D9499.toInt(); text.textSize = 12f; c.drawText("GUITAR  •  STANDARD + TAB", 24f, scoreTop + 30f, text)
            text.color = 0xFFE6E7E8.toInt(); text.textSize = 15f; c.drawText("6-string · Standard tuning", 24f, scoreTop + 51f, text)
            val gridTop = scoreTop + 82f; val rowGap = 30f; val labelX = 24f; val gridX = 78f; val gridRight = w - 24f; val columns = 8; val colWidth = (gridRight - gridX) / columns
            text.color = 0xFFB8BDC1.toInt(); text.textSize = 12f
            for (i in strings.indices) { val yy = gridTop + i * rowGap; c.drawText(strings[i], labelX, yy + 4f, text); c.drawLine(gridX, yy, gridRight, yy, line) }
            for (i in 0..columns) { val xx = gridX + i * colWidth; line.color = if (i % 4 == 0) 0xFF555B60.toInt() else 0xFF292D31.toInt(); line.strokeWidth = if (i % 4 == 0) 2f else 1f; c.drawLine(xx, gridTop - 18f, xx, gridTop + 5 * rowGap + 12f, line) }
            line.color = 0xFF34383C.toInt(); line.strokeWidth = 1f
            for (s in 0..5) for ((column, value) in cells[s]) drawTabNumber(c, value, gridX + column * colWidth + colWidth / 2f - 5f, gridTop + s * rowGap + 6f, s == selectedString && column == selectedColumn)
            for (column in 0 until columns) strokes[column]?.let { stroke -> text.color = 0xFFD8DADD.toInt(); text.textSize = 17f; c.drawText(if (stroke == StrokeDirection.DOWN) "↓" else "↑", gridX + column * colWidth + colWidth / 2f - 5f, gridTop - 28f, text) }
            if (selectedTool == "CHORD") for (column in 0 until columns) { val active = (0..5).filter { cells[it].containsKey(column) }; if (active.size >= 2) { line.color = 0xFF6F777D.toInt(); line.strokeWidth = 2f; val xx = gridX + column * colWidth + colWidth / 2f + 10f; c.drawLine(xx, gridTop + active.first() * rowGap - 9f, xx, gridTop + active.last() * rowGap + 9f, line) } }
            bg.color = 0xFF1C2023.toInt(); c.drawRect(0f, h - 112f, w, h, bg)
            text.color = 0xFF8F969B.toInt(); text.textSize = 11f; c.drawText("PICKING", 20f, h - 84f, text)
            drawControl(c, "↓", 20f, h - 68f, pickingMode == PickingMode.MANUAL && selectedStroke == StrokeDirection.DOWN); drawControl(c, "↑", 58f, h - 68f, pickingMode == PickingMode.MANUAL && selectedStroke == StrokeDirection.UP); drawControl(c, "ALT", 96f, h - 68f, pickingMode == PickingMode.ALTERNATE); drawControl(c, "STRUM", 142f, h - 68f, pickingMode == PickingMode.STRUM)
            text.color = 0xFF8F969B.toInt(); text.textSize = 11f; c.drawText("PATTERN", 215f, h - 84f, text); text.color = 0xFFE5E7E8.toInt(); text.textSize = 14f; c.drawText("↓ ↓ ↑ ↑ ↓ ↑", 215f, h - 58f, text)
            text.color = 0xFF8F969B.toInt(); text.textSize = 11f; c.drawText("SELECTED", w - 190f, h - 84f, text); text.color = 0xFFE5E7E8.toInt(); text.textSize = 14f; c.drawText("String ${selectedString + 1}  •  Beat ${selectedColumn + 1}", w - 190f, h - 58f, text)
        }

        private fun snapshot(): Array<ArrayMap<Int, String>> = Array(6) { s -> ArrayMap<Int, String>().also { it.putAll(cells[s]) } }
        private fun restore(state: Array<ArrayMap<Int, String>>) { for (s in 0..5) { cells[s].clear(); cells[s].putAll(state[s]) } }
        private fun rememberEdit() { undoStack.addLast(snapshot()); if (undoStack.size > 50) undoStack.removeFirst(); redoStack.clear() }
        private fun undo() { if (undoStack.isEmpty()) return; redoStack.addLast(snapshot()); restore(undoStack.removeLast()); invalidate() }
        private fun redo() { if (redoStack.isEmpty()) return; undoStack.addLast(snapshot()); restore(redoStack.removeLast()); invalidate() }
        private fun assignStroke(column: Int) { when (pickingMode) { PickingMode.MANUAL -> strokes[column] = selectedStroke; PickingMode.ALTERNATE -> PickingEngine.alternate(8, selectedStroke).forEachIndexed { i, d -> strokes[i] = d }; PickingMode.STRUM -> PickingEngine.applyPattern(strumPattern, 8).forEachIndexed { i, d -> strokes[i] = d } } }
        private fun editFret(string: Int, column: Int) {
            val input = EditText(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "0–24"; setText(cells[string][column] ?: "") }
            AlertDialog.Builder(activity).setTitle("Fret ${strings[string]} • Beat ${column + 1}").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Apply") { _, _ ->
                val value = input.text.toString().trim(); rememberEdit(); if (value.isEmpty()) cells[string].remove(column) else cells[string][column] = value.toIntOrNull()?.coerceIn(0, 24)?.toString() ?: "0"; invalidate()
            }.show()
        }
        private fun drawTool(c: Canvas, label: String, x: Float, y: Float, active: Boolean) { text.color = if (active) 0xFFFFFFFF.toInt() else 0xFFAEB4B8.toInt(); text.textSize = 12f; text.typeface = Typeface.create(Typeface.SANS_SERIF, if (active) Typeface.BOLD else Typeface.NORMAL); c.drawText(label, x, y + 22f, text) }
        private fun drawControl(c: Canvas, label: String, x: Float, y: Float, active: Boolean) { bg.color = if (active) 0xFF343A3F.toInt() else 0xFF252A2E.toInt(); val bw = if (label == "STRUM") 58f else 32f; c.drawRoundRect(x, y, x + bw, y + 30f, 6f, 6f, bg); text.color = 0xFFE0E3E5.toInt(); text.textSize = 13f; c.drawText(label, x + 9f, y + 20f, text) }
        private fun drawTabNumber(c: Canvas, value: String, x: Float, y: Float, selected: Boolean) { text.color = if (selected) 0xFFFFFFFF.toInt() else 0xFFF0F1F2.toInt(); text.textSize = 17f; text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); c.drawText(value, x, y, text); if (selected) { line.color = 0xFF777D82.toInt(); line.strokeWidth = 2f; c.drawCircle(x + 5f, y - 5f, 13f, line) }; text.typeface = Typeface.DEFAULT }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            val x = event.x; val y = event.y; val h = height.toFloat()
            if (y in 64f..116f) {
                if (x >= width - 175f && x < width - 82f) undo() else if (x >= width - 82f) redo() else { selectedTool = when { x < 78f -> "NOTE"; x < 145f -> "REST"; x < 225f -> "CHORD"; else -> selectedTool }; invalidate() }
                return true
            }
            if (y > h - 112f) { when { x < 55f -> { pickingMode = PickingMode.MANUAL; selectedStroke = StrokeDirection.DOWN }; x < 92f -> { pickingMode = PickingMode.MANUAL; selectedStroke = StrokeDirection.UP }; x < 138f -> pickingMode = PickingMode.ALTERNATE; x < 205f -> pickingMode = PickingMode.STRUM }; assignStroke(selectedColumn); invalidate(); return true }
            val gridTop = 198f; val gridX = 78f; val gridRight = width.toFloat() - 24f; val rowGap = 30f; val colWidth = (gridRight - gridX) / 8f
            if (y >= gridTop - 20f && y <= gridTop + 5 * rowGap + 20f && x >= gridX && x <= gridRight) {
                selectedString = ((y - gridTop + rowGap / 2f) / rowGap).toInt().coerceIn(0, 5); selectedColumn = ((x - gridX) / colWidth).toInt().coerceIn(0, 7)
                when (selectedTool) { "NOTE" -> editFret(selectedString, selectedColumn); "REST" -> { rememberEdit(); cells[selectedString].remove(selectedColumn) }; "CHORD" -> { rememberEdit(); if (cells[selectedString].containsKey(selectedColumn)) cells[selectedString].remove(selectedColumn) else cells[selectedString][selectedColumn] = "0" } }
                assignStroke(selectedColumn); invalidate(); return true
            }
            return true
        }
    }
}
