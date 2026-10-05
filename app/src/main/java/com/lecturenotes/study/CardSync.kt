package com.lecturenotes.study

import androidx.room.withTransaction
import com.lecturenotes.data.AppDatabase
import com.lecturenotes.data.ReviewCard
import com.lecturenotes.notes.StudyNotes
import org.json.JSONObject

/** Keeps a lecture's review deck in step with its notes. */
object CardSync {
    /**
     * Cards whose question is unchanged keep their review schedule (the answer and timestamp are
     * refreshed); new questions start as new cards; questions no longer in the notes are removed.
     */
    suspend fun sync(db: AppDatabase, recordingId: Long, notes: StudyNotes) {
        val dao = db.reviewCards()
        db.withTransaction {
            val existing = dao.getFor(recordingId).associateBy { key(it.front) }
            val wanted = notes.flashcards.distinctBy { key(it.front) }
            val now = System.currentTimeMillis()
            val toInsert = mutableListOf<ReviewCard>()
            val toUpdate = mutableListOf<ReviewCard>()
            for (f in wanted) {
                val old = existing[key(f.front)]
                if (old == null) {
                    toInsert += ReviewCard(recordingId = recordingId, front = f.front, back = f.back, atMs = f.atMs, dueAt = now)
                } else if (old.back != f.back || old.atMs != f.atMs || old.front != f.front) {
                    toUpdate += old.copy(front = f.front, back = f.back, atMs = f.atMs)
                }
            }
            val keep = wanted.map { key(it.front) }.toSet()
            val toDelete = existing.filterKeys { it !in keep }.values.map { it.id }
            if (toDelete.isNotEmpty()) dao.deleteIds(toDelete)
            if (toUpdate.isNotEmpty()) dao.updateAll(toUpdate)
            if (toInsert.isNotEmpty()) dao.insertAll(toInsert)
        }
    }

    /** One-time backfill for notes made before study mode existed. */
    suspend fun backfill(db: AppDatabase) {
        val have = db.reviewCards().recordingsWithCards().toSet()
        for (n in db.notes().allDone()) {
            if (n.recordingId in have) continue
            val parsed = runCatching { StudyNotes.parse(JSONObject(n.json!!), Long.MAX_VALUE / 2, "") }.getOrNull() ?: continue
            sync(db, n.recordingId, parsed)
        }
    }

    private fun key(front: String) = front.trim().lowercase().replace(Regex("\\s+"), " ")
}
