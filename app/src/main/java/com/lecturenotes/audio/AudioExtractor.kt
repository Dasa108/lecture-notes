package com.lecturenotes.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Pulls the audio track out of a video without re-encoding it, so a large lecture
 * video costs only its (small) audio in app storage and takes seconds, not minutes.
 */
object AudioExtractor {

    /** Thrown when the video has no sound to transcribe. */
    class NoAudioException : IOException("This video has no sound to transcribe.")

    /**
     * Writes the audio track of [uri] to a new file in [dir] and returns it,
     * or null if the codec can't be remuxed (the caller then copies the whole file).
     */
    fun extract(context: Context, uri: Uri, dir: File, baseName: String): File? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw NoAudioException()
            val format = extractor.getTrackFormat(track)
            val (container, ext) = containerFor(format.getString(MediaFormat.KEY_MIME)!!) ?: return null

            val out = File(dir, "$baseName.$ext")
            val muxer = MediaMuxer(out.path, container)
            try {
                val dst = muxer.addTrack(format)
                muxer.start()
                extractor.selectTrack(track)
                val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    1 shl 20
                }
                val buf = ByteBuffer.allocate(maxOf(maxSize, 64 * 1024))
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) break
                    info.set(0, size, extractor.sampleTime, toCodecFlags(extractor.sampleFlags))
                    muxer.writeSampleData(dst, buf, info)
                    extractor.advance()
                }
                muxer.stop()
            } catch (e: Exception) {
                out.delete()
                // Some containers/codecs the muxer rejects part-way; fall back to a full copy.
                return null
            } finally {
                runCatching { muxer.release() }
            }
            return out
        } finally {
            extractor.release()
        }
    }

    private fun containerFor(mime: String): Pair<Int, String>? = when (mime) {
        MediaFormat.MIMETYPE_AUDIO_AAC,
        MediaFormat.MIMETYPE_AUDIO_AMR_NB,
        MediaFormat.MIMETYPE_AUDIO_AMR_WB -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 to "m4a"
        MediaFormat.MIMETYPE_AUDIO_OPUS,
        MediaFormat.MIMETYPE_AUDIO_VORBIS -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM to "webm"
        else -> null
    }

    private fun toCodecFlags(sampleFlags: Int): Int =
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
}
