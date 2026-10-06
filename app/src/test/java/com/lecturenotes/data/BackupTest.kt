package com.lecturenotes.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BackupTest {
    private lateinit var ctx: Context
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = AppDatabase.create(ctx)
    }

    private fun file(dir: String, name: String, text: String) =
        File(File(ctx.filesDir, dir).apply { mkdirs() }, name).apply { writeText(text) }

    private fun lecture(title: String, audio: File): Long = runBlocking {
        val id = db.recordings().insert(Recording(title = title, createdAt = 1, audioPath = audio.path, status = RecordingStatus.TRANSCRIBED))
        db.segments().insertAll(listOf(Segment(recordingId = id, startMs = 0, endMs = 1000, text = "each call is an agent")))
        id
    }

    private fun allLectures(): List<Recording> = runBlocking {
        val ids = db.openHelper.readableDatabase.query("SELECT id FROM recordings").use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        db.recordings().getAll(ids)
    }

    private fun backup(): ByteArray = ByteArrayOutputStream().also { Backup.write(ctx, db, it) }.toByteArray()

    @Test fun restoreBringsBackExactlyWhatWasBackedUp() {
        val audioA = file("audio", "a.m4a", "AUDIO-A")
        val slides = file("attachments", "s.pdf", "PDF")
        val a = lecture("Lecture A", audioA)
        runBlocking { db.attachments().insert(Attachment(recordingId = a, kind = AttachmentKind.values().first(), fileName = "s.pdf", path = slides.path)) }
        ctx.getSharedPreferences("settings", 0).edit().putBoolean("b", true).putInt("i", 7).putLong("l", 9L).putFloat("f", 1.5f)
            .putString("s", "x").putStringSet("ss", setOf("p", "q")).commit()
        ctx.getSharedPreferences("secrets", 0).edit().putString("key", "this-phone-only").commit()
        val zip = backup()

        // Changes after the backup, which a restore must undo.
        runBlocking { db.segments().getFor(a).forEach { db.segments().updateText(it.id, "changed") } }
        val audioB = file("audio", "b.m4a", "AUDIO-B")
        lecture("Lecture B", audioB)
        audioA.writeText("CHANGED")
        ctx.getSharedPreferences("settings", 0).edit().clear().putInt("i", 1).putString("new", "n").commit()

        val staged = Backup.stage(ctx, db, ByteArrayInputStream(zip))
        assertEquals(1, staged.lectures)
        Backup.replace(ctx, db, staged)

        db = AppDatabase.create(ctx)
        runBlocking {
            val recs = allLectures()
            assertEquals(listOf("Lecture A"), recs.map { it.title })
            assertEquals(listOf("each call is an agent"), db.segments().getFor(recs[0].id).map { it.text })
            assertEquals("AUDIO-A", File(recs[0].audioPath).readText())
            assertEquals("PDF", File(db.attachments().getFor(recs[0].id).single().path).readText())
        }
        assertFalse("lecture B's audio is gone", audioB.exists())
        val p = ctx.getSharedPreferences("settings", 0)
        assertEquals(mapOf("b" to true, "i" to 7, "l" to 9L, "f" to 1.5f, "s" to "x", "ss" to setOf("p", "q")), p.all)
        assertEquals("this-phone-only", ctx.getSharedPreferences("secrets", 0).getString("key", null))
        assertFalse(File(ctx.filesDir, "restore_staging").exists())
        assertFalse(File(ctx.filesDir, "restore_previous").exists())
    }

    @Test fun badFilesAreRejectedWithoutTouchingData() {
        val audio = file("audio", "a.m4a", "AUDIO-A")
        lecture("Lecture A", audio)
        fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        }.toByteArray()
        for (bad in listOf(
            "not a zip".toByteArray(),
            zip("hello.txt" to "hi"),
            zip("manifest.json" to "{\"format\":1}", "../../escape.txt" to "x"),
            zip("manifest.json" to "{\"format\":99,\"filesDir\":\"/x\"}", "database/lecturenotes.db" to "x"),
        )) {
            try {
                Backup.stage(ctx, db, ByteArrayInputStream(bad)); fail("accepted a bad backup")
            } catch (e: Backup.BackupException) { /* expected */ }
        }
        assertFalse(File(ctx.filesDir.parentFile, "escape.txt").exists())
        assertEquals("AUDIO-A", audio.readText())
        assertEquals(1, allLectures().size)
    }

    @Test fun pathsMoveToThisInstallsFolder() {
        SQLiteDatabase.create(null).use { d ->
            d.execSQL("CREATE TABLE recordings (audioPath TEXT NOT NULL, sourcePath TEXT)")
            d.execSQL("CREATE TABLE attachments (path TEXT NOT NULL)")
            d.execSQL("INSERT INTO recordings VALUES ('/data/user/0/old/files/audio/a.m4a', NULL), ('', '/elsewhere/x.mp4')")
            d.execSQL("INSERT INTO attachments VALUES ('/data/user/0/old/files/attachments/s.pdf')")
            Backup.rewritePaths(d, "/data/user/0/old/files", "/data/user/0/new/files")
            fun col(sql: String) = d.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            assertEquals(listOf("/data/user/0/new/files/audio/a.m4a", ""), col("SELECT audioPath FROM recordings"))
            assertEquals(listOf(null, "/elsewhere/x.mp4"), col("SELECT sourcePath FROM recordings"))
            assertEquals(listOf("/data/user/0/new/files/attachments/s.pdf"), col("SELECT path FROM attachments"))
        }
        assertTrue(true)
    }
}
