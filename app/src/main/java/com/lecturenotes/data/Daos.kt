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

    @Query("UPDATE notes SET partialJson = :partialJson WHERE recordingId = :recordingId")
    suspend fun setPartial(recordingId: Long, partialJson: String?)

    @Query("UPDATE notes SET status = :status, message = :message WHERE recordingId = :recordingId")
    suspend fun setStatus(recordingId: Long, status: NotesStatus, message: String?)

    @Query("UPDATE notes SET status = 'DONE', json = :json, partialJson = NULL, progress = 1, message = NULL WHERE recordingId = :recordingId")
    suspend fun finish(recordingId: Long, json: String)

    @Query("DELETE FROM notes WHERE recordingId = :recordingId")
    suspend fun delete(recordingId: Long)
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
