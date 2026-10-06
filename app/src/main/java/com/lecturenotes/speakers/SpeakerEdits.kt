package com.lecturenotes.speakers

import androidx.room.withTransaction
import com.lecturenotes.data.AppDatabase
import com.lecturenotes.data.Speaker

/** Fixes users make to automatic speaker labels. */
object SpeakerEdits {
    /**
     * Merges [from] into [into] (the user heard they're the same person): all of [from]'s lines move
     * to [into], talk time is combined, the lecturer flag is kept if either had it, and the remaining
     * speakers are renumbered by talk time. Names the user chose for [into] are kept.
     */
    suspend fun merge(db: AppDatabase, from: Speaker, into: Speaker) {
        require(from.recordingId == into.recordingId && from.id != into.id)
        db.withTransaction {
            db.segments().moveSpeaker(from.recordingId, from.id, into.id)
            db.speakers().delete(from.id)
            db.speakers().update(into.copy(talkMs = into.talkMs + from.talkMs, isLecturer = into.isLecturer || from.isLecturer))
            renumber(db, from.recordingId)
        }
    }

    /** Numbers speakers 1..n by talk time; default names ("Speaker N") follow their new number. */
    private suspend fun renumber(db: AppDatabase, recordingId: Long) {
        db.speakers().getFor(recordingId).sortedByDescending { it.talkMs }.forEachIndexed { i, s ->
            val n = i + 1
            val name = if (DEFAULT_NAME.matches(s.name)) "Speaker $n" else s.name
            if (s.number != n || s.name != name) db.speakers().update(s.copy(number = n, name = name))
        }
    }

    private val DEFAULT_NAME = Regex("""Speaker \d+""")
}
