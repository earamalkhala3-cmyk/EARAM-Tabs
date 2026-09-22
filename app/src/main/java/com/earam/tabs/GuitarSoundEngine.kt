package com.earam.tabs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Lightweight modeled instrument engine.
 * It provides distinct timbres for the main Guitar Pro-style instrument families
 * without shipping copyrighted samples.
 */
class GuitarSoundEngine(private val sampleRate: Int = 44100) {
    enum class Voice {
        ELECTRIC_GUITAR, CLEAN_ELECTRIC_GUITAR, DISTORTION_GUITAR, ACOUSTIC_GUITAR, CLASSICAL_GUITAR,
        TWELVE_STRING_GUITAR, SEVEN_STRING_GUITAR, EIGHT_STRING_GUITAR, BARITONE_GUITAR,
        BASS, FIVE_STRING_BASS, SIX_STRING_BASS, FRETLESS_BASS,
        PIANO, ELECTRIC_PIANO, ORGAN, SYNTH_LEAD, SYNTH_PAD,
        VIOLIN, VIOLA, CELLO, DOUBLE_BASS,
        FLUTE, CLARINET, OBOE, SAXOPHONE, TRUMPET, TROMBONE,
        HARMONICA, BANJO, MANDOLIN, UKULELE, HARP, DRUMS
    }

    fun render(midiNotes: List<Int>, durationSeconds: Double, voice: Voice,
               velocity: Float = 0.9f, upstroke: Boolean = false): ShortArray {
        val length = max(1, (durationSeconds * sampleRate).toInt())
        if (midiNotes.isEmpty()) return ShortArray(length)
        val out = DoubleArray(length)
        midiNotes.forEachIndexed { index, midi ->
            val note = when (voice) {
                Voice.ELECTRIC_GUITAR, Voice.CLEAN_ELECTRIC_GUITAR, Voice.DISTORTION_GUITAR, Voice.ACOUSTIC_GUITAR, Voice.CLASSICAL_GUITAR,
                Voice.TWELVE_STRING_GUITAR, Voice.SEVEN_STRING_GUITAR,
                Voice.EIGHT_STRING_GUITAR, Voice.BARITONE_GUITAR, Voice.BASS, Voice.FIVE_STRING_BASS,
                Voice.SIX_STRING_BASS, Voice.FRETLESS_BASS -> guitarFamily(midi, durationSeconds, voice, velocity, upstroke, index)
                Voice.PIANO, Voice.ELECTRIC_PIANO -> pianoFamily(midi, durationSeconds, voice, velocity)
                Voice.ORGAN -> organ(midi, durationSeconds, velocity)
                Voice.SYNTH_LEAD, Voice.SYNTH_PAD -> synth(midi, durationSeconds, voice, velocity)
                Voice.VIOLIN, Voice.VIOLA, Voice.CELLO, Voice.DOUBLE_BASS -> bowed(midi, durationSeconds, voice, velocity)
                Voice.FLUTE, Voice.CLARINET, Voice.OBOE, Voice.SAXOPHONE,
                Voice.TRUMPET, Voice.TROMBONE, Voice.HARMONICA -> wind(midi, durationSeconds, voice, velocity)
                Voice.BANJO, Voice.MANDOLIN, Voice.UKULELE, Voice.HARP -> plucked(midi, durationSeconds, voice, velocity, index)
                Voice.DRUMS -> percussion(midi, durationSeconds, velocity)
            }
            val gain = 1.0 / max(1.0, midiNotes.size.toDouble().pow(0.38))
            for (i in note.indices) if (i < out.size) out[i] += note[i] * gain
        }
        val peak = out.maxOf { abs(it) }.coerceAtLeast(1e-9)
        val scale = min(0.94, 28000.0 / (peak * 32767.0))
        return ShortArray(length) { i -> (out[i] * scale * 32767.0).toInt().coerceIn(-32767, 32767).toShort() }
    }

