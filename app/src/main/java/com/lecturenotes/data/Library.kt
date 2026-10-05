package com.lecturenotes.data

import android.content.Context
import androidx.room.withTransaction
import com.lecturenotes.app
import com.lecturenotes.transcribe.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Actions on recordings and subjects that touch more than one table or the file system. */
object Library {
    suspend fun deleteRecordings(context: Context, ids: Collection<Long>) = withContext(Dispatchers.IO) {
        val db = context.app.db
        for (rec in db.recordings().getAll(ids.toList())) {
            Jobs.cancelProcessing(context, rec.id)
            Jobs.cancelNotes(context, rec.id)
            db.attachments().getFor(rec.id).forEach { File(it.path).delete() }
            File(rec.audioPath).delete()
            rec.sourcePath?.let { File(it).delete() }
            db.recordings().delete(rec)
        }
    }

    suspend fun moveToSubject(context: Context, ids: Collection<Long>, subjectId: Long?) =
        context.app.db.recordings().setSubject(ids.toList(), subjectId)

    suspend fun createSubject(context: Context, name: String, colorIndex: Int? = null): Long {
        val dao = context.app.db.subjects()
        val color = colorIndex ?: (dao.count() % SUBJECT_COLOR_COUNT)
        return dao.insert(Subject(name = name.trim(), colorIndex = color))
    }

    /** Deletes a subject; its lectures are kept and become unfiled. */
    suspend fun deleteSubject(context: Context, subject: Subject) {
        val db = context.app.db
        db.withTransaction {
            db.subjects().unfileAll(subject.id)
            db.subjects().delete(subject)
        }
    }

    const val SUBJECT_COLOR_COUNT = 8
}
