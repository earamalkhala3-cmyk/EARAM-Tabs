package com.earam.tabs

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import com.earam.tabs.music.MusicalTimeline
import com.earam.tabs.music.Tempo
import com.earam.tabs.music.TimeSignature

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val timeline = MusicalTimeline(Tempo(120.0), TimeSignature(4, 4))
        setContentView(EditorView(this, timeline))
    }

    private class EditorView(context: Activity, private val timeline: MusicalTimeline) : View(context) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strings = arrayOf("e", "B", "G", "D", "A", "E")
        private val cells = Array(6) { mutableMapOf<Int, String>() }
        private val strokes = mutableMapOf<Int, String>()
        private var selectedStroke = "↓"
        private var selectedTool = "NOTE"
        private var selectedColumn = 0
        private var selectedString = 0

        init {
            bg.color = 0xFF111315.toInt()
            line.color = 0xFF34383C.toInt()
            line.strokeWidth = 1f
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            c.drawColor(0xFF111315.toInt())
            val w = width.toFloat()
            val h = height.toFloat()
            bg.color = 0xFF191C1F.toInt(); c.drawRect(0f, 0f, w, 64f, bg)
            text.color = 0xFFF4F4F4.toInt(); text.textSize = 22f
            text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            c.drawText("Earam", 24f, 40f, text)
            text.color = 0xFF9FA5AA.toInt(); text.textSize = 13f; text.typeface = Typeface.DEFAULT
            c.drawText("UNTITLED", 112f, 39f, text); c.drawText("120 BPM", w - 170f, 39f, text); c.drawText("4/4", w - 90f, 39f, text)

            bg.color = 0xFF202428.toInt(); c.drawRect(0f, 64f, w, 116f, bg)
            drawTool(c, "NOTE", 18f, 78f, selectedTool == "NOTE")
            drawTool(c, "REST", 88f, 78f, selectedTool == "REST")
            drawTool(c, "CHORD", 154f, 78f, selectedTool == "CHORD")
            drawTool(c, "BAR", 232f, 78f, selectedTool == "BAR")
            drawTool(c, "UNDO", w - 150f, 78f, false); drawTool(c, "REDO", w - 78f, 78f, false)

            val scoreTop = 116f; bg.color = 0xFF151719.toInt(); c.drawRect(0f, scoreTop, w, h - 112f, bg)
            text.color = 0xFF8D9499.toInt(); text.textSize = 12f; c.drawText("GUITAR  •  STANDARD + TAB", 24f, scoreTop + 30f, text)
            text.color = 0xFFE6E7E8.toInt(); text.textSize = 15f; c.drawText("6-string · Standard tuning", 24f, scoreTop + 51f, text)

            val gridTop = scoreTop + 82f; val rowGap = 30f; val labelX = 24f; val gridX = 78f; val gridRight = w - 24f
            text.color = 0xFFB8BDC1.toInt(); text.textSize = 12f
            for (i in strings.indices) { val y = gridTop + i * rowGap; c.drawText(strings[i], labelX, y + 4f, text); c.drawLine(gridX, y, gridRight, y, line) }
            val columns = 8
            val colWidth = (gridRight - gridX) / columns
            for (i in 0..columns) { val x = gridX + i * colWidth; line.color = if (i % 4 == 0) 0xFF555B60.toInt() else 0xFF292D31.toInt(); line.strokeWidth = if (i % 4 == 0) 2f else 1f; c.drawLine(x, gridTop - 18f, x, gridTop + 5 * rowGap + 12f, line) }
            line.color = 0xFF34383C.toInt(); line.strokeWidth = 1f

            for (s in 0..5) for ((column, value) in cells[s]) drawTabNumber(c, value, gridX + column * colWidth + colWidth / 2f - 5f, gridTop + s * rowGap + 6f, s == selectedString && column == selectedColumn)
            for ((column, stroke) in strokes) { text.color = 0xFFD8DADD.toInt(); text.textSize = 17f; c.drawText(stroke, gridX + column * colWidth + colWidth / 2f - 5f, gridTop - 28f, text) }

            bg.color = 0xFF1C2023.toInt(); c.drawRect(0f, h - 112f, w, h, bg)
            text.color = 0xFF8F969B.toInt(); text.textSize = 11f; c.drawText("PICKING", 20f, h - 84f, text)
            drawControl(c, "↓", 20f, h - 68f, selectedStroke == "↓"); drawControl(c, "↑", 58f, h - 68f, selectedStroke == "↑"); drawControl(c, "ALT", 96f, h - 68f, false); drawControl(c, "STRUM", 142f, h - 68f, false)
            text.color = 0xFF8F969B.toInt(); text.textSize = 11f; c.drawText("SELECTED", w - 190f, h - 84f, text)
            text.color = 0xFFE5E7E8.toInt(); text.textSize = 14f; c.drawText("String ${selectedString + 1}  •  Beat ${selectedColumn + 1}", w - 190f, h - 58f, text)
        }

        private fun drawTool(c: Canvas, label: String, x: Float, y: Float, active: Boolean) { text.color = if (active) 0xFFFFFFFF.toInt() else 0xFFAEB4B8.toInt(); text.textSize = 12f; text.typeface = Typeface.create(Typeface.SANS_SERIF, if (active) Typeface.BOLD else Typeface.NORMAL); c.drawText(label, x, y + 22f, text) }
        private fun drawControl(c: Canvas, label: String, x: Float, y: Float, active: Boolean) { bg.color = if (active) 0xFF343A3F.toInt() else 0xFF252A2E.toInt(); val bw = if (label == "STRUM") 58f else 32f; c.drawRoundRect(x, y, x + bw, y + 30f, 6f, 6f, bg); text.color = 0xFFE0E3E5.toInt(); text.textSize = 13f; c.drawText(label, x + 9f, y + 20f, text) }
        private fun drawTabNumber(c: Canvas, value: String, x: Float, y: Float, selected: Boolean) { text.color = if (selected) 0xFFFFFFFF.toInt() else 0xFFF0F1F2.toInt(); text.textSize = 17f; text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); c.drawText(value, x, y, text); if (selected) { line.color = 0xFF777D82.toInt(); line.strokeWidth = 2f; c.drawCircle(x + 5f, y - 5f, 13f, line) }; text.typeface = Typeface.DEFAULT }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            val x = event.x; val y = event.y; val h = height.toFloat()
            if (y in 64f..116f) { selectedTool = when { x < 78f -> "NOTE"; x < 145f -> "REST"; x < 225f -> "CHORD"; else -> selectedTool }; invalidate(); return true }
            if (y > h - 112f) { if (x < 55f) selectedStroke = "↓" else if (x < 92f) selectedStroke = "↑" else if (x < 138f) selectedStroke = "ALT"; invalidate(); return true }
            val gridTop = 198f; val gridX = 78f; val gridRight = width.toFloat() - 24f; val rowGap = 30f; val colWidth = (gridRight - gridX) / 8f
            if (y >= gridTop - 20f && y <= gridTop + 5 * rowGap + 20f && x >= gridX && x <= gridRight) {
                selectedString = ((y - gridTop + rowGap / 2f) / rowGap).toInt().coerceIn(0, 5)
                selectedColumn = ((x - gridX) / colWidth).toInt().coerceIn(0, 7)
                if (selectedTool == "NOTE") cells[selectedString][selectedColumn] = ((selectedString + selectedColumn) % 5).toString()
                else if (selectedTool == "REST") cells[selectedString].remove(selectedColumn)
                strokes[selectedColumn] = selectedStroke
                invalidate(); return true
            }
            return true
        }
    }
}
