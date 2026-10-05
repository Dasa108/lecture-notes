package com.lecturenotes.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Streaming mono resampler using a Hann-windowed sinc low-pass, so downsampling
 * 44.1/48 kHz audio to 16 kHz doesn't alias high frequencies into the speech band.
 * The filter is precomputed for [PHASES] fractional offsets to keep a 3-hour lecture fast.
 */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val table: Array<FloatArray> = buildTable()

    // buf[0] holds absolute input sample index bufStart.
    private var buf = FloatArray(8192)
    private var len = HALF_TAPS // leading silence
    private var bufStart = -HALF_TAPS.toLong()
    private var pos = 0.0 // absolute input position of the next output sample
    private var totalIn = 0L

    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        if (inRate == outRate) return input.copyOf(count)
        append(input, count)
        totalIn += count
        return drain(limit = bufStart + len - HALF_TAPS)
    }

    /** Flushes the remaining input by padding with silence. */
    fun finish(): FloatArray {
        if (inRate == outRate) return FloatArray(0)
        append(FloatArray(HALF_TAPS), HALF_TAPS)
        return drain(limit = minOf(bufStart + len - HALF_TAPS, totalIn - 1))
    }

    /** Emits outputs whose centre sample is <= [limit]. */
    private fun drain(limit: Long): FloatArray {
        val n = if (floor(pos).toLong() > limit) 0 else ((limit - pos) / step).toInt() + 1
        val out = FloatArray(n.coerceAtLeast(0))
        var produced = 0
        while (produced < out.size) {
            val center = floor(pos).toLong()
            if (center > limit) break
            val phase = ((pos - center) * PHASES).toInt().coerceAtMost(PHASES - 1)
            val kernel = table[phase]
            val base = (center - bufStart).toInt() - HALF_TAPS + 1
            var acc = 0f
            for (t in kernel.indices) acc += buf[base + t] * kernel[t]
            out[produced++] = acc
            pos += step
        }
        // Keep only the history the next output still needs.
        val drop = ((floor(pos).toLong() - HALF_TAPS + 1) - bufStart).toInt().coerceIn(0, len)
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, len - drop)
            len -= drop
            bufStart += drop
        }
        return if (produced == out.size) out else out.copyOf(produced)
    }

    private fun append(input: FloatArray, count: Int) {
        if (len + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + count))
        System.arraycopy(input, 0, buf, len, count)
        len += count
    }

    private fun buildTable(): Array<FloatArray> {
        val cutoff = 0.95 * minOf(1.0, outRate.toDouble() / inRate) // fraction of input Nyquist
        return Array(PHASES) { p ->
            val frac = p.toDouble() / PHASES
            FloatArray(HALF_TAPS * 2) { t ->
                val x = frac - (t - HALF_TAPS + 1) // distance from output position to tap sample
                val sinc = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
                val window = 0.5 + 0.5 * cos(PI * x / HALF_TAPS)
                (cutoff * sinc * window).toFloat()
            }
        }
    }

    private companion object {
        const val HALF_TAPS = 16
        const val PHASES = 256
    }
}
