package com.lecturenotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun statusToString(status: RecordingStatus): String = status.name

    @TypeConverter
    fun stringToStatus(value: String): RecordingStatus = RecordingStatus.valueOf(value)

    @TypeConverter
    fun notesStatusToString(status: NotesStatus): String = status.name

    @TypeConverter
    fun stringToNotesStatus(value: String): NotesStatus = NotesStatus.valueOf(value)
}

@Database(entities = [Recording::class, Segment::class, Subject::class, Notes::class, ReviewCard::class, QuizAttempt::class], version = 5)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun segments(): SegmentDao
    abstract fun subjects(): SubjectDao
    abstract fun notes(): NotesDao
    abstract fun reviewCards(): ReviewCardDao
    abstract fun quizAttempts(): QuizAttemptDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "lecturenotes.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        /** v5: study mode (flashcard review schedule, quiz scores). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `review_cards` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordingId` INTEGER NOT NULL, `front` TEXT NOT NULL, `back` TEXT NOT NULL, `atMs` INTEGER, " +
                        "`ease` REAL NOT NULL, `intervalDays` REAL NOT NULL, `reps` INTEGER NOT NULL, `lapses` INTEGER NOT NULL, " +
                        "`dueAt` INTEGER NOT NULL, `firstReviewedAt` INTEGER, `lastReviewedAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_review_cards_recordingId_front` ON `review_cards` (`recordingId`, `front`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_cards_dueAt` ON `review_cards` (`dueAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `quiz_attempts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordingId` INTEGER NOT NULL, `correct` INTEGER NOT NULL, `total` INTEGER NOT NULL, `takenAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quiz_attempts_recordingId` ON `quiz_attempts` (`recordingId`)")
            }
        }

        /** v4: per-subject notes prompts. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf("promptType", "promptInstructions", "customPrompt", "defaultStyle")) {
                    db.execSQL("ALTER TABLE `subjects` ADD COLUMN `$column` TEXT")
                }
            }
        }

        /** v3: AI study notes. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordingId` INTEGER NOT NULL, `status` TEXT NOT NULL, `style` TEXT NOT NULL, " +
                        "`provider` TEXT NOT NULL, `model` TEXT NOT NULL, `json` TEXT, `partialJson` TEXT, " +
                        "`progress` REAL NOT NULL, `message` TEXT, `createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notes_recordingId` ON `notes` (`recordingId`)")
            }
        }

        /** v2: subjects for organising lectures. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `subjects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `colorIndex` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `subjectId` INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recordings_subjectId` ON `recordings` (`subjectId`)")
            }
        }
    }
}
