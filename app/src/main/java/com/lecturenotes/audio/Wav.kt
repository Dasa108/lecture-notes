package com.lecturenotes.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** All app audio is canonical 16 kHz mono PCM16 WAV, the format Whisper expects. */
object Wav {
    const val SAMPLE_RATE = 16_000
    const val HEADER_BYTES = 44L

    fun samplesToMs(samples: Long) = samples * 1000 / SAMPLE_RATE
    fun msToSamples(ms: Long) = ms * SAMPLE_RATE / 1000

    fun sampleCount(file: File): Long = ((file.length() - HEADER_BYTES).coerceAtLeast(0)) / 2

    fun durationMs(file: File): Long = samplesToMs(sampleCount(file))

    /** Rewrites the size fields from the actual file length, e.g. after a crash mid-recording. */
    fun repairHeader(file: File) {
        RandomAccessFile(file, "rw").use { writeHeader(it, (it.length() - HEADER_BYTES).coerceAtLeast(0)) }
    }

    /** Reads [count] samples starting at sample [start] as floats in [-1, 1]. */
    fun readFloats(file: File, start: Long, count: Int): FloatArray {
        RandomAccessFile(file, "r").use { raf ->
            val available = ((raf.length() - HEADER_BYTES) / 2 - start).coerceAtLeast(0)
            val n = minOf(count.toLong(), available).toInt()
            val bytes = ByteArray(n * 2)
            raf.seek(HEADER_BYTES + start * 2)
            raf.readFully(bytes)
            val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(n) { shorts.get(it) / 32768f }
        }
    }

    internal fun writeHeader(raf: RandomAccessFile, dataBytes: Long) {
        val header = ByteBuffer.allocate(HEADER_BYTES.toInt()).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2) // byte rate
            putShort(2) // block align
            putShort(16) // bits per sample
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }
        raf.seek(0)
        raf.write(header.array())
    }
}

/** Appends PCM16 samples to a WAV file. Call [syncHeader] periodically so a crash leaves a playable file. */
class WavWriter(file: File, append: Boolean = false) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes: Long
    private val buf = ByteBuffer.allocate(16_384).order(ByteOrder.LITTLE_ENDIAN)

    init {
        if (append && raf.length() >= Wav.HEADER_BYTES) {
            dataBytes = raf.length() - Wav.HEADER_BYTES
        } else {
            raf.setLength(0)
            dataBytes = 0
            Wav.writeHeader(raf, 0)
        }
        raf.seek(Wav.HEADER_BYTES + dataBytes)
    }

    val samplesWritten: Long get() = dataBytes / 2

    fun write(samples: ShortArray, count: Int = samples.size) {
        var i = 0
        while (i < count) {
            buf.clear()
            val n = minOf(count - i, buf.capacity() / 2)
            for (k in 0 until n) buf.putShort(samples[i + k])
            raf.write(buf.array(), 0, n * 2)
            i += n
        }
        dataBytes += count * 2L
    }

    fun write(samples: FloatArray, count: Int = samples.size) {
        val shorts = ShortArray(count) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        write(shorts)
    }

    fun syncHeader() {
        Wav.writeHeader(raf, dataBytes)
        raf.seek(Wav.HEADER_BYTES + dataBytes)
        raf.fd.sync()
    }

    override fun close() {
        Wav.writeHeader(raf, dataBytes)
        raf.close()
    }
}
