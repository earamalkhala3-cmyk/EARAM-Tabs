package com.earam.tabs

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.math.roundToLong

object TabImportParser {
    data class Imported(
        val name: String, val instrument: String, val strings: Int, val tuning: String,
        val bpm: Int, val timeSignature: String, val key: String,
        val cells: Array<MutableMap<Int, String>>, val durations: MutableMap<Int, Long>,
        val chords: MutableMap<Int, String>, val strokes: MutableMap<Int, String>, val columns: Int
    )

    fun parse(context: Context, uri: Uri, fileName: String): Imported {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot read TAB file")
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "gpx" -> parseGpx(bytes, fileName)
            "gp" -> parseGpZip(bytes, fileName)
            "xml", "musicxml", "mxl" -> parseMusicXml(bytes, fileName)
            "earam", "json" -> parseEaram(bytes, fileName)
            else -> if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte())
                parseGpZip(bytes, fileName) else error("Unsupported TAB format: ." + ext)
        }
    }

    private fun parseGpZip(bytes: ByteArray, fileName: String): Imported {
        var score: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && (e.name.endsWith("score.gpif", true) || e.name.endsWith("score.xml", true))) {
                    score = z.readBytes()
                    break
                }
            }
        }
        return score?.let { parseGpxXml(it, fileName) } ?: error("No score.gpif found in TAB archive")
    }

    private fun parseGpx(bytes: ByteArray, fileName: String): Imported {
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) return parseGpZip(bytes, fileName)
        error("GPX BCFS/BCFZ container detected. Native BCFS decoding is not enabled yet.")
    }

    private fun parseGpxXml(bytes: ByteArray, fileName: String): Imported {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var title = fileName.substringBeforeLast('.').ifBlank { "Imported TAB" }
        var bpm = 120
        var sig = "4/4"
        var strings = 6
        var tuning = "Standard"
        var beat = 0
        var track = -1
        var inBeat = false
        var stringIndex = -1
        var fret = -1
        var ticks = 960L
        var chord: String? = null
        val raw = Array(10) { mutableMapOf<Int, String>() }
        val durations = mutableMapOf<Int, Long>()
        val chords = mutableMapOf<Int, String>()

        fun dur(s: String) = when (s.lowercase()) {
            "whole" -> 3840L; "half" -> 1920L; "quarter" -> 960L
            "eighth" -> 480L; "sixteenth", "16th" -> 240L
            "thirtysecond", "32nd" -> 120L; "sixtyfourth", "64th" -> 60L
            else -> 960L
        }
        fun finish() {
            if (!inBeat) return
            if (track == 0 && stringIndex >= 0 && fret >= 0 && stringIndex < strings) {
                val target = strings - 1 - stringIndex
                raw[target][beat] = fret.toString()
            }
            if (track == 0) {
                durations[beat] = ticks
                if (!chord.isNullOrBlank()) chords[beat] = chord!!
                beat++
            }
            stringIndex = -1; fret = -1; ticks = 960L; chord = null
        }

        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                when (p.name.lowercase()) {
                    "title" -> p.nextText().trim().takeIf { it.isNotBlank() }?.let { title = it }
                    "tempo" -> p.getAttributeValue(null, "value")?.toIntOrNull()?.let { bpm = it.coerceIn(30, 300) }
                    "track" -> track++
                    "strings" -> p.getAttributeValue(null, "count")?.toIntOrNull()?.let { strings = it.coerceIn(1, 10) }
                    "string" -> {
                        val n = p.getAttributeValue(null, "number")?.toIntOrNull()
                        val t = p.getAttributeValue(null, "tuning")?.toIntOrNull()
                        if (n != null) strings = maxOf(strings, n).coerceAtMost(10)
                        if (n == 1 && t == 64) tuning = "Standard"
                    }
                    "beat" -> { finish(); inBeat = track == 0 }
                    "note" -> if (inBeat) {
                        stringIndex = p.getAttributeValue(null, "string")?.toIntOrNull() ?: -1
                        fret = p.getAttributeValue(null, "fret")?.toIntOrNull() ?: -1
                    }
                    "notevalue" -> if (inBeat) ticks = dur(p.nextText().trim())
                    "chord" -> if (inBeat) chord = p.getAttributeValue(null, "name")
                    "timesignature" -> {
                        val n = p.getAttributeValue(null, "numerator")?.toIntOrNull()
                        val d = p.getAttributeValue(null, "denominator")?.toIntOrNull()
                        if (n != null && d != null) sig = n.toString() + "/" + d
                    }
                }
            } else if (e == XmlPullParser.END_TAG && p.name.equals("beat", true)) {
                finish(); inBeat = false
            }
            e = p.next()
        }
        finish()
        val out = Array(strings) { mutableMapOf<Int, String>() }
        for (s in 0 until strings) raw[s].forEach { (k,v) -> out[s][k] = v }
        return Imported(title, "Electric Guitar", strings, tuning, bpm, sig, "C", out, durations, chords, mutableMapOf(), maxOf(1, beat))
    }

    private fun parseMusicXml(bytes: ByteArray, fileName: String): Imported {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val cells = Array(6) { mutableMapOf<Int, String>() }
        val durations = mutableMapOf<Int, Long>()
        var title = fileName.substringBeforeLast('.')
        var bpm = 120
        var sig = "4/4"
        var divisions = 1
        var beat = 0
        var string = -1
        var fret = -1
        var duration = 1L
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (p.name.lowercase()) {
                "work-title", "movement-title" -> title = p.nextText().trim().ifBlank { title }
                "per-minute" -> p.nextText().trim().toIntOrNull()?.let { bpm = it.coerceIn(30,300) }
                "divisions" -> divisions = p.nextText().trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
                "beats" -> p.nextText().trim().toIntOrNull()?.let { sig = it.toString() + "/" + sig.substringAfter('/', "4") }
                "beat-type" -> p.nextText().trim().toIntOrNull()?.let { sig = sig.substringBefore('/', "4") + "/" + it }
                "string" -> string = p.nextText().trim().toIntOrNull() ?: -1
                "fret" -> fret = p.nextText().trim().toIntOrNull() ?: -1
                "duration" -> duration = p.nextText().trim().toLongOrNull() ?: 1L
            } else if (e == XmlPullParser.END_TAG && p.name.equals("note", true)) {
                if (string in 1..6 && fret >= 0) cells[6-string][beat] = fret.toString()
                durations[beat] = (duration.toDouble() / divisions * 960.0).roundToLong().coerceAtLeast(60)
                beat++; string = -1; fret = -1
            }
            e = p.next()
        }
        return Imported(title, "Electric Guitar", 6, "Standard", bpm, sig, "C", cells, durations, mutableMapOf(), mutableMapOf(), maxOf(1,beat))
    }

    private fun parseEaram(bytes: ByteArray, fileName: String): Imported {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val n = root.optInt("strings", 6).coerceIn(1,10)
        val cells = Array(n) { mutableMapOf<Int,String>() }
        val notes = root.optJSONArray("notes")
        if (notes != null) for (s in 0 until n) notes.optJSONObject(s)?.let { o -> o.keys().forEach { k -> cells[s][k.toInt()] = o.getString(k) } }
        val durations = mutableMapOf<Int,Long>()
        root.optJSONObject("durations")?.let { o -> o.keys().forEach { k -> durations[k.toInt()] = o.getLong(k) } }
        val chords = mutableMapOf<Int,String>()
        root.optJSONObject("chords")?.let { o -> o.keys().forEach { k -> chords[k.toInt()] = o.getString(k) } }
        return Imported(root.optString("name",fileName.substringBeforeLast('.')),root.optString("instrument","Electric Guitar"),n,
            root.optString("tuning","Standard"),root.optInt("bpm",120).coerceIn(30,300),root.optString("timeSignature","4/4"),
            root.optString("key","C"),cells,durations,chords,mutableMapOf(),maxOf(1,(durations.keys.maxOrNull() ?: 0)+1))
    }
}
