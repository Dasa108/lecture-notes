package com.lecturenotes

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.lecturenotes.audio.RecorderService
import com.lecturenotes.audio.Wav
import com.lecturenotes.data.AppDatabase
import com.lecturenotes.data.RecordingStatus
import com.lecturenotes.notes.ProviderSettings
import com.lecturenotes.transcribe.Jobs
import com.lecturenotes.whisper.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class LectureNotesApp : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var models: ModelRepository
        private set
    lateinit var aiSettings: ProviderSettings
        private set

    /** Lives as long as the process; for work that must outlive a screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val audioDir: File by lazy { File(filesDir, "audio").apply { mkdirs() } }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.create(this)
        models = ModelRepository(this)
        aiSettings = ProviderSettings(this)
        createNotificationChannels()
        appScope.launch { recoverInterruptedRecordings() }
    }

    /** A recording left in RECORDING state means the process died mid-capture; keep what was saved. */
    private suspend fun recoverInterruptedRecordings() {
        if (RecorderService.state.value.active) return
        for (rec in db.recordings().withStatus(RecordingStatus.RECORDING)) {
            val file = File(rec.audioPath)
            if (!file.exists() || Wav.sampleCount(file) == 0L) {
                file.delete()
                db.recordings().delete(rec)
                continue
            }
            Wav.repairHeader(file)
            db.recordings().update(rec.copy(status = RecordingStatus.READY, durationMs = Wav.durationMs(file)))
            Jobs.process(this, rec.id)
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_RECORDING, getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_WORK, getString(R.string.channel_work), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_WORK = "work"
    }
}

val android.content.Context.app: LectureNotesApp get() = applicationContext as LectureNotesApp
