package com.lecturenotes.speakers

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
import com.lecturenotes.data.Speaker
import com.lecturenotes.data.SpeakerStatus
import com.lecturenotes.transcribe.HeavyWork
import com.lecturenotes.whisper.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Labels who is speaking in a recording and tags each transcript line with its speaker. */
class SpeakerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.app
    private val recordingId = params.inputData.getLong(KEY_RECORDING_ID, -1)

    override suspend fun getForegroundInfo() = foregroundInfo()

    override suspend fun doWork(): Result {
        val rec = app.db.recordings().get(recordingId) ?: return Result.success()
        val wav = File(rec.audioPath)
        if (!wav.exists()) return Result.success()
        setForeground(foregroundInfo())
        val recordings = app.db.recordings()
        return try {
            recordings.setSpeakerStatus(recordingId, SpeakerStatus.RUNNING.name, 0f)
            SpeakerModels.ensure(applicationContext) { p -> runBlocking { recordings.setSpeakerStatus(recordingId, SpeakerStatus.RUNNING.name, p * 0.1f) } }
            val turns = HeavyWork.lock.withLock {
                withContext(Dispatchers.Default) {
                    Diarizer(applicationContext, DeviceInfo.threadsForCurrentThread()).use { d ->
                        var last = 0f
                        d.run(wav, onProgress = { p ->
                            if (p - last >= 0.02f || p >= 1f) {
                                last = p
                                runBlocking { recordings.setSpeakerStatus(recordingId, SpeakerStatus.RUNNING.name, 0.1f + p * 0.9f) }
                            }
                        }, isCancelled = { isStopped })
                    }
                }
            }
            save(turns)
            recordings.setSpeakerStatus(recordingId, SpeakerStatus.DONE.name, null)
            Result.success()
        } catch (e: InterruptedException) {
            Result.retry()
        } catch (e: Exception) {
            if (isStopped) return Result.retry()
            Log.w(TAG, "speaker labelling failed", e)
            recordings.setSpeakerStatus(recordingId, SpeakerStatus.FAILED.name, null)
            Result.failure()
        }
    }

    /**
     * Speakers are numbered by talk time; the one who talks most is suggested as the lecturer.
     * Voices with under [MIN_TALK_MS] in total (coughs, a door) are dropped.
     */
    private suspend fun save(turns: List<Turn>) {
        val db = app.db
        val talk = SpeakerLinker.talkTime(turns).filterValues { it >= MIN_TALK_MS }
        val ranked = talk.entries.sortedByDescending { it.value }.map { it.key }
        val kept = turns.filter { it.speaker in talk }
        val segments = db.segments().getFor(recordingId)
        val assigned = SpeakerLinker.assign(segments.map { it.startMs..it.endMs }, kept)
        db.withTransaction {
            db.segments().clearSpeakers(recordingId)
            db.speakers().deleteFor(recordingId)
            val ids = HashMap<Int, Long>()
            ranked.forEachIndexed { i, g ->
                ids[g] = db.speakers().insert(
                    Speaker(
                        recordingId = recordingId,
                        number = i + 1,
                        name = if (i == 0) "Lecturer" else "Speaker ${i + 1}",
                        isLecturer = i == 0,
                        talkMs = talk.getValue(g),
                    ),
                )
            }
            segments.zip(assigned).forEach { (seg, g) -> db.segments().setSpeaker(seg.id, g?.let(ids::get)) }
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, LectureNotesApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(applicationContext.getString(R.string.labelling_speakers))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(NOTIFICATION_BASE + recordingId.toInt(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_RECORDING_ID = "recording_id"
        private const val TAG = "SpeakerWorker"
        private const val NOTIFICATION_BASE = 9000
        private const val MIN_TALK_MS = 3_000L

        /** Recordings shorter than this are not labelled automatically (still possible by hand). */
        const val AUTO_MIN_DURATION_MS = 2 * 60_000L
    }
}
