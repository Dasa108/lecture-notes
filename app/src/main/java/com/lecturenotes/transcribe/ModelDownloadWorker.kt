package com.lecturenotes.transcribe

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lecturenotes.LectureNotesApp
import com.lecturenotes.R
import com.lecturenotes.app
import com.lecturenotes.whisper.WhisperModel
import com.lecturenotes.whisper.WhisperModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads a Whisper model with resume support and verifies its SHA-256. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val models = context.app.models
    private val model: WhisperModel? = WhisperModels.byId(params.inputData.getString(KEY_MODEL_ID))

    override suspend fun getForegroundInfo() = foregroundInfo(0f)

    override suspend fun doWork(): Result {
        val model = model ?: return Result.failure()
        if (models.isDownloaded(model)) return Result.success()
        setForeground(foregroundInfo(0f))

        val part = models.partFileFor(model)
        return try {
            withContext(Dispatchers.IO) { download(model, part) }
            if (isStopped) return Result.retry()
            setProgress(workDataOf(KEY_PROGRESS to 1f, KEY_VERIFYING to true))
            val hash = withContext(Dispatchers.IO) { sha256(part) }
            if (!hash.equals(model.sha256, ignoreCase = true)) {
                part.delete()
                return Result.failure(workDataOf(KEY_ERROR to "Download was corrupted. Please try again."))
            }
            if (!part.renameTo(models.fileFor(model))) throw IOException("Could not save model")
            if (models.activeModel() == null || models.selectedId.value == model.id) models.select(model)
            models.notifyChanged()
            Jobs.processWaiting(applicationContext)
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
        }
    }

    private suspend fun download(model: WhisperModel, part: File) {
        var have = if (part.exists()) part.length() else 0L
        if (have >= model.sizeBytes) return

        val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) have = 0 // server ignored Range; start over
            else if (code != HttpURLConnection.HTTP_PARTIAL) throw IOException("Server returned $code")

            conn.inputStream.use { input ->
                FileOutputStream(part, have > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (isStopped) return
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have - lastReport > 512 * 1024) {
                            lastReport = have
                            val p = have.toFloat() / model.sizeBytes
                            setProgress(workDataOf(KEY_PROGRESS to p))
                            setForeground(foregroundInfo(p))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (have < model.sizeBytes) throw IOException("Connection lost")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun foregroundInfo(progress: Float): ForegroundInfo {
        val ctx = applicationContext
        val notification = NotificationCompat.Builder(ctx, LectureNotesApp.CHANNEL_WORK)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(ctx.getString(R.string.downloading_model, model?.displayName ?: ""))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (progress * 100).toInt(), false)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_VERIFYING = "verifying"
        const val KEY_ERROR = "error"
        private const val NOTIFICATION_ID = 2
    }
}
