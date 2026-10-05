package com.lecturenotes.transcribe

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lecturenotes.LectureNotesApp
import com.lecturenotes.R
import com.lecturenotes.app
import com.lecturenotes.audio.AudioDecoder
import com.lecturenotes.audio.Wav
import com.lecturenotes.data.Recording
import com.lecturenotes.data.RecordingStatus
import com.lecturenotes.data.Segment
import com.lecturenotes.whisper.DeviceInfo
import com.lecturenotes.whisper.NativeSegment
import com.lecturenotes.whisper.Whisper
import com.lecturenotes.whisper.WhisperContext
import com.lecturenotes.whisper.WhisperModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decodes an imported file (if needed) and transcribes a recording chunk by chunk, resuming
 * from its checkpoint. Decoding and transcription overlap: the first chunk is transcribed as
 * soon as enough audio has been decoded, which hides most of the decode time.
 */
class ProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app = context.app
    private val recordings = app.db.recordings()
    private val recordingId = params.inputData.getLong(KEY_RECORDING_ID, -1)

    /** Set once transcription starts, so the decoder stops reporting import progress. */
    @Volatile private var transcribing = false

    override suspend fun getForegroundInfo() = foregroundInfo(applicationContext.getString(R.string.preparing), null)

    override suspend fun doWork(): Result {
        val rec = recordings.get(recordingId) ?: return Result.success()
        return try {
            setForeground(foregroundInfo(rec.title, null))
            coroutineScope {
                val decoding = if (rec.sourcePath != null) startDecode(rec) else null
                transcribe(rec, decoding)
            }
        } catch (e: InterruptedException) {
            Result.retry()
        } catch (e: Exception) {
            if (isStopped) return Result.retry()
            Log.w(TAG, "processing failed", e)
            recordings.setStatus(recordingId, RecordingStatus.FAILED, e.message ?: e.javaClass.simpleName)
            Result.failure()
        }
    }

    /** Decodes the imported file to WAV in the background; resolves to the duration in ms. */
    private fun CoroutineScope.startDecode(rec: Recording): Deferred<Long> = async(Dispatchers.IO) {
        val src = File(rec.sourcePath!!)
        recordings.setStatus(rec.id, RecordingStatus.IMPORTING)
        var last = 0f
        val duration = AudioDecoder.decodeToWav(
            src, File(rec.audioPath),
            onProgress = { p ->
                if (!transcribing && p - last >= 0.01f) {
                    last = p
                    runBlocking { recordings.setProgress(rec.id, p) }
                }
            },
            isCancelled = { isStopped },
        )
        recordings.markImported(rec.id, duration)
        src.delete()
        duration
    }

    private suspend fun transcribe(rec: Recording, decoding: Deferred<Long>?): Result {
        val model = app.models.activeModel()
        if (model == null) {
            decoding?.await()
            recordings.setStatus(rec.id, RecordingStatus.READY, NO_MODEL)
            return Result.success()
        }
        if (decoding == null) recordings.setStatus(rec.id, RecordingStatus.QUEUED)

        // One transcription at a time: two models in memory at once can exhaust RAM.
        return HeavyWork.lock.withLock {
            val current = recordings.get(rec.id) ?: return@withLock Result.success()
            withContext(Dispatchers.Default) { transcribeLocked(current, model, decoding) }
        }
    }

    private suspend fun transcribeLocked(rec: Recording, model: WhisperModel, decoding: Deferred<Long>?): Result {
        val file = File(rec.audioPath)
        // While decoding, progress is measured against the container's stated duration.
        val estimatedMs = rec.sourcePath?.let { AudioDecoder.probeDurationMs(File(it)) } ?: 0L
        Whisper.ensureBackends(applicationContext.applicationInfo.nativeLibraryDir)

        transcribing = true
        recordings.markTranscribing(rec.id, model.id)
        // Drop anything past the checkpoint, e.g. from a run that died mid-chunk.
        app.db.segments().deleteFrom(rec.id, rec.transcribedMs)

        WhisperContext.load(app.models.fileFor(model).path).use { whisper ->
            var chunkStart = Wav.msToSamples(rec.transcribedMs)
            while (true) {
                if (isStopped) return Result.retry()

                // Wait until the decoder is far enough ahead to place the next cut, or finished.
                val done = decoding == null || decoding.isCompleted
                if (decoding != null && decoding.isCompleted) decoding.await() // rethrows decode errors
                val available = Wav.sampleCount(file)
                if (!done && available <= chunkStart + ChunkPlanner.lookaheadSamples) {
                    delay(500)
                    continue
                }
                if (done && chunkStart >= available) break
                val chunkEnd = ChunkPlanner.nextCut(file, chunkStart, available)
                val totalMs = (if (done) Wav.samplesToMs(available) else estimatedMs)
                    .coerceAtLeast(Wav.samplesToMs(chunkEnd)).coerceAtLeast(1)

                val segments = transcribeChunk(whisper, rec, file, chunkStart, chunkEnd, totalMs)
                    ?: return Result.retry()

                val offsetMs = Wav.samplesToMs(chunkStart)
                val endMs = Wav.samplesToMs(chunkEnd)
                val rows = segments
                    .filter { isSpeech(it.text) }
                    .map {
                        Segment(
                            recordingId = rec.id,
                            startMs = offsetMs + it.t0Ms,
                            endMs = minOf(offsetMs + it.t1Ms, endMs),
                            text = it.text,
                        )
                    }
                app.db.withTransaction {
                    app.db.segments().insertAll(rows)
                    recordings.setCheckpoint(rec.id, endMs, endMs.toFloat() / totalMs)
                }
                chunkStart = chunkEnd
            }
        }
        recordings.setStatus(rec.id, RecordingStatus.TRANSCRIBED)
        recordings.setProgress(rec.id, 1f)
        // Label speakers next (it queues behind any other heavy work).
        val final = recordings.get(rec.id)
        if (final != null && app.settings.autoSpeakers && final.durationMs >= com.lecturenotes.speakers.SpeakerWorker.AUTO_MIN_DURATION_MS) {
            Jobs.labelSpeakers(applicationContext, rec.id)
        }
        return Result.success()
    }

    /**
     * Transcribes one chunk. If the OS takes CPUs away mid-run (common when the app goes to the
     * background), the run stops early and is retried with a thread count that fits.
     * Returns null if the worker was stopped.
     */
    private suspend fun transcribeChunk(
        whisper: WhisperContext,
        rec: Recording,
        file: File,
        start: Long,
        end: Long,
        totalMs: Long,
    ): List<NativeSegment>? {
        val samples = Wav.readFloats(file, start, (end - start).toInt())
        val offsetMs = Wav.samplesToMs(start)
        val endMs = Wav.samplesToMs(end)
        repeat(MAX_CPU_RETRIES) {
            val threads = DeviceInfo.threadsForCurrentThread()
            Log.i(TAG, "chunk at ${offsetMs / 1000}s: cpus=${Whisper.allowedCpus().joinToString(",")} threads=$threads")
            var lastPct = -1
            val result = runInterruptible(whisper) {
                // No carry-over prompt from the previous chunk: tested on-device, it makes Whisper
                // skip or misplace speech that repeats the prompt's words at the start of a chunk.
                whisper.transcribe(samples, threads, initialPrompt = null) { pct ->
                    if (pct != lastPct) {
                        lastPct = pct
                        val overall = (offsetMs + (endMs - offsetMs) * pct / 100f) / totalMs
                        runBlocking {
                            recordings.setProgress(rec.id, overall)
                            setForeground(foregroundInfo(rec.title, overall))
                        }
                    }
                }
            }
            if (result != null) return result
            if (isStopped) return null
            if (!whisper.cpuShrunk) error("Transcription failed")
        }
        error("Transcription kept being interrupted by the system")
    }

    /** Runs native decoding, aborting it if WorkManager stops this worker. */
    private fun <T> runInterruptible(whisper: WhisperContext, block: () -> T): T {
        val watcher = Thread {
            try {
                while (!isStopped) Thread.sleep(250)
                whisper.abort()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        try {
            return block()
        } finally {
            watcher.interrupt()
        }
    }

    private fun isSpeech(text: String): Boolean {
        if (text.isBlank()) return false
        // Whisper marks non-speech as e.g. "[BLANK_AUDIO]", "(music)", "[Applause]".
        return !NON_SPEECH.matches(text)
    }

    private fun foregroundInfo(title: String, progress: Float?): ForegroundInfo {
        val ctx = applicationContext
        val notification = NotificationCompat.Builder(ctx, LectureNotesApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(ctx.getString(R.string.transcribing))
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .build()
        return ForegroundInfo(
            NOTIFICATION_BASE + recordingId.toInt(), notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_RECORDING_ID = "recording_id"
        const val NO_MODEL = "Download a speech model to transcribe this recording."
        private const val TAG = "ProcessWorker"
        private const val NOTIFICATION_BASE = 1000
        private const val MAX_CPU_RETRIES = 5
        private val NON_SPEECH = Regex("""^\s*[\[(].*[])]\s*$""")
    }
}
