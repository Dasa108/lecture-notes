package com.lecturenotes.docs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lecturenotes.app
import com.lecturenotes.data.Attachment
import com.lecturenotes.data.Recording
import com.lecturenotes.data.RecordingStatus
import com.lecturenotes.transcribe.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Imports slides/PDFs/photos/text, either as a new slides-only lecture or attached to an existing one. */
object DocumentImporter {

    /** True if [uri] is a document type we can read (as opposed to audio/video). */
    fun isDocument(context: Context, uri: Uri): Boolean =
        TextExtractor.kindFor(displayName(context, uri) ?: "", context.contentResolver.getType(uri)) != null

    /** Creates a lecture with no recording from a document. Returns the new lecture's id. */
    suspend fun importAsLecture(context: Context, uri: Uri, subjectId: Long? = null): Long = withContext(Dispatchers.IO) {
        val app = context.app
        val name = displayName(context, uri) ?: "Slides"
        val id = app.db.recordings().insert(
            Recording(
                title = name.substringBeforeLast('.'),
                createdAt = System.currentTimeMillis(),
                audioPath = "",
                status = RecordingStatus.DOCUMENT,
                subjectId = subjectId,
            ),
        )
        attach(context, uri, id)
        id
    }

    /** Adds a document to an existing lecture and starts reading its text. */
    suspend fun attach(context: Context, uri: Uri, recordingId: Long): Long = withContext(Dispatchers.IO) {
        val app = context.app
        val name = displayName(context, uri) ?: "Document"
        val kind = TextExtractor.kindFor(name, context.contentResolver.getType(uri))
            ?: throw IOException("Unsupported file type. Use PDF, PowerPoint (.pptx), an image or a text file.")
        val dir = File(app.filesDir, "attachments").apply { mkdirs() }
        val dst = File(dir, "${System.currentTimeMillis()}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        context.contentResolver.openInputStream(uri)?.use { input -> dst.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("Could not open the file")
        val id = app.db.attachments().insert(Attachment(recordingId = recordingId, kind = kind, fileName = name, path = dst.path))
        Jobs.extractAttachment(context, id)
        id
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
}
