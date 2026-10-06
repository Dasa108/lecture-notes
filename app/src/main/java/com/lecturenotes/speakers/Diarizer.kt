package com.lecturenotes.speakers

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.lecturenotes.audio.Wav
import java.io.File

/**
 * On-device speaker diarization with sherpa-onnx (pyannote segmentation + CAM++ embeddings).
 * The recording is processed in [PIECE_SEC] pieces so memory stays small on long lectures; the
 * speakers of each piece are linked across pieces by their voice embeddings ([SpeakerLinker]).
 */
class Diarizer(context: Context, threads: Int) : AutoCloseable {
    private val diarization = OfflineSpeakerDiarization(
        config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(SpeakerModels.segmentation(context).path),
                numThreads = threads,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(SpeakerModels.embedding(context).path, numThreads = threads),
            clustering = FastClusteringConfig(numClusters = -1, threshold = CLUSTER_THRESHOLD),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        ),
    )
    private val extractor = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(SpeakerModels.embedding(context).path, numThreads = threads),
    )

    /**
     * Speaker turns for a whole 16 kHz WAV, with global speaker ids.
     * [onProgress] gets 0..1; [isCancelled] is checked between pieces.
     */
    fun run(
        wav: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        /** Called before each piece; may block (e.g. to let a hot phone cool down). */
        beforePiece: () -> Unit = {},
    ): List<Turn> {
        val total = Wav.sampleCount(wav)
        val pieceSamples = PIECE_SEC * Wav.SAMPLE_RATE
        val linker = SpeakerLinker()
        val turns = mutableListOf<Turn>()
        var start = 0L
        while (start < total) {
            beforePiece()
            if (isCancelled()) throw InterruptedException("Cancelled")
            // Fold a short remainder into the last piece rather than diarizing a few seconds alone.
            val end = if (total - start < pieceSamples + MIN_PIECE_SEC * Wav.SAMPLE_RATE) total else start + pieceSamples
            val samples = Wav.readFloats(wav, start, (end - start).toInt())
            val offsetMs = Wav.samplesToMs(start)

            val segments = diarization.process(samples)
            if (segments.isNotEmpty()) {
                val locals = segments.map { it.speaker }.distinct().sorted()
                val embeddings = locals.map { local ->
                    embed(samples, segments.filter { it.speaker == local }.map { it.start to it.end })
                }
                val global = linker.link(embeddings)
                val toGlobal = locals.zip(global).toMap()
                segments.forEach { s ->
                    turns += Turn(offsetMs + (s.start * 1000).toLong(), offsetMs + (s.end * 1000).toLong(), toGlobal.getValue(s.speaker))
                }
            }
            start = end
            onProgress(start.toFloat() / total)
        }
        return SpeakerLinker.mergeAdjacent(turns)
    }

    /** One embedding for a speaker from up to [EMBED_SEC] of their longest turns in this piece. */
    private fun embed(samples: FloatArray, spans: List<Pair<Float, Float>>): FloatArray {
        val stream = extractor.createStream()
        try {
            var used = 0f
            for ((s, e) in spans.sortedByDescending { it.second - it.first }) {
                if (used >= EMBED_SEC) break
                val from = (s * Wav.SAMPLE_RATE).toInt().coerceIn(0, samples.size)
                val to = (e * Wav.SAMPLE_RATE).toInt().coerceIn(from, samples.size)
                if (to - from < Wav.SAMPLE_RATE / 2) continue // skip blips shorter than 0.5 s
                stream.acceptWaveform(samples.copyOfRange(from, to), Wav.SAMPLE_RATE)
                used += e - s
            }
            if (used == 0f) {
                // Only very short turns: use them all rather than nothing.
                spans.forEach { (s, e) ->
                    val from = (s * Wav.SAMPLE_RATE).toInt().coerceIn(0, samples.size)
                    val to = (e * Wav.SAMPLE_RATE).toInt().coerceIn(from, samples.size)
                    if (to > from) stream.acceptWaveform(samples.copyOfRange(from, to), Wav.SAMPLE_RATE)
                }
            }
            stream.inputFinished()
            return if (extractor.isReady(stream)) extractor.compute(stream) else FloatArray(extractor.dim())
        } finally {
            stream.release()
        }
    }

    override fun close() {
        diarization.release()
        extractor.release()
    }

    companion object {
        const val PIECE_SEC = 600L
        private const val MIN_PIECE_SEC = 120L
        private const val EMBED_SEC = 30f
        /**
         * How alike two stretches of voice must be to count as one speaker (higher merges more).
         * sherpa-onnx's default of 0.5 split one lecturer into 8 "speakers" in 10 minutes of a real
         * classroom recording (the voice changes with distance, facing the board, room echo); 0.9 gave
         * 2 voices and stayed at 2 up to 1.2. Users can still merge leftovers by ear in the app.
         */
        private const val CLUSTER_THRESHOLD = 0.9f
    }
}
