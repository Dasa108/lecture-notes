package com.lecturenotes.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Re-encodes a transcribed lecture's 16 kHz WAV (~115 MB/hour) as AAC speech audio in .m4a
 * (~14 MB/hour). The WAV is only needed again for re-transcribing or speaker labelling, which
 * decode the .m4a back to a temporary WAV.
 */
object AudioCompressor {
    private const val BIT_RATE = 32_000
    private const val CHUNK_SAMPLES = 1024

    /** Writes [dst] from [wav]; verifies its duration before returning. Does not delete [wav]. */
    fun compress(wav: File, dst: File, isCancelled: () -> Boolean = { false }) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Wav.SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_SAMPLES * 2 * 4)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        val totalSamples = Wav.sampleCount(wav)
        var fed = 0L
        var inputDone = false
        var outputDone = false
        try {
            RandomAccessFile(wav, "r").use { raf ->
                raf.seek(Wav.HEADER_BYTES)
                val bytes = ByteArray(CHUNK_SAMPLES * 2)
                while (!outputDone) {
                    if (isCancelled()) throw InterruptedException("Cancelled")
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(5_000)
                        if (idx >= 0) {
                            val n = raf.read(bytes).coerceAtLeast(0)
                            val ptsUs = fed * 1_000_000 / Wav.SAMPLE_RATE
                            if (n == 0) {
                                codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.getInputBuffer(idx)!!.apply { clear(); put(bytes, 0, n) }
                                codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
                                fed += n / 2
                            }
                        }
                    }
                    val out = codec.dequeueOutputBuffer(info, 5_000)
                    when {
                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                        }
                        out >= 0 -> {
                            val buf = codec.getOutputBuffer(out)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buf, info)
                            }
                            codec.releaseOutputBuffer(out, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            dst.delete()
            throw e
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
        // Don't let the caller delete the original unless the copy is the full length.
        val expectedMs = Wav.samplesToMs(totalSamples)
        val gotMs = AudioDecoder.probeDurationMs(dst)
        if (abs(gotMs - expectedMs) > 2_000) {
            dst.delete()
            throw IOException("Compressed audio is ${gotMs}ms, expected ${expectedMs}ms")
        }
    }

    /** True for app audio that has been compressed (anything but our own WAV). */
    fun isCompressed(path: String) = path.isNotEmpty() && !path.endsWith(".wav", ignoreCase = true)
}
