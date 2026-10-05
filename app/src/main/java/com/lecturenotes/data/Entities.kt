package com.lecturenotes.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RecordingStatus {
    /** Audio is still being captured by RecorderService. */
    RECORDING,

    /** An imported file is waiting to be decoded to 16 kHz WAV. */
    IMPORTING,

    /** Audio is ready but has not been transcribed (e.g. no model downloaded yet). */
    READY,
    QUEUED,
    TRANSCRIBING,
    TRANSCRIBED,
    FAILED,
}

enum class NotesStatus { QUEUED, GENERATING, DONE, FAILED }

/** AI study notes for a recording (at most one set per recording; regenerating replaces it). */
@Entity(
    tableName = "notes",
    foreignKeys = [ForeignKey(
        entity = Recording::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["recordingId"], unique = true)],
)
data class Notes(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val status: NotesStatus,
    /** NoteStyle name. */
    val style: String,
    val provider: String,
    val model: String,
    /** The finished StudyNotes as JSON. */
    val json: String? = null,
    /** Resumable progress of a split run (PartialRun JSON). */
    val partialJson: String? = null,
    val progress: Float = 0f,
    /** Human-readable progress ("Writing notes: part 2 of 5…") or the error when FAILED. */
    val message: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A flashcard from a lecture's notes, with its spaced-repetition schedule. */
@Entity(
    tableName = "review_cards",
    foreignKeys = [ForeignKey(
        entity = Recording::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["recordingId", "front"], unique = true), Index("dueAt")],
)
data class ReviewCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val front: String,
    val back: String,
    /** Where in the lecture this card's idea is taught. */
    val atMs: Long? = null,
    val ease: Double = 2.5,
    val intervalDays: Double = 0.0,
    /** Successful reviews in a row; 0 = new (never reviewed) or relearning. */
    val reps: Int = 0,
    val lapses: Int = 0,
    val dueAt: Long,
    val firstReviewedAt: Long? = null,
    val lastReviewedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isNew: Boolean get() = firstReviewedAt == null
}

@Entity(
    tableName = "quiz_attempts",
    foreignKeys = [ForeignKey(
        entity = Recording::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId")],
)
data class QuizAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val correct: Int,
    val total: Int,
    val takenAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "subjects")
data class Subject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Index into the app's subject colour palette. */
    val colorIndex: Int,
    val createdAt: Long = System.currentTimeMillis(),
    /** Prompts.SubjectType name: the starting template for this subject's notes. */
    val promptType: String? = null,
    /** The student's own instructions for notes in this subject. */
    val promptInstructions: String? = null,
    /** Advanced: a full replacement for the guidance part of the prompt (the JSON format is always added). */
    val customPrompt: String? = null,
    /** NoteStyle name pre-selected when generating notes for this subject. */
    val defaultStyle: String? = null,
)

@Entity(tableName = "recordings", indices = [Index("subjectId")])
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    /** 16 kHz mono PCM16 WAV owned by the app. */
    val audioPath: String,
    /** Imported original awaiting decode; deleted once [audioPath] is written. */
    val sourcePath: String? = null,
    val durationMs: Long = 0,
    val status: RecordingStatus,
    /** 0..1 progress of the current import or transcription. */
    val progress: Float = 0f,
    /** Transcription checkpoint: audio before this point has been transcribed and saved. */
    val transcribedMs: Long = 0,
    val modelId: String? = null,
    val error: String? = null,
    /** The subject (course) this lecture is filed under; null means unfiled. */
    val subjectId: Long? = null,
)

@Entity(
    tableName = "segments",
    foreignKeys = [ForeignKey(
        entity = Recording::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId", "startMs")],
)
data class Segment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Filled in by speaker labelling (M3). */
    val speakerId: Long? = null,
)