    private fun guitarFamily(midi: Int, seconds: Double, voice: Voice, velocity: Float, upstroke: Boolean, seedOffset: Int): DoubleArray {
        val length = max(1, (seconds * sampleRate).toInt())
        val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val (partials, decay, drive) = when (voice) {
            Voice.CLASSICAL_GUITAR -> Triple(doubleArrayOf(1.0,.55,.28,.14,.07), 3.2, 1.0)
            Voice.ACOUSTIC_GUITAR -> Triple(doubleArrayOf(1.0,.68,.42,.24,.12,.06), 2.8, 1.0)
            Voice.TWELVE_STRING_GUITAR -> Triple(doubleArrayOf(1.0,.82,.55,.34,.20,.11), 3.1, 1.05)
            Voice.SEVEN_STRING_GUITAR -> Triple(doubleArrayOf(1.0,.74,.48,.30,.17,.09), 3.7, 2.5)
            Voice.EIGHT_STRING_GUITAR -> Triple(doubleArrayOf(1.0,.72,.46,.28,.15,.08), 3.9, 2.7)
            Voice.BARITONE_GUITAR -> Triple(doubleArrayOf(1.0,.66,.40,.23,.11), 4.0, 2.0)
            Voice.BASS, Voice.FIVE_STRING_BASS, Voice.SIX_STRING_BASS -> Triple(doubleArrayOf(1.0,.42,.20,.10,.05), 5.0, 1.3)
            Voice.FRETLESS_BASS -> Triple(doubleArrayOf(1.0,.50,.26,.13,.06), 5.5, 1.0)
            Voice.CLEAN_ELECTRIC_GUITAR -> Triple(doubleArrayOf(1.0,.78,.54,.35,.21,.12), 4.0, 1.35)
            else -> Triple(doubleArrayOf(1.0,.78,.55,.36,.22,.13,.08), 3.8, 2.8)
        }
        val out = DoubleArray(length)
        val rnd = Random(0xEA7A + midi * 31 + seedOffset * 97 + if (upstroke) 7 else 0)
        for (n in 0 until length) {
            val t = n.toDouble() / sampleRate
            val attack = (1.0 - exp(-n.toDouble() / (sampleRate * 0.0025))) * exp(-n.toDouble() / (sampleRate * 0.045))
            var tone = 0.0
            partials.forEachIndexed { p, a ->
                val detune = if (voice == Voice.TWELVE_STRING_GUITAR) 1.0 + p * 0.0025 else 1.0 + p * 0.0017
                tone += a * sin(2.0 * PI * f * (p + 1) * detune * t) * exp(-t / (decay / (1.0 + p * 0.32)))
            }
            if (voice == Voice.TWELVE_STRING_GUITAR) tone += .18 * sin(2.0 * PI * f * 1.003 * t) * exp(-t / 2.2)
            val pick = rnd.nextDouble(-1.0, 1.0) * exp(-t / 0.008) * if (upstroke) .78 else 1.0
            val bodyFreq = if (voice.name.contains("BASS")) 95.0 else 185.0
            var v = (tone * .20 + pick * .20 * attack + sin(2.0 * PI * bodyFreq * t) * exp(-t / .45) * .07) * velocity
            if (voice == Voice.ELECTRIC_GUITAR || voice == Voice.DISTORTION_GUITAR || voice == Voice.EIGHT_STRING_GUITAR || voice == Voice.SEVEN_STRING_GUITAR) v = tanh(v * drive) * .82
            out[n] = v
        }
        return out
    }

    private fun pianoFamily(midi: Int, seconds: Double, voice: Voice, velocity: Float): DoubleArray {
        val length = max(1, (seconds * sampleRate).toInt())
        val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val out = DoubleArray(length)
        for (n in 0 until length) {
            val t = n.toDouble() / sampleRate
            val attack = 1.0 - exp(-t / .004)
            val decay = exp(-t / if (voice == Voice.ELECTRIC_PIANO) 2.0 else 2.7)
            var v = 0.0
            for (h in 1..10) v += (1.0 / h.pow(.72)) * sin(2 * PI * f * h * t) * exp(-t / (2.0 + h * .12))
            if (voice == Voice.ELECTRIC_PIANO) v += .12 * sin(2 * PI * f * 2.01 * t) * exp(-t / 1.8)
            out[n] = v * .16 * attack * decay * velocity
        }
        return out
    }

