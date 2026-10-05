package com.lecturenotes.transcribe

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.lecturenotes.app
import com.lecturenotes.data.RecordingStatus
import com.lecturenotes.notes.NotesWorker
import com.lecturenotes.whisper.WhisperModel

object Jobs {
    fun processName(recordingId: Long) = "process-$recordingId"
    fun downloadName(model: WhisperModel) = "model-${model.id}"

    /** Imports (if needed) and transcribes a recording in the background. Safe to call repeatedly. */
    fun process(context: Context, recordingId: Long) {
        val request = OneTimeWorkRequestBuilder<ProcessWorker>()
            .setInputData(workDataOf(ProcessWorker.KEY_RECORDING_ID to recordingId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // KEEP: if this recording is already being processed, that run will finish the job.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(processName(recordingId), ExistingWorkPolicy.KEEP, request)
    }

    suspend fun cancelProcessing(context: Context, recordingId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(processName(recordingId))
        val dao = context.app.db.recordings()
        val rec = dao.get(recordingId) ?: return
        if (rec.status == RecordingStatus.QUEUED || rec.status == RecordingStatus.TRANSCRIBING ||
            rec.status == RecordingStatus.IMPORTING
        ) {
            dao.setStatus(recordingId, RecordingStatus.READY)
        }
    }

    /** Queues every recording that is waiting for a model, e.g. right after one was downloaded. */
    suspend fun processWaiting(context: Context) {
        val waiting = context.app.db.recordings().withStatus(RecordingStatus.READY, RecordingStatus.QUEUED)
        waiting.filter { it.durationMs == 0L || it.transcribedMs < it.durationMs }.forEach { process(context, it.id) }
    }

    fun extractAttachment(context: Context, attachmentId: Long) {
        val request = OneTimeWorkRequestBuilder<com.lecturenotes.docs.AttachmentWorker>()
            .setInputData(workDataOf(com.lecturenotes.docs.AttachmentWorker.KEY_ATTACHMENT_ID to attachmentId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("attachment-$attachmentId", ExistingWorkPolicy.KEEP, request)
    }

    fun speakersName(recordingId: Long) = "speakers-$recordingId"

    /** Labels who's speaking in a recording (replacing any earlier labels). */
    suspend fun labelSpeakers(context: Context, recordingId: Long) {
        context.app.db.recordings().setSpeakerStatus(recordingId, com.lecturenotes.data.SpeakerStatus.QUEUED.name, null)
        val request = OneTimeWorkRequestBuilder<com.lecturenotes.speakers.SpeakerWorker>()
            .setInputData(workDataOf(com.lecturenotes.speakers.SpeakerWorker.KEY_RECORDING_ID to recordingId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(speakersName(recordingId), ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun cancelSpeakers(context: Context, recordingId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(speakersName(recordingId))
        val dao = context.app.db.recordings()
        val status = dao.get(recordingId)?.speakerStatus
        if (status == "QUEUED" || status == "RUNNING") dao.setSpeakerStatus(recordingId, null, null)
    }

    fun notesName(recordingId: Long) = "notes-$recordingId"

    /** Queues notes generation; the Notes row (style, provider, model) must already exist. */
    fun generateNotes(context: Context, recordingId: Long) {
        val request = OneTimeWorkRequestBuilder<NotesWorker>()
            .setInputData(workDataOf(NotesWorker.KEY_RECORDING_ID to recordingId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(notesName(recordingId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelNotes(context: Context, recordingId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(notesName(recordingId))
    }

    fun downloadModel(context: Context, model: WhisperModel) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to model.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(downloadName(model), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(context: Context, model: WhisperModel) {
        WorkManager.getInstance(context).cancelUniqueWork(downloadName(model))
    }
}
