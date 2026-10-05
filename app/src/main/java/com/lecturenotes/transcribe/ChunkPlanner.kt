package com.lecturenotes.transcribe

import com.lecturenotes.audio.Wav
import java.io.File

/**
 * Splits long audio into ~2 minute pieces for Whisper. Each cut is placed at the
 * quietest half-second near the target, so words are not chopped in half and no
 * overlap/de-duplication is needed. Each piece is a resumable checkpoint.
 */
object ChunkPlanner {
    // Short enough that the thread count is re-checked often (usable cores change when the app
    // moves between foreground and background) and transcription of an import starts early.
    private const val TARGET_SEC = 120L
    private const val SEARCH_SEC = 15L
    private const val MAX_TAIL_SEC = 45L // don't leave a tiny final chunk
    private const val FRAME = Wav.SAMPLE_RATE / 50 // 20 ms
    private const val WINDOW_FRAMES = 25 // 0.5 s

    /**
     * Audio needed past a chunk's start before it can be cut without knowing the file's end.
     * With less than this remaining at the true end, the rest becomes one final chunk.
     */
    val lookaheadSamples: Long = (TARGET_SEC + MAX_TAIL_SEC) * Wav.SAMPLE_RATE

    /** Returns the absolute sample index where the chunk starting at [start] should end. */
    fun nextCut(file: File, start: Long, total: Long): Long {
        val sr = Wav.SAMPLE_RATE
        if (total - start <= (TARGET_SEC + MAX_TAIL_SEC) * sr) return total

        val searchStart = start + (TARGET_SEC - SEARCH_SEC) * sr
        val samples = Wav.readFloats(file, searchStart, (SEARCH_SEC * sr).toInt())
        val frames = samples.size / FRAME
        if (frames < WINDOW_FRAMES) return start + TARGET_SEC * sr

        val energy = DoubleArray(frames) { f ->
            var e = 0.0
            for (i in f * FRAME until (f + 1) * FRAME) e += samples[i] * samples[i]
            e
        }
        var window = (0 until WINDOW_FRAMES).sumOf { energy[it] }
        var best = window
        var bestFrame = 0
        for (f in WINDOW_FRAMES until frames) {
            window += energy[f] - energy[f - WINDOW_FRAMES]
            if (window < best) {
                best = window
                bestFrame = f - WINDOW_FRAMES + 1
            }
        }
        return searchStart + (bestFrame + WINDOW_FRAMES / 2).toLong() * FRAME
    }
}
