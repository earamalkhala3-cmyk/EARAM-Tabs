package com.earam.tabs

import android.app.Activity
import android.app.AlertDialog
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.text.InputType
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import com.earam.tabs.music.PickingEngine
import com.earam.tabs.music.PickingMode
import com.earam.tabs.music.StrumPattern
import com.earam.tabs.music.StrokeDirection

data class EditorState(val notes:Array<out Map<Int,String>>,val strokes:Map<Int,StrokeDirection>,val durations:Map<Int,Long>)

class MainActivity:Activity(){
 private var projectName="UNTITLED";private var instrument="Guitar";private var stringCount=6;private var tuning="Standard";private var bpm=120;private var timeSig=4;private var keySig="C";private var notation="BOTH"
 private var cells=Array(6){mutableMapOf<Int,String>()};private var strokes=mutableMapOf<Int,StrokeDirection>();private var durations=mutableMapOf<Int,Long>();private var editor:EditorView?=null
 private var playing=false;private var playThread:Thread?=null;private var track:AudioTrack?=null
 override fun onCreate(b:Bundle?){super.onCreate(b);showHome()}
 private fun showHome(){stopPlayback();setContentView(HomeView())}
 private fun newProject(){
  val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(28,4,28,0)}
  val name=EditText(this).apply{hint="Project name"}
  val ins=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("Guitar","Bass"))}
  val strings=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("4","5","6","7"));setSelection(2)}
  val tune=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("Standard","Drop D","Drop C","Custom"))}
  val sig=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("4/4","3/4","6/8","5/4","7/8"))}
  val key=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("C","G","D","A","E","F","Am","Em"))}
  val tempo=EditText(this).apply{hint="BPM";inputType=InputType.TYPE_CLASS_NUMBER;setText("120")}
  listOf(TextView(this).apply{text="Instrument"},ins,TextView(this).apply{text="Strings"},strings,TextView(this).apply{text="Tuning"},tune,TextView(this).apply{text="Time Signature"},sig,TextView(this).apply{text="Key"},key,tempo).forEach(box::addView)
  AlertDialog.Builder(this).setTitle("New Earam Project").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Create"){_,_->
   projectName=name.text.toString().trim().ifBlank{"UNTITLED"};instrument=ins.selectedItem.toString();stringCount=strings.selectedItem.toString().toInt();tuning=tune.selectedItem.toString();timeSig=sig.selectedItem.toString().substringBefore('/').toInt();keySig=key.selectedItem.toString();bpm=tempo.text.toString().toIntOrNull()?.coerceIn(30,300)?:120
   cells=Array(stringCount){mutableMapOf()};strokes=mutableMapOf();durations=mutableMapOf();openEditor()
  }.show()
 }
 private fun safeFileName(s:String)=s.replace(Regex("[^A-Za-z0-9._-]"),"_").ifBlank{"UNTITLED"}
 private fun saveProject(){try{val root=JSONObject();root.put("version",3);root.put("name",projectName);root.put("instrument",instrument);root.put("strings",stringCount);root.put("tuning",tuning);root.put("bpm",bpm);root.put("timeSignature",timeSig);root.put("key",keySig);root.put("notation",notation);val n=JSONArray();cells.forEach{m->val o=JSONObject();m.forEach{(k,v)->o.put(k.toString(),v)};n.put(o)};root.put("notes",n);val st=JSONObject();strokes.forEach{(k,v)->st.put(k.toString(),if(v==StrokeDirection.DOWN)"DOWN"else"UP")};root.put("strokes",st);val du=JSONObject();durations.forEach{(k,v)->du.put(k.toString(),v)};root.put("durations",du);File(filesDir,"${safeFileName(projectName)}.earam").writeText(root.toString());Toast.makeText(this,"Saved: $projectName",Toast.LENGTH_SHORT).show()}catch(_:Exception){Toast.makeText(this,"Save failed",Toast.LENGTH_SHORT).show()}}
 private fun openProject(){val fs=filesDir.listFiles()?.filter{it.extension=="earam"}?:emptyList();if(fs.isEmpty()){Toast.makeText(this,"No Earam projects found",Toast.LENGTH_SHORT).show();return};AlertDialog.Builder(this).setTitle("Open Earam Project").setItems(fs.map{it.nameWithoutExtension}.toTypedArray()){_,i->loadProject(fs[i])}.show()}
 private fun loadProject(f:File){try{val r=JSONObject(f.readText());projectName=r.optString("name","UNTITLED");instrument=r.optString("instrument","Guitar");stringCount=r.optInt("strings",6).coerceIn(4,7);tuning=r.optString("tuning","Standard");bpm=r.optInt("bpm",120).coerceIn(30,300);timeSig=r.optInt("timeSignature",4);keySig=r.optString("key","C");notation=r.optString("notation","BOTH");cells=Array(stringCount){mutableMapOf()};r.optJSONArray("notes")?.let{n->for(s in 0 until stringCount){n.optJSONObject(s)?.let{o->o.keys().forEach{k->cells[s][k.toInt()]=o.getString(k)}}}};strokes=mutableMapOf();r.optJSONObject("strokes")?.let{st->st.keys().forEach{k->strokes[k.toInt()]=if(st.getString(k)=="UP")StrokeDirection.UP else StrokeDirection.DOWN)};durations=mutableMapOf();r.optJSONObject("durations")?.let{du->du.keys().forEach{k->durations[k.toInt()]=du.getLong(k)}};openEditor()}catch(_:Exception){Toast.makeText(this,"Could not open project",Toast.LENGTH_SHORT).show()}}
 private fun midi(s:Int,f:Int):Int{val open=when(stringCount){7->intArrayOf(64,59,55,50,45,40,35);6->intArrayOf(64,59,55,50,45,40);5->intArrayOf(43,38,33,28,23);else->intArrayOf(43,38,33,28)};var m=open[s.coerceIn(0,open.lastIndex)]+f;if(tuning=="Drop D"&&s==stringCount-1)m-=2;if(tuning=="Drop C"&&s==stringCount-1)m-=4;return m}
 private fun playColumn(k:Int,seconds:Double){val rate=44100;val n=(rate*seconds).toInt().coerceAtLeast(1);val data=ShortArray(n);val notes=mutableListOf<Int>();cells.forEachIndexed{s,m->m[k]?.toIntOrNull()?.let{notes.add(midi(s,it))}};for(i in 0 until n){var v=0.0;notes.forEach{m->v+=sin(2.0*PI*(440.0*Math.pow(2.0,(m-69)/12.0))*i/rate)};if(notes.isNotEmpty())v/=notes.size;data[i]=(v*10000.0).toInt().coerceIn(-32767,32767).toShort()};track?.write(data,0,data.size)}
 private fun startPlayback(){if(playing)return;playing=true;editor?.invalidate();playThread=Thread{try{val rate=44100;track=AudioTrack(AudioManager.STREAM_MUSIC,rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,rate,AudioTrack.MODE_STREAM);track?.play();do{for(k in 0 until 8){if(!playing)break;val d=durations[k]?:960L;playColumn(k,d/960.0*60.0/bpm);runOnUiThread{editor?.playingCol=k;editor?.invalidate()}}}while(playing&&editor?.loop==true)}finally{try{track?.stop()}catch(_:Exception){};track?.release();track=null;playing=false;runOnUiThread{editor?.playingCol=-1;editor?.invalidate()}}}.also{it.start()}}
 private fun stopPlayback(){playing=false;playThread?.interrupt();playThread=null;try{track?.stop()}catch(_:Exception){};track?.release();track=null}
 private fun openEditor(){editor=EditorView();setContentView(editor)}
 private inner class HomeView:View(this){private val p=Paint(1);override fun onDraw(c:Canvas){c.drawColor(0xFF0E1012.toInt());p.color=0xFFF1F2F3.toInt();p.textSize=40f;p.typeface=Typeface.DEFAULT_BOLD;c.drawText("Earam",34f,88f,p);p.color=0xFF8F969B.toInt();p.textSize=13f;p.typeface=Typeface.DEFAULT;c.drawText("GUITAR / BASS TAB • STANDARD • PLAYBACK",36f,116f,p);button(c,"NEW PROJECT",36f,170f,width-72f,58f);button(c,"OPEN PROJECT",36f,244f,width-72f,58f)};private fun button(c:Canvas,s:String,x:Float,y:Float,w:Float,h:Float){p.color=0xFF202529.toInt();c.drawRoundRect(x,y,x+w,y+h,10f,10f,p);p.color=0xFFF0F1F2.toInt();p.textSize=15f;p.typeface=Typeface.DEFAULT_BOLD;c.drawText(s,x+18f,y+36f,p)};override fun onTouchEvent(e:MotionEvent):Boolean{if(e.action==MotionEvent.ACTION_UP){when{e.y>=160f&&e.y<235f->newProject();e.y>=235f&&e.y<315f->openProject()}};return true}}
 private inner class EditorView:View(this){private val p=Paint(1);private val line=Paint(1);private var row=0;private var col=0;private var selectedStroke=StrokeDirection.DOWN;private var mode=PickingMode.MANUAL;private val pattern=StrumPattern.fromText("↓ ↓ ↑ ↑ ↓ ↑");private val undo=ArrayDeque<EditorState>();private val redo=ArrayDeque<EditorState>();var loop=false;var playingCol=-1
  override fun onDraw(c:Canvas){c.drawColor(0xFF111315.toInt());val w=width.toFloat();val h=height.toFloat();p.color=0xFF191C1F.toInt();c.drawRect(0f,0f,w,64f,p);txt(c,"Earam",18f,40f,22f,true);txt(c,projectName,104f,39f,12f,false);txt(c,"$bpm BPM",w-82f,39f,11f,false);p.color=0xFF202428.toInt();c.drawRect(0f,64f,w,118f,p);val tools=listOf("NOTE","REST","CHORD","DUR","SAVE","HOME","PLAY","STOP","LOOP");tools.forEachIndexed{i,s->txt(c,s,8f+i*((w-16f)/9f),97f,9f,false)};txt(c,"$instrument • $stringCount-string • $tuning • $timeSig/4 • $keySig",18f,143f,10f,false)
   val staffTop=164f;val spacing=9f;p.color=0xFF555A5F.toInt();for(i in 0..4)c.drawLine(18f,staffTop+i*spacing,w-18f,staffTop+i*spacing,p);val gx=54f;val gy=238f;val gap=if(stringCount>6)25f else 28f;val cw=(w-gx-12f)/8f
   for(s in 0 until stringCount){val y=gy+s*gap;val names=when(stringCount){7->arrayOf("e","B","G","D","A","E","B");6->arrayOf("e","B","G","D","A","E");5->arrayOf("G","D","A","E","B");else->arrayOf("G","D","A","E")};txt(c,names[s],18f,y+4f,10f,false);line.color=0xFF34383C.toInt();c.drawLine(gx,y,w-12f,y,line)}
   for(k in 0..8){val x=gx+k*cw;line.color=if(k%timeSig==0)0xFF666C71.toInt()else 0xFF292D31.toInt();c.drawLine(x,gy-14f,x,gy+(stringCount-1)*gap+12f,line)}
   for(k in 0 until 8){val x=gx+k*cw+cw/2f;strokes[k]?.let{txt(c,if(it==StrokeDirection.DOWN)"↓"else"↑",x-5f,gy-18f,16f,true)};for(s in 0 until stringCount){cells[s][k]?.let{drawTab(c,it,x-5f,gy+s*gap+5f,s==row&&k==col)}};drawStandard(c,k,x,staffTop)}
   p.color=0xFF1C2023.toInt();c.drawRect(0f,h-120f,w,h,p);txt(c,"DURATION",14f,h-94f,9f,false);listOf("½","♩","♪","♬").forEachIndexed{i,s->chip(c,s,12f+i*42f,h-78f,34f,i==durIndex())};txt(c,"PICKING",190f,h-94f,9f,false);chip(c,"↓",186f,h-78f,34f,mode==PickingMode.MANUAL&&selectedStroke==StrokeDirection.DOWN);chip(c,"↑",224f,h-78f,34f,mode==PickingMode.MANUAL&&selectedStroke==StrokeDirection.UP);chip(c,"ALT",262f,h-78f,44f,mode==PickingMode.ALTERNATE);chip(c,"STR",310f,h-78f,42f,mode==PickingMode.STRUM);txt(c,if(playingCol>=0)"▶ ${playingCol+1}/8" else "Ready",365f,h-58f,10f,false)
  }
  private fun durIndex()=when(durations[col]?:960L){1920L->0;960L->1;480L->2;240L->3;else->1}
  private fun txt(c:Canvas,s:String,x:Float,y:Float,size:Float,bold:Boolean){p.color=0xFFE5E7E8.toInt();p.textSize=size;p.typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT;c.drawText(s,x,y,p)}
  private fun chip(c:Canvas,s:String,x:Float,y:Float,w:Float,a:Boolean){p.color=if(a)0xFF3A4248.toInt()else 0xFF252A2E.toInt();c.drawRoundRect(x,y,x+w,y+28f,5f,5f,p);txt(c,s,x+9f,y+19f,10f,true)}
  private fun drawTab(c:Canvas,v:String,x:Float,y:Float,a:Boolean){txt(c,v,x,y,15f,true);if(a){p.style=Paint.Style.STROKE;p.color=0xFF9AA1A6.toInt();c.drawCircle(x+5f,y-5f,11f,p);p.style=Paint.Style.FILL}}
  private fun drawStandard(c:Canvas,k:Int,x:Float,top:Float){if(cells.none{it.containsKey(k)})return;val avg=cells.indices.filter{cells[it].containsKey(k)}.map{midi(it,cells[it][k]!!.toIntOrNull()?:0)}.average();val y=top+36f-(avg-60.0)*2.0f;p.color=0xFFE5E7E8.toInt();c.drawCircle(x,y.toFloat(),5f,p);line.color=0xFFE5E7E8.toInt();c.drawLine(x+5f,y.toFloat(),x+5f,y.toFloat()-25f,line)}
  private fun snapshot()=EditorState(Array<Map<Int,String>>(stringCount){cells[it].toMap()},strokes.toMap(),durations.toMap())
  private fun restore(st:EditorState){cells=Array(stringCount){st.notes[it].toMutableMap()};strokes=st.strokes.toMutableMap();durations=st.durations.toMutableMap()}
  private fun remember(){undo.addLast(snapshot());if(undo.size>50)undo.removeFirst();redo.clear()}
  private fun assign(){remember();when(mode){PickingMode.MANUAL->strokes[col]=selectedStroke;PickingMode.ALTERNATE->PickingEngine.alternate(8,selectedStroke).forEachIndexed{i,d->strokes[i]=d};PickingMode.STRUM->PickingEngine.applyPattern(pattern,8).forEachIndexed{i,d->strokes[i]=d}};invalidate()}
  private fun setDuration(v:Long){remember();durations[col]=v;invalidate()}
  override fun onTouchEvent(e:MotionEvent):Boolean{if(e.action!=MotionEvent.ACTION_UP)return true;val w=width.toFloat();val h=height.toFloat();if(e.y>=64f&&e.y<118f){val i=(e.x/((w-16f)/9f)).toInt();when(i){0->{};1->{};2->{};3->{};4->saveProject();5->showHome();6->startPlayback();7->stopPlayback();8->{loop=!loop;invalidate()}};return true};if(e.y>=h-120f){when{e.x in 12f..46f->setDuration(1920L);e.x in 54f..88f->setDuration(960L);e.x in 96f..130f->setDuration(480L);e.x in 138f..172f->setDuration(240L);e.x in 186f..220f->{selectedStroke=StrokeDirection.DOWN;mode=PickingMode.MANUAL;assign()};e.x in 224f..258f->{selectedStroke=StrokeDirection.UP;mode=PickingMode.MANUAL;assign()};e.x in 262f..306f->{mode=PickingMode.ALTERNATE;assign()};e.x in 310f..352f->{mode=PickingMode.STRUM;assign()}};return true};val gx=54f;val cw=(w-gx-12f)/8f;val gap=if(stringCount>6)25f else 28f;if(e.x>=gx&&e.x<=w-12f&&e.y>=220f&&e.y<=238f+(stringCount-1)*gap+20f){col=((e.x-gx)/cw).toInt().coerceIn(0,7);row=((e.y-238f+gap/2f)/gap).toInt().coerceIn(0,stringCount-1);remember();cells[row][col]=((e.x-gx)/cw*4f).toInt().coerceIn(0,24).toString();assign();invalidate()};return true}
 }
 private fun drawDummy(){ }
}
