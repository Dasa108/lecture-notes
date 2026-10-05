package com.lecturenotes.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: Long): Flow<Recording?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): Recording?

    @Query("SELECT * FROM recordings WHERE status IN (:statuses)")
    suspend fun withStatus(vararg statuses: RecordingStatus): List<Recording>

    @Insert
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Delete
    suspend fun delete(recording: Recording)

    @Query("UPDATE recordings SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: RecordingStatus, error: String? = null)

    @Query("UPDATE recordings SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Float)

    /** Targeted updates, so the decoder and transcriber (running concurrently) don't overwrite each other. */
    @Query("UPDATE recordings SET sourcePath = NULL, durationMs = :durationMs WHERE id = :id")
    suspend fun markImported(id: Long, durationMs: Long)

    @Query("UPDATE recordings SET status = 'TRANSCRIBING', modelId = :modelId, error = NULL WHERE id = :id")
    suspend fun markTranscribing(id: Long, modelId: String)

    @Query("UPDATE recordings SET transcribedMs = :transcribedMs, progress = :progress WHERE id = :id")
    suspend fun setCheckpoint(id: Long, transcribedMs: Long, progress: Float)

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE recordings SET subjectId = :subjectId WHERE id IN (:ids)")
    suspend fun setSubject(ids: List<Long>, subjectId: Long?)

    @Query("SELECT * FROM recordings WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<Recording>
}

data class SubjectWithCount(
    @Embedded val subject: Subject,
    @ColumnInfo(name = "count") val count: Int,
)

@Dao
interface NotesDao {
    @Query("SELECT * FROM notes WHERE recordingId = :recordingId")
    fun observeFor(recordingId: Long): Flow<Notes?>

    @Query("SELECT * FROM notes WHERE recordingId = :recordingId")
    suspend fun getFor(recordingId: Long): Notes?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(notes: Notes): Long

    @Query("UPDATE notes SET progress = :progress, message = :message WHERE recordingId = :recordingId")
    suspend fun setProgress(recordingId: Long, progress: Float, message: String?)

    @Query("UPDATE notes SET message = :message WHERE recordingId = :recordingId")
    suspend fun setMessage(recordingId: Long, message: String?)

    @Query("SELECT * FROM notes WHERE status = 'DONE' AND json IS NOT NULL")
    suspend fun allDone(): List<Notes>

    /** Recordings whose notes mention [pattern] (a LIKE pattern); the caller finds the exact line. */
    @Query("SELECT * FROM notes WHERE json LIKE :pattern ESCAPE '\\'")
    fun observeMatching(pattern: String): Flow<List<Notes>>

    @Query("UPDATE notes SET partialJson = :partialJson WHERE recordingId = :recordingId")
    suspend fun setPartial(recordingId: Long, partialJson: String?)

    @Query("UPDATE notes SET status = :status, message = :message WHERE recordingId = :recordingId")
    suspend fun setStatus(recordingId: Long, status: NotesStatus, message: String?)

    @Query("UPDATE notes SET status = 'DONE', json = :json, partialJson = NULL, progress = 1, message = NULL WHERE recordingId = :recordingId")
    suspend fun finish(recordingId: Long, json: String)

    @Query("DELETE FROM notes WHERE recordingId = :recordingId")
    suspend fun delete(recordingId: Long)
}

/** Counts for a study scope (all, one subject, or one lecture). */
data class StudyCounts(
    @ColumnInfo(name = "due") val due: Int,
    @ColumnInfo(name = "newCards") val newCards: Int,
    @ColumnInfo(name = "total") val total: Int,
)

/** One lecture's flashcards, for the study picker. */
data class LectureCards(
    @ColumnInfo(name = "recordingId") val recordingId: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "subjectId") val subjectId: Long?,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "due") val due: Int,
    @ColumnInfo(name = "newCards") val newCards: Int,
    @ColumnInfo(name = "total") val total: Int,
)

@Dao
interface ReviewCardDao {
    @Query(
        """SELECT r.id AS recordingId, r.title, r.subjectId, r.createdAt,
             SUM(CASE WHEN c.firstReviewedAt IS NOT NULL AND c.dueAt <= :now THEN 1 ELSE 0 END) AS due,
             SUM(CASE WHEN c.firstReviewedAt IS NULL THEN 1 ELSE 0 END) AS newCards,
             COUNT(*) AS total
           FROM review_cards c JOIN recordings r ON r.id = c.recordingId
           GROUP BY r.id ORDER BY r.createdAt DESC""",
    )
    suspend fun lectureCards(now: Long): List<LectureCards>

    /** Due reviews/relearning (oldest first), then new cards, from the chosen lectures. */
    @Query(
        """SELECT * FROM review_cards
           WHERE recordingId IN (:recordingIds)
             AND ((firstReviewedAt IS NOT NULL AND dueAt <= :now) OR firstReviewedAt IS NULL)
           ORDER BY firstReviewedAt IS NULL, dueAt, recordingId, id""",
    )
    suspend fun studyQueueFor(now: Long, recordingIds: List<Long>): List<ReviewCard>

