package com.earam.tabs

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Lightweight plucked-string guitar model.
 *
 * This is intentionally not a sine-wave generator: each string is excited by a
 * short pick transient and fed through a Karplus-Strong style delay line with
 * damping, body resonance and a small bridge component.
 */
class GuitarSoundEngine(
    private val sampleRate: Int = 44100
) {
    enum class Voice { ELECTRIC_GUITAR, ACOUSTIC_GUITAR, BASS }

    fun render(
        midiNotes: List<Int>,
        durationSeconds: Double,
        voice: Voice,
        velocity: Float = 0.9f,
        upstroke: Boolean = false
    ): ShortArray {
        val length = max(1, (durationSeconds * sampleRate).toInt())
        if (midiNotes.isEmpty()) return ShortArray(length)

        val out = DoubleArray(length)
        midiNotes.forEachIndexed { index, midi ->
            val stringSeconds = durationSeconds * (0.92 + 0.06 * index.coerceAtMost(1))
            val rendered = pluck(midi, stringSeconds, voice, velocity, upstroke, index)
            val gain = 1.0 / max(1.0, midiNotes.size.toDouble().pow(0.42))
            for (i in rendered.indices) {
                if (i >= out.size) break
                out[i] += rendered[i] * gain
            }
        }

        val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        val scale = min(0.92, 28000.0 / (peak * 32767.0))
        return ShortArray(length) { i ->
            (out[i] * scale * 32767.0).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun pluck(
        midi: Int,
        seconds: Double,
        voice: Voice,
        velocity: Float,
        upstroke: Boolean,
        seedOffset: Int
    ): DoubleArray {
        val length = max(1, (seconds * sampleRate).toInt())
        val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val period = max(2, (sampleRate / f).toInt())
        val delay = DoubleArray(period)
        val random = Random(0xEA7A + midi * 31 + seedOffset * 97 + if (upstroke) 7 else 0)

        // A picked string starts noisy rather than as a mathematically pure tone.
        for (i in delay.indices) {
            val envelope = 1.0 - i.toDouble() / delay.size
            delay[i] = random.nextDouble(-1.0, 1.0) * envelope
        }

        val result = DoubleArray(length)
        var lp = 0.0
        val damping = when (voice) {
            Voice.ACOUSTIC_GUITAR -> 0.9965
            Voice.ELECTRIC_GUITAR -> 0.9975
            Voice.BASS -> 0.9985
        }
        val brightness = when (voice) {
            Voice.ACOUSTIC_GUITAR -> 0.72
            Voice.ELECTRIC_GUITAR -> 0.88
            Voice.BASS -> 0.58
        }
        val bodyFreq = when (voice) {
            Voice.ACOUSTIC_GUITAR -> 150.0
            Voice.ELECTRIC_GUITAR -> 210.0
            Voice.BASS -> 95.0
        }

        for (n in 0 until length) {
            val idx = n % period
            val next = (idx + 1) % period
            val filtered = (delay[idx] + delay[next]) * 0.5
            val high = delay[idx] - lp
            lp = lp * 0.82 + delay[idx] * 0.18
            delay[idx] = filtered * damping

            val t = n.toDouble() / sampleRate
            val attack = if (n < sampleRate * 0.018) {
                val a = n.toDouble() / (sampleRate * 0.018)
                (1.0 - a).pow(1.8) * if (upstroke) 0.78 else 1.0
            } else 0.0
            val decay = exp(-t / when (voice) {
                Voice.ACOUSTIC_GUITAR -> 2.2
                Voice.ELECTRIC_GUITAR -> 3.4
                Voice.BASS -> 4.8
            })

            val body = sin(2.0 * PI * bodyFreq * t) * exp(-t / 0.42) * 0.055
            val string = delay[idx] * (0.76 + brightness * 0.20)
            val pick = high * attack * 0.32
            result[n] = (string + pick + body) * velocity * decay
        }

        return result
    }

}
