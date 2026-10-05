package com.lecturenotes.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/** Decodes any audio file Android can read (mp3, m4a, ogg, opus, wav, flac...) to 16 kHz mono WAV. */
object AudioDecoder {

    /** The audio track's duration from the container header, or 0 if unknown. */
    fun probeDurationMs(src: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(src.path)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)?.div(1000) ?: 0L
        } catch (e: IOException) {
            0L
        } finally {
            extractor.release()
        }
    }

    /** Returns the duration of the written WAV in ms. */
    fun decodeToWav(
        src: File,
        dst: File,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(src.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw IOException("No audio track found in this file")
        }
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFormat, null, null, 0)
        codec.start()

        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var floatPcm = false
        var resampler = Resampler(sampleRate, Wav.SAMPLE_RATE)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastReported = -1

        try {
            WavWriter(dst).use { wav ->
                var idle = false
                while (!outputDone) {
                    if (isCancelled()) throw InterruptedException("Import cancelled")
                    // Feed every free input buffer without waiting.
                    while (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(0)
                        if (inIdx < 0) break
                        val size = extractor.readSampleData(codec.getInputBuffer(inIdx)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    // Only block briefly when the codec had nothing for us last time.
                    val outIdx = codec.dequeueOutputBuffer(info, if (idle) 2_000 else 0)
                    idle = outIdx == MediaCodec.INFO_TRY_AGAIN_LATER
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            resampler = Resampler(sampleRate, Wav.SAMPLE_RATE)
                        }
                        outIdx >= 0 -> {
                            val out = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val mono = downmix(out, channels, floatPcm)
                            codec.releaseOutputBuffer(outIdx, false)
                            wav.write(resampler.process(mono))

                            if (durationUs > 0) {
                                val pct = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                                if (pct != lastReported) {
                                    lastReported = pct
                                    onProgress(pct / 100f)
                                }
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                wav.write(resampler.finish())
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return Wav.durationMs(dst)
    }

    private fun downmix(buf: java.nio.ByteBuffer, channels: Int, floatPcm: Boolean): FloatArray {
        return if (floatPcm) {
            val fb = buf.asFloatBuffer()
            val frames = fb.remaining() / channels
            FloatArray(frames) { i ->
                var s = 0f
                for (c in 0 until channels) s += fb.get(i * channels + c)
                s / channels
            }
        } else {
            val sb = buf.asShortBuffer()
            val frames = sb.remaining() / channels
            FloatArray(frames) { i ->
                var s = 0f
                for (c in 0 until channels) s += sb.get(i * channels + c)
                s / (channels * 32768f)
            }
        }
    }
}
