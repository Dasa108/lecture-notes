package com.lecturenotes.docs

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
import com.lecturenotes.data.AttachmentPage
import com.lecturenotes.data.AttachmentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** Extracts the text of an attached PDF/PPTX/image/text file, page by page. */
class AttachmentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val dao = context.app.db.attachments()
    private val attachmentId = params.inputData.getLong(KEY_ATTACHMENT_ID, -1)

    override suspend fun getForegroundInfo() = foregroundInfo()

    override suspend fun doWork(): Result {
        val a = dao.get(attachmentId) ?: return Result.success()
        setForeground(foregroundInfo())
        dao.setStatus(a.id, AttachmentStatus.EXTRACTING)
        dao.clearPages(a.id)
        return try {
            withContext(Dispatchers.IO) {
                TextExtractor.extract(applicationContext, a.kind, File(a.path), isCancelled = { isStopped }) { page, total ->
                    runBlocking {
                        dao.insertPage(AttachmentPage(attachmentId = a.id, number = page.number, text = page.text, ocr = page.ocr))
                        dao.setProgress(a.id, page.number.toFloat() / total, total)
                    }
                }
            }
            dao.setStatus(a.id, AttachmentStatus.DONE)
            Result.success()
        } catch (e: InterruptedException) {
            Result.retry()
        } catch (e: Exception) {
            if (isStopped) return Result.retry()
            Log.w(TAG, "extraction failed", e)
            dao.setStatus(a.id, AttachmentStatus.FAILED, friendly(e))
            Result.failure()
        }
    }

    private fun friendly(e: Exception): String = when {
        e.javaClass.simpleName.contains("InvalidPassword") -> "This PDF is password-protected."
        e is java.util.zip.ZipException -> "This doesn't look like a valid PowerPoint (.pptx) file."
        else -> "Couldn't read this file: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun foregroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, LectureNotesApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(applicationContext.getString(R.string.reading_slides))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(NOTIFICATION_BASE + attachmentId.toInt(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_ATTACHMENT_ID = "attachment_id"
        private const val TAG = "AttachmentWorker"
        private const val NOTIFICATION_BASE = 7000
    }
}