    private fun organ(midi: Int, seconds: Double, velocity: Float): DoubleArray {
        val length = max(1, (seconds * sampleRate).toInt()); val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
        return DoubleArray(length) { n ->
            val t=n.toDouble()/sampleRate
            (sin(2*PI*f*t)+.55*sin(2*PI*f*2*t)+.32*sin(2*PI*f*3*t)+.18*sin(2*PI*f*4*t))*.18*velocity*(1-exp(-t/.02))*exp(-t/12.0)
        }
    }

    private fun synth(midi:Int, seconds:Double, voice:Voice, velocity:Float):DoubleArray {
        val length=max(1,(seconds*sampleRate).toInt()); val f=440.0*2.0.pow((midi-69)/12.0)
        return DoubleArray(length){n ->
            val t=n.toDouble()/sampleRate
            val wave=if(voice==Voice.SYNTH_PAD) (sin(2*PI*f*t)+.5*sin(2*PI*f*1.005*t)+.25*sin(2*PI*f*2*t)) else sin(2*PI*f*t)+.35*sin(2*PI*f*2*t)
            wave*.20*velocity*(1-exp(-t/(if(voice==Voice.SYNTH_PAD).35 else .01)))*exp(-t/(if(voice==Voice.SYNTH_PAD) 6.0 else 3.0))
        }
    }

    private fun bowed(midi:Int, seconds:Double, voice:Voice, velocity:Float):DoubleArray {
        val length=max(1,(seconds*sampleRate).toInt()); val f=440.0*2.0.pow((midi-69)/12.0)
        val body=when(voice){Voice.VIOLIN->1.0;Voice.VIOLA->.72;Voice.CELLO->.48;else->.30}
        return DoubleArray(length){n -> val t=n.toDouble()/sampleRate; (sin(2*PI*f*t)+.45*sin(2*PI*f*2*t)+.2*sin(2*PI*f*3*t))*body*.16*velocity*(1-exp(-t/.09))*exp(-t/6.0)}
    }

    private fun wind(midi:Int, seconds:Double, voice:Voice, velocity:Float):DoubleArray {
        val length=max(1,(seconds*sampleRate).toInt()); val f=440.0*2.0.pow((midi-69)/12.0)
        val breath=when(voice){Voice.FLUTE->.04;Voice.CLARINET->.08;Voice.OBOE->.10;Voice.SAXOPHONE->.12;Voice.TRUMPET->.15;Voice.TROMBONE->.13;else->.07}
        return DoubleArray(length){n -> val t=n.toDouble()/sampleRate; (sin(2*PI*f*t)+.3*sin(2*PI*f*3*t)+breath*Random(91+n).nextDouble(-1.0,1.0))* .20*velocity*(1-exp(-t/.04))*exp(-t/4.5)}
    }

    private fun plucked(midi:Int, seconds:Double, voice:Voice, velocity:Float, seedOffset:Int):DoubleArray {
        val length=max(1,(seconds*sampleRate).toInt()); val f=440.0*2.0.pow((midi-69)/12.0); val out=DoubleArray(length)
        val decay=when(voice){Voice.BANJO->1.7;Voice.MANDOLIN->1.4;Voice.UKULELE->2.0;else->4.0}
        for(n in 0 until length){val t=n.toDouble()/sampleRate; var v=sin(2*PI*f*t)*exp(-t/decay); v+=.28*sin(2*PI*f*2*t)*exp(-t/(decay*.6)); out[n]=v*.22*velocity}
        return out
    }

    private fun percussion(midi:Int, seconds:Double, velocity:Float):DoubleArray {
        val length=max(1,(seconds*sampleRate).toInt()); val out=DoubleArray(length); val rnd=Random(midi+91)
        val base=when(midi){36->65.0;38->180.0;42->8000.0;46->6000.0;49->9000.0;else->220.0}
        for(n in 0 until length){val t=n.toDouble()/sampleRate; val env=exp(-t/if(midi==36) .55 else .12); val noise=rnd.nextDouble(-1.0,1.0); out[n]=if(midi==36) sin(2*PI*base*t)*env*.7 else noise*env*.35}
        return out
    }

    private fun tanh(x: Double): Double {
        val e = exp(2 * x)
        return (e - 1) / (e + 1)
    }
}
