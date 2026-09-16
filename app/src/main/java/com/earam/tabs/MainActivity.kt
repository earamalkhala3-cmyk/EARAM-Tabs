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
import com.earam.tabs.music.PickingEngine
import com.earam.tabs.music.PickingMode
import com.earam.tabs.music.StrumPattern
import com.earam.tabs.music.StrokeDirection

data class EditorState(val notes: Array<Map<Int, String>>, val strokes: Map<Int, StrokeDirection>)

class MainActivity : Activity() {
    private var projectName = "UNTITLED"
    private var instrument = "Guitar"
    private var stringCount = 6
    private var tuning = "Standard"
    private var bpm = 120

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); showHome() }
    private fun showHome() { setContentView(HomeView()) }

    private fun newProject() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 0) }
        val name = EditText(this).apply { hint = "Project name"; setText(projectName) }
        val instrumentSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Guitar", "Bass")) }
        val stringsSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("4", "5", "6", "7")); setSelection(2) }
        val tuningSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Standard", "Drop D", "Drop C", "Custom")) }
        val bpmEdit = EditText(this).apply { hint = "BPM"; inputType = InputType.TYPE_CLASS_NUMBER; setText("120") }
        listOf(TextView(this).apply { text = "Instrument" }, instrumentSpinner, TextView(this).apply { text = "Strings" }, stringsSpinner, TextView(this).apply { text = "Tuning" }, tuningSpinner, bpmEdit).forEach(box::addView)
        AlertDialog.Builder(this).setTitle("New Earam Project").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Create") { _, _ ->
            projectName = name.text.toString().ifBlank { "UNTITLED" }; instrument = instrumentSpinner.selectedItem.toString(); stringCount = stringsSpinner.selectedItem.toString().toInt(); tuning = tuningSpinner.selectedItem.toString(); bpm = bpmEdit.text.toString().toIntOrNull()?.coerceIn(30, 300) ?: 120; setContentView(EditorView())
        }.show()
    }

    private inner class HomeView : View(this) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) { c.drawColor(0xFF0E1012.toInt()); p.color=0xFFF1F2F3.toInt();p.textSize=38f;p.typeface=Typeface.DEFAULT_BOLD;c.drawText("Earam",34f,90f,p);p.color=0xFF8F969B.toInt();p.textSize=14f;p.typeface=Typeface.DEFAULT;c.drawText("GUITAR / BASS TAB & NOTATION",36f,119f,p);button(c,"NEW PROJECT",36f,170f,width-72f,58f);button(c,"OPEN PROJECT",36f,244f,width-72f,58f);p.color=0xFF676E73.toInt();p.textSize=12f;c.drawText("Recent",36f,346f,p);p.color=0xFFDDE0E2.toInt();p.textSize=16f;c.drawText(projectName,36f,378f,p) }
        private fun button(c:Canvas,s:String,x:Float,y:Float,w:Float,h:Float){p.color=0xFF202529.toInt();c.drawRoundRect(x,y,x+w,y+h,10f,10f,p);p.color=0xFFF0F1F2.toInt();p.textSize=15f;p.typeface=Typeface.DEFAULT_BOLD;c.drawText(s,x+18f,y+36f,p)}
        override fun onTouchEvent(e:MotionEvent):Boolean { if(e.action==MotionEvent.ACTION_UP && e.y in 160f..235f)newProject(); return true }
    }

    private inner class EditorView : View(this) {
        private val bg=Paint(Paint.ANTI_ALIAS_FLAG);private val line=Paint(Paint.ANTI_ALIAS_FLAG);private val text=Paint(Paint.ANTI_ALIAS_FLAG)
        private var names=when(stringCount){7->arrayOf("e","B","G","D","A","E","B");6->arrayOf("e","B","G","D","A","E");5->arrayOf("G","D","A","E","B");else->arrayOf("G","D","A","E")}
        private val cells=Array(stringCount){mutableMapOf<Int,String>()};private val strokes=mutableMapOf<Int,StrokeDirection>();private var selectedStroke=StrokeDirection.DOWN;private var mode=PickingMode.MANUAL;private val pattern=StrumPattern.fromText("↓ ↓ ↑ ↑ ↓ ↑");private var tool="NOTE";private var col=0;private var row=0;private val undo=ArrayDeque<EditorState>();private val redo=ArrayDeque<EditorState>()
        override fun onDraw(c:Canvas){c.drawColor(0xFF111315.toInt());val w=width.toFloat();val h=height.toFloat();bg.color=0xFF191C1F.toInt();c.drawRect(0f,0f,w,66f,bg);text.color=0xFFF4F4F4.toInt();text.textSize=22f;text.typeface=Typeface.DEFAULT_BOLD;c.drawText("Earam",20f,41f,text);text.color=0xFF9FA5AA.toInt();text.textSize=12f;text.typeface=Typeface.DEFAULT;c.drawText(projectName,108f,40f,text);c.drawText("$bpm BPM",w-120f,40f,text);bg.color=0xFF202428.toInt();c.drawRect(0f,66f,w,122f,bg);tool(c,"NOTE",16f,tool=="NOTE");tool(c,"REST",74f,tool=="REST");tool(c,"CHORD",130f,tool=="CHORD");tool(c,"UNDO",w-124f,false);tool(c,"REDO",w-62f,false);text.color=0xFF8D9499.toInt();text.textSize=11f;c.drawText("$instrument • $stringCount-string • $tuning",20f,148f,text);val gt=190f;val gap=if(stringCount>6)27f else 30f;val gx=72f;val gr=w-18f;val cw=(gr-gx)/8f;text.color=0xFFB8BDC1.toInt();text.textSize=12f;for(s in names.indices){val yy=gt+s*gap;c.drawText(names[s],18f,yy+4f,text);line.color=0xFF34383C.toInt();line.strokeWidth=1f;c.drawLine(gx,yy,gr,yy,line)};for(i in 0..8){val xx=gx+i*cw;line.color=if(i%4==0)0xFF555B60.toInt()else 0xFF292D31.toInt();line.strokeWidth=if(i%4==0)2f else 1f;c.drawLine(xx,gt-22f,xx,gt+(stringCount-1)*gap+12f,line)};for(s in names.indices)for((k,v) in cells[s])drawTab(c,v,gx+k*cw+cw/2-5f,gt+s*gap+6f,s==row&&k==col);text.color=0xFFD8DADD.toInt();text.textSize=17f;for(k in 0 until 8)strokes[k]?.let{c.drawText(if(it==StrokeDirection.DOWN)"↓" else "↑",gx+k*cw+cw/2-5f,gt-30f,text)};bg.color=0xFF1C2023.toInt();c.drawRect(0f,h-116f,w,h,bg);text.color=0xFF8F969B.toInt();text.textSize=10f;c.drawText("PICKING",16f,h-88f,text);control(c,"↓",16f,h-70f,34f,mode==PickingMode.MANUAL&&selectedStroke==StrokeDirection.DOWN);control(c,"↑",56f,h-70f,34f,mode==PickingMode.MANUAL&&selectedStroke==StrokeDirection.UP);control(c,"ALT",96f,h-70f,42f,mode==PickingMode.ALTERNATE);control(c,"STRUM",146f,h-70f,58f,mode==PickingMode.STRUM);text.color=0xFFE5E7E8.toInt();text.textSize=13f;c.drawText("↓ ↓ ↑ ↑ ↓ ↑",220f,h-50f,text)}
        private fun snapshot()=EditorState(Array(stringCount){s->cells[s].toMap()},strokes.toMap());private fun restore(st:EditorState){for(s in 0 until stringCount){cells[s].clear();cells[s].putAll(st.notes[s])};strokes.clear();strokes.putAll(st.strokes)};private fun remember(){undo.addLast(snapshot());if(undo.size>50)undo.removeFirst();redo.clear()};private fun doUndo(){if(undo.isNotEmpty()){redo.addLast(snapshot());restore(undo.removeLast());invalidate()}};private fun doRedo(){if(redo.isNotEmpty()){undo.addLast(snapshot());restore(redo.removeLast());invalidate()}};private fun assign(k:Int){when(mode){PickingMode.MANUAL->strokes[k]=selectedStroke;PickingMode.ALTERNATE->PickingEngine.alternate(8,selectedStroke).forEachIndexed{i,d->strokes[i]=d};PickingMode.STRUM->PickingEngine.applyPattern(pattern,8).forEachIndexed{i,d->strokes[i]=d}}}
        private fun editFret(s:Int,k:Int){val input=EditText(this@MainActivity).apply{inputType=InputType.TYPE_CLASS_NUMBER;hint="0–24";setText(cells[s][k]?:"")};AlertDialog.Builder(this@MainActivity).setTitle("Fret ${names[s]} • Beat ${k+1}").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Apply"){_,_->remember();val v=input.text.toString().trim();if(v.isEmpty())cells[s].remove(k)else cells[s][k]=v.toIntOrNull()?.coerceIn(0,24)?.toString()?:"0";assign(k);invalidate()}.show()}
        private fun tool(c:Canvas,s:String,x:Float,a:Boolean){text.color=if(a)0xFFFFFFFF.toInt()else 0xFFAEB4B8.toInt();text.textSize=12f;c.drawText(s,x,101f,text)};private fun control(c:Canvas,s:String,x:Float,y:Float,w:Float,a:Boolean){bg.color=if(a)0xFF3A4248.toInt()else 0xFF252A2E.toInt();c.drawRoundRect(x,y,x+w,y+30f,6f,6f,bg);text.color=0xFFE0E3E5.toInt();text.textSize=12f;c.drawText(s,x+9f,y+20f,text)};private fun drawTab(c:Canvas,v:String,x:Float,y:Float,a:Boolean){text.color=0xFFF0F1F2.toInt();text.textSize=16f;text.typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD);c.drawText(v,x,y,text);if(a){line.color=0xFF888F95.toInt();line.strokeWidth=2f;c.drawCircle(x+5f,y-5f,12f,line)};text.typeface=Typeface.DEFAULT}
        override fun onTouchEvent(e:MotionEvent):Boolean{if(e.action!=MotionEvent.ACTION_UP)return true;val x=e.x;val y=e.y;val h=height.toFloat();val w=width.toFloat();if(y in 66f..122f){when{x>=w-140f&&x<w-70f->doUndo();x>=w-70f->doRedo();x<65f->tool="NOTE";x<120f->tool="REST";x<190f->tool="CHORD"};invalidate();return true};if(y>h-116f){when{x<52f->{mode=PickingMode.MANUAL;selectedStroke=StrokeDirection.DOWN};x<92f->{mode=PickingMode.MANUAL;selectedStroke=StrokeDirection.UP};x<142f->mode=PickingMode.ALTERNATE;x<215f->mode=PickingMode.STRUM};remember();assign(col);invalidate();return true};val gt=190f;val gap=if(stringCount>6)27f else 30f;val gx=72f;val gr=w-18f;val cw=(gr-gx)/8f;if(y>=gt-20f&&y<=gt+(stringCount-1)*gap+20f&&x>=gx&&x<=gr){row=((y-gt+gap/2)/gap).toInt().coerceIn(0,stringCount-1);col=((x-gx)/cw).toInt().coerceIn(0,7);when(tool){"NOTE"->editFret(row,col);"REST"->{remember();cells[row].remove(col);invalidate()};"CHORD"->{remember();if(cells[row].containsKey(col))cells[row].remove(col)else cells[row][col]="0";assign(col);invalidate()}}};return true}
    }
}
