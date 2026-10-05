package com.lecturenotes.audio

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.lecturenotes.LectureNotesApp
import com.lecturenotes.MainActivity
import com.lecturenotes.R
import com.lecturenotes.app
import com.lecturenotes.data.Recording
import com.lecturenotes.data.RecordingStatus
import com.lecturenotes.transcribe.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

data class RecorderState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val recordingId: Long? = null,
    val elapsedMs: Long = 0,
    /** Input level 0..1 for the meter. */
    val level: Float = 0f,
)

/**
 * Captures 16 kHz mono PCM straight to WAV in a foreground service, so long lectures
 * survive the screen turning off and the user switching apps.
 */
class RecorderService : LifecycleService() {

    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    private var captureThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (!_state.value.active) {
                start(intent.getLongExtra(EXTRA_SUBJECT, -1).takeIf { it > 0 })
            }
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start(subjectId: Long?) {
        _state.value = RecorderState(active = true)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        lifecycleScope.launch {
            val title = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date())
            val file = File(app.audioDir, "rec_${System.currentTimeMillis()}.wav")
            val id = app.db.recordings().insert(
                Recording(
                    title = title, createdAt = System.currentTimeMillis(), audioPath = file.path,
                    status = RecordingStatus.RECORDING, subjectId = subjectId,
                ),
            )
            _state.update { it.copy(recordingId = id) }
            try {
                startCapture(file)
            } catch (e: Exception) {
                val reason = if (e is SecurityException) "Microphone permission denied" else e.message
                app.db.recordings().setStatus(id, RecordingStatus.FAILED, reason)
                finish()
                return@launch
            }
            // Refresh the notification's timer once a second.
            while (isActive && _state.value.active) {
                notificationManager().notify(NOTIFICATION_ID, buildNotification())
                delay(1000)
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startCapture(file: File) {
        val minBuf = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, Wav.SAMPLE_RATE), // >= 0.5 s of buffer
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Could not open the microphone" }
        val writer = WavWriter(file)
        captureThread = Thread({ captureLoop(record, writer) }, "audio-capture").apply { start() }
    }

    private fun captureLoop(record: AudioRecord, writer: WavWriter) {
        val chunk = ShortArray(Wav.SAMPLE_RATE / 10) // 100 ms
        var lastSync = 0L
        var recording = false
        try {
            while (!stopRequested) {
                if (paused) {
                    if (recording) { record.stop(); recording = false }
                    _state.update { it.copy(level = 0f) }
                    Thread.sleep(50)
                    continue
                }
                if (!recording) { record.startRecording(); recording = true }
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                writer.write(chunk, n)
                val elapsed = Wav.samplesToMs(writer.samplesWritten)
                _state.update { it.copy(elapsedMs = elapsed, level = levelOf(chunk, n)) }
                if (elapsed - lastSync >= 5_000) {
                    writer.syncHeader()
                    lastSync = elapsed
                }
            }
        } finally {
            if (recording) record.stop()
            record.release()
            writer.close()
        }
    }

    private fun setPaused(value: Boolean) {
        if (!_state.value.active) return
        paused = value
        _state.update { it.copy(paused = value) }
        notificationManager().notify(NOTIFICATION_ID, buildNotification())
    }

    private fun stop() {
        if (!_state.value.active) {
            stopSelf()
            return
        }
        stopRequested = true
        val id = _state.value.recordingId
        lifecycleScope.launch {
            withContext(Dispatchers.IO + NonCancellable) {
                captureThread?.join()
                if (id != null) {
                    val rec = app.db.recordings().get(id)
                    if (rec != null) {
                        val file = File(rec.audioPath)
                        if (Wav.sampleCount(file) == 0L) {
                            file.delete()
                            app.db.recordings().delete(rec)
                        } else {
                            app.db.recordings().update(rec.copy(status = RecordingStatus.READY, durationMs = Wav.durationMs(file)))
                            Jobs.process(this@RecorderService, id)
                        }
                    }
                }
            }
            finish()
        }
    }

    private fun finish() {
        _state.value = RecorderState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val s = _state.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseOrResume = if (s.paused) {
            NotificationCompat.Action(0, getString(R.string.resume), serviceIntent(ACTION_RESUME))
        } else {
            NotificationCompat.Action(0, getString(R.string.pause), serviceIntent(ACTION_PAUSE))
        }
        return NotificationCompat.Builder(this, LectureNotesApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(if (s.paused) R.string.recording_paused else R.string.recording_now))
            .setContentText(formatDuration(s.elapsedMs))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(pauseOrResume)
            .addAction(NotificationCompat.Action(0, getString(R.string.stop), serviceIntent(ACTION_STOP)))
            .build()
    }

    private fun serviceIntent(action: String) = PendingIntent.getService(
        this, action.hashCode(), Intent(this, RecorderService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationManager() = getSystemService(android.app.NotificationManager::class.java)

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "start"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_SUBJECT = "subject"

        private val _state = MutableStateFlow(RecorderState())
        val state: StateFlow<RecorderState> = _state.asStateFlow()

        /** Starts recording; the new lecture is filed under [subjectId] if given. */
        fun start(context: Context, subjectId: Long? = null) {
            val intent = Intent(context, RecorderService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_SUBJECT, subjectId ?: -1L)
            context.startForegroundService(intent)
        }
        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, RecorderService::class.java).setAction(action))
        }

        private fun levelOf(samples: ShortArray, n: Int): Float {
            var sum = 0.0
            for (i in 0 until n) sum += samples[i].toDouble() * samples[i]
            val rms = sqrt(sum / n) / 32768.0
            val db = 20 * log10(rms.coerceAtLeast(1e-5))
            return ((db + 60) / 60).toFloat().coerceIn(0f, 1f) // -60 dBFS..0 dBFS
        }
    }
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