    /** Every card from the chosen lectures, for exam revision regardless of schedule. */
    @Query("SELECT * FROM review_cards WHERE recordingId IN (:recordingIds) ORDER BY recordingId, id")
    suspend fun allFor(recordingIds: List<Long>): List<ReviewCard>

    @Query(
        """SELECT MIN(dueAt) FROM review_cards
           WHERE firstReviewedAt IS NOT NULL AND dueAt > :now AND recordingId IN (:recordingIds)""",
    )
    suspend fun nextDueFor(now: Long, recordingIds: List<Long>): Long?

    @Query(
        """SELECT
             SUM(CASE WHEN c.firstReviewedAt IS NOT NULL AND c.dueAt <= :now THEN 1 ELSE 0 END) AS due,
             SUM(CASE WHEN c.firstReviewedAt IS NULL THEN 1 ELSE 0 END) AS newCards,
             COUNT(*) AS total
           FROM review_cards c JOIN recordings r ON r.id = c.recordingId
           WHERE (:subjectId IS NULL OR r.subjectId = :subjectId)
             AND (:recordingId IS NULL OR c.recordingId = :recordingId)""",
    )
    fun observeCounts(now: Long, subjectId: Long?, recordingId: Long?): Flow<StudyCounts?>

    /** New cards first studied since [since]; used for the daily new-card limit. */
    @Query("SELECT COUNT(*) FROM review_cards WHERE firstReviewedAt >= :since")
    suspend fun newStudiedSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM review_cards WHERE lastReviewedAt >= :since")
    fun observeReviewedSince(since: Long): Flow<Int>

    @Query("SELECT * FROM review_cards WHERE recordingId = :recordingId")
    suspend fun getFor(recordingId: Long): List<ReviewCard>

    @Query("SELECT recordingId FROM review_cards GROUP BY recordingId")
    suspend fun recordingsWithCards(): List<Long>

    @Insert
    suspend fun insertAll(cards: List<ReviewCard>)

    @Update
    suspend fun updateAll(cards: List<ReviewCard>)

    @Update
    suspend fun update(card: ReviewCard)

    @Query("DELETE FROM review_cards WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)
}

@Dao
interface QuizAttemptDao {
    @Insert
    suspend fun insert(attempt: QuizAttempt)

    @Query("SELECT * FROM quiz_attempts WHERE recordingId = :recordingId ORDER BY takenAt DESC")
    fun observeFor(recordingId: Long): Flow<List<QuizAttempt>>

    @Query("SELECT MAX(correct) FROM quiz_attempts WHERE recordingId = :recordingId")
    suspend fun bestFor(recordingId: Long): Int?
}

@Dao
interface SubjectDao {
    @Query(
        """SELECT s.*, (SELECT COUNT(*) FROM recordings r WHERE r.subjectId = s.id) AS count
           FROM subjects s ORDER BY s.name COLLATE NOCASE""",
    )
    fun observeWithCounts(): Flow<List<SubjectWithCount>>

    @Query("SELECT * FROM subjects WHERE id = :id")
    fun observe(id: Long): Flow<Subject?>

    @Query("SELECT * FROM subjects WHERE id = :id")
    suspend fun get(id: Long): Subject?

    @Query("SELECT COUNT(*) FROM subjects")
    suspend fun count(): Int

    @Insert
    suspend fun insert(subject: Subject): Long

    @Update
    suspend fun update(subject: Subject)

    @Delete
    suspend fun delete(subject: Subject)

    @Query("UPDATE recordings SET subjectId = NULL WHERE subjectId = :subjectId")
    suspend fun unfileAll(subjectId: Long)
}

@Dao
interface SegmentDao {
    @Query("SELECT * FROM segments WHERE recordingId = :recordingId ORDER BY startMs")
    fun observeFor(recordingId: Long): Flow<List<Segment>>

    @Insert
    suspend fun insertAll(segments: List<Segment>)

    @Query("DELETE FROM segments WHERE recordingId = :recordingId AND startMs >= :fromMs")
    suspend fun deleteFrom(recordingId: Long, fromMs: Long)

    @Query("UPDATE segments SET text = :text WHERE id = :id")
    suspend fun updateText(id: Long, text: String)

    @Query("SELECT * FROM segments WHERE recordingId = :recordingId ORDER BY startMs")
    suspend fun getFor(recordingId: Long): List<Segment>

    @Query("SELECT text FROM segments WHERE recordingId = :recordingId ORDER BY startMs DESC LIMIT :limit")
    suspend fun lastTexts(recordingId: Long, limit: Int): List<String>

    /** The earliest transcript line in each recording that contains [pattern] (a LIKE pattern). */
    @Query(
        """SELECT recordingId, MIN(startMs) AS startMs, text FROM segments
           WHERE text LIKE :pattern ESCAPE '\' GROUP BY recordingId""",
    )
    fun observeMatches(pattern: String): Flow<List<TranscriptMatch>>
}

data class TranscriptMatch(val recordingId: Long, val startMs: Long, val text: String)
