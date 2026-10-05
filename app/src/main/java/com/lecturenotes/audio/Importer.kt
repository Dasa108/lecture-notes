package com.lecturenotes.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lecturenotes.app
import com.lecturenotes.data.Recording
import com.lecturenotes.data.RecordingStatus
import com.lecturenotes.transcribe.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object Importer {
    /**
     * Copies a picked or shared audio or video file into app storage and queues it for
     * decoding and transcription. For videos only the audio track is kept.
     * Returns the new recording id.
     */
    suspend fun import(context: Context, uri: Uri, title: String? = null, subjectId: Long? = null): Long = withContext(Dispatchers.IO) {
        val app = context.app
        val name = displayName(context, uri) ?: "Imported audio"
        val stamp = System.currentTimeMillis()
        val isVideo = context.contentResolver.getType(uri)?.startsWith("video/") == true ||
            name.substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS

        val source = (if (isVideo) AudioExtractor.extract(context, uri, app.audioDir, "import_$stamp") else null)
            ?: copy(context, uri, File(app.audioDir, "import_$stamp.${name.substringAfterLast('.', "bin")}"))

        val id = app.db.recordings().insert(
            Recording(
                title = title ?: name.substringBeforeLast('.'),
                createdAt = stamp,
                audioPath = File(app.audioDir, "rec_$stamp.wav").path,
                sourcePath = source.path,
                status = RecordingStatus.IMPORTING,
                subjectId = subjectId,
            ),
        )
        Jobs.process(context, id)
        id
    }

    private fun copy(context: Context, uri: Uri, dst: File): File {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { input.copyTo(it) }
        } ?: throw IOException("Could not open the file")
        return dst
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov")
}
