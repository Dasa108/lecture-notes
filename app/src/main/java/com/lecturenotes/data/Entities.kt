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

    /** A lecture made from slides/documents only; it has no audio (audioPath is empty). */
    DOCUMENT,
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
    /** Target length in words for the reading part of the notes; null = no limit. */
    val wordBudget: Int? = null,
    /** Exact number of flashcards / quiz questions the user asked for; null = automatic. */
    val flashcardCount: Int? = null,
    val quizCount: Int? = null,
)

enum class AttachmentKind { PDF, PPTX, IMAGE, TEXT }
enum class AttachmentStatus { PENDING, EXTRACTING, DONE, FAILED }

/** Slides, a PDF, photos of the board, or a text file attached to a lecture. */
@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(
        entity = Recording::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId")],
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val kind: AttachmentKind,
    val fileName: String,
    /** The app's own copy of the file. */
    val path: String,
    val status: AttachmentStatus = AttachmentStatus.PENDING,
    val pageCount: Int = 0,
    val progress: Float = 0f,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Text of one page/slide of an attachment. [number] is the slide/page number shown to the student. */
@Entity(
    tableName = "attachment_pages",
    foreignKeys = [ForeignKey(
        entity = Attachment::class,
        parentColumns = ["id"],
        childColumns = ["attachmentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["attachmentId", "number"], unique = true)],
)
data class AttachmentPage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val attachmentId: Long,
    val number: Int,
    val text: String,
    /** True if the text came from OCR (a scan or photo) rather than the file's text layer. */
    val ocr: Boolean = false,
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
    /** Speaker labelling: null = not run, else a SpeakerStatus name. */
    val speakerStatus: String? = null,
    /** 0..1 while speaker labelling runs. */
    val speakerProgress: Float? = null,
)

enum class SpeakerStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED }

/** A voice found in a recording by speaker labelling. */
@Entity(
    tableName = "speakers",
    foreignKeys = [ForeignKey(
        entity = Recording::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("recordingId")],
)
data class Speaker(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    /** 1-based order by talk time (1 = talks most). */
    val number: Int,
    val name: String,
    val isLecturer: Boolean,
    val talkMs: Long,
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
