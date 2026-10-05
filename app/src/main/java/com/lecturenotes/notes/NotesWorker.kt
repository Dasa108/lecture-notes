package com.lecturenotes.notes

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lecturenotes.LectureNotesApp
import com.lecturenotes.R
import com.lecturenotes.app
import com.lecturenotes.data.NotesStatus
import java.io.IOException

/** Generates study notes for a recording in the background, resuming a split run if interrupted. */
class NotesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app = context.app
    private val dao = app.db.notes()
    private val recordingId = params.inputData.getLong(KEY_RECORDING_ID, -1)

    override suspend fun getForegroundInfo() = foregroundInfo(null)

    override suspend fun doWork(): Result {
        val notes = dao.getFor(recordingId) ?: return Result.success()
        val rec = app.db.recordings().get(recordingId) ?: return Result.success()
        val preset = ProviderPreset.byId(notes.provider) ?: return fail("Unknown AI provider")
        val config = app.aiSettings.configFor(preset).copy(model = notes.model)
        val style = runCatching { NoteStyle.valueOf(notes.style) }.getOrDefault(NoteStyle.STANDARD)

        val lines = app.db.segments().getFor(recordingId).map { TranscriptLine(it.startMs, it.text) }
        if (lines.isEmpty()) return fail("This lecture has no transcript yet.")
        val subject = rec.subjectId?.let { app.db.subjects().get(it) }

        setForeground(foregroundInfo(null))
        dao.setStatus(recordingId, NotesStatus.GENERATING, "Starting…")
        val generator = NotesGenerator(LlmClient.create(config), config, object : NotesProgress {
            override fun status(message: String, fraction: Float?) {
                kotlinx.coroutines.runBlocking {
                    // Status-only updates (e.g. rate-limit waits) keep the last known progress.
                    if (fraction == null) dao.setMessage(recordingId, message) else dao.setProgress(recordingId, fraction, message)
                    if (fraction != null) runCatching { setForeground(foregroundInfo(fraction)) }
                }
            }

            override suspend fun saved(run: PartialRun) = dao.setPartial(recordingId, run.toJson())
        })
        return try {
            val result = generator.generate(
                NotesInput(
                    rec.title, subject?.name, style, lines, rec.durationMs.coerceAtLeast(lines.last().startMs),
                    systemPrompt = Prompts.systemPrompt(subject),
                ),
                PartialRun.parse(notes.partialJson),
            )
            dao.finish(recordingId, result.toJson())
            Result.success()
        } catch (e: LlmException.Auth) {
            fail("${preset.label} rejected your API key. Check it in Settings → Notes AI.")
        } catch (e: LlmException.ModelNotFound) {
            fail("The model “${notes.model}” isn't available on ${preset.label}. Pick another in Settings → Notes AI.")
        } catch (e: IOException) {
            if (isStopped) return Result.retry()
            Log.w(TAG, "notes failed", e)
            fail(e.message ?: "Something went wrong while writing notes.")
        }
    }

    private suspend fun fail(message: String): Result {
        dao.setStatus(recordingId, NotesStatus.FAILED, message)
        return Result.failure()
    }

    private fun foregroundInfo(progress: Float?): ForegroundInfo {
        val ctx = applicationContext
        val determinate = progress != null && progress >= 0f
        val notification = NotificationCompat.Builder(ctx, LectureNotesApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(ctx.getString(R.string.writing_notes))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (determinate) (progress!! * 100).toInt() else 0, !determinate)
            .build()
        return ForegroundInfo(NOTIFICATION_BASE + recordingId.toInt(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_RECORDING_ID = "recording_id"
        private const val TAG = "NotesWorker"
        private const val NOTIFICATION_BASE = 5000
    }
}
