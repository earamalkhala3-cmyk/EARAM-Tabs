package com.earam.tabs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Modeled instrument renderer: pick transient + harmonics + string decay + body/pickup coloration. */
class GuitarSoundEngine(private val sampleRate: Int = 44100) {
    enum class Voice { ELECTRIC_GUITAR, ACOUSTIC_GUITAR, BASS, PIANO }

    fun render(midiNotes: List<Int>, durationSeconds: Double, voice: Voice, velocity: Float = 0.9f, upstroke: Boolean = false): ShortArray {
        val length = max(1, (durationSeconds * sampleRate).toInt())
        if (midiNotes.isEmpty()) return ShortArray(length)
        val out = DoubleArray(length)
        midiNotes.forEachIndexed { index, midi ->
            val note = when (voice) {
                Voice.PIANO -> piano(midi, durationSeconds, velocity)
                else -> guitar(midi, durationSeconds, voice, velocity, upstroke, index)
            }
            val gain = 1.0 / max(1.0, midiNotes.size.toDouble().pow(0.38))
            for (i in note.indices) if (i < out.size) out[i] += note[i] * gain
        }
        val peak = out.maxOf { abs(it) }.coerceAtLeast(1e-9)
        val scale = min(0.94, 28000.0 / (peak * 32767.0))
        return ShortArray(length) { i -> (out[i] * scale * 32767.0).toInt().coerceIn(-32767,32767).toShort() }
    }

    private fun guitar(midi: Int, seconds: Double, voice: Voice, velocity: Float, upstroke: Boolean, seedOffset: Int): DoubleArray {
        val length=max(1,(seconds*sampleRate).toInt())
        val f=440.0*2.0.pow((midi-69)/12.0)
        val partials=when(voice){ Voice.BASS->doubleArrayOf(1.0,.42,.20,.10,.05); Voice.ACOUSTIC_GUITAR->doubleArrayOf(1.0,.62,.38,.22,.12,.07); else->doubleArrayOf(1.0,.78,.55,.36,.22,.13,.08) }
        val decay=when(voice){ Voice.BASS->5.0; Voice.ACOUSTIC_GUITAR->2.7; else->3.8 }
        val out=DoubleArray(length)
        val rnd=Random(0xEA7A+midi*31+seedOffset*97+if(upstroke)7 else 0)
        for(n in 0 until length){
            val t=n.toDouble()/sampleRate
            val attack=(1.0-exp(-n.toDouble()/(sampleRate*0.0025)))*exp(-n.toDouble()/(sampleRate*0.045))
            var tone=0.0
            partials.forEachIndexed { p,a -> val detune=1.0+p*0.0017; tone += a*sin(2.0*PI*f*(p+1)*detune*t)*exp(-t/(decay/(1.0+p*0.32))) }
            val pickNoise=(rnd.nextDouble(-1.0,1.0)*exp(-t/0.008))*if(upstroke).78 else 1.0
            val body=sin(2.0*PI*(if(voice==Voice.BASS)95.0 else 185.0)*t)*exp(-t/.45)*.07
            var v=(tone*.20+pickNoise*.20*attack+body)*velocity
            if(voice==Voice.ELECTRIC_GUITAR){ v=tanh(v*2.8)*0.82; v += .035*sin(2.0*PI*f*7.0*t)*exp(-t/1.2) }
            out[n]=v
        }
        return out
    }

    private fun piano(midi:Int, seconds:Double, velocity:Float):DoubleArray {
        val length=max(1,(seconds*sampleRate).toInt()); val f=440.0*2.0.pow((midi-69)/12.0); val out=DoubleArray(length)
        for(n in 0 until length){ val t=n.toDouble()/sampleRate; val attack=1.0-exp(-t/.004); val d=exp(-t/2.4); var v=0.0; for(h in 1..8) v += (1.0/h.pow(.72))*sin(2*PI*f*h*t)*exp(-t/(2.4+h*.15)); out[n]=v*.18*attack*d*velocity }
        return out
    }

    private fun tanh(x:Double):Double { val e=exp(2*x); return (e-1)/(e+1) }
}