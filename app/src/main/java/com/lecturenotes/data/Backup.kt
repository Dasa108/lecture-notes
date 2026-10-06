package com.lecturenotes.data

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import com.lecturenotes.LectureNotesApp
import com.lecturenotes.audio.RecorderService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One-file backup of everything the user made: the database (lectures, transcripts, notes, study
 * progress, speakers, subjects), audio, attached slides, and settings. Speech models are left out
 * (they re-download) and so are API keys (they're encrypted with a key that never leaves the phone).
 *
 * Restore unpacks to a staging folder and checks it before touching anything, then swaps it in and
 * restarts the app. If the swap fails part-way, the previous data is put back.
 */
object Backup {
    private const val FORMAT = 1
    private const val MANIFEST = "manifest.json"
    private const val DB_ENTRY = "database/lecturenotes.db"
    private const val DB_NAME = "lecturenotes.db"
    private const val FILES = "files/"

    /** Folders under filesDir that hold the user's own files. */
    private val DIRS = listOf("audio", "attachments")

    /** Plain settings; "secrets" (API keys) and "models" (per-device downloads) are deliberately absent. */
    private val PREFS = listOf("settings", "notes_ai")

    class BackupException(message: String) : IOException(message)

    /** Why backup/restore must wait, or null if nothing is running. Half-finished work can't be copied consistently. */
    suspend fun busyReason(app: LectureNotesApp): String? {
        val db = app.db
        if (RecorderService.state.value.active) return "A recording is in progress."
        val busy = db.recordings().withStatus(
            RecordingStatus.RECORDING, RecordingStatus.IMPORTING, RecordingStatus.QUEUED, RecordingStatus.TRANSCRIBING,
        )
        if (busy.isNotEmpty()) return "“${busy.first().title}” is still being transcribed."
        val q = db.openHelper.readableDatabase
        fun any(sql: String) = q.query(sql).use { it.moveToFirst() && it.getInt(0) > 0 }
        if (any("SELECT COUNT(*) FROM notes WHERE status IN ('QUEUED','GENERATING')")) return "Notes are being generated."
        if (any("SELECT COUNT(*) FROM recordings WHERE speakerStatus IN ('QUEUED','RUNNING','PAUSED')")) return "Speakers are being labelled."
        if (any("SELECT COUNT(*) FROM attachments WHERE status IN ('PENDING','EXTRACTING')")) return "Slides are still being read."
        return null
    }

    /** Rough size of a backup before compression. */
    fun estimateBytes(context: Context): Long =
        DIRS.sumOf { d -> File(context.filesDir, d).walkTopDown().filter { it.isFile }.sumOf { it.length() } } +
            context.getDatabasePath(DB_NAME).length()

    fun write(app: Context, db: AppDatabase, out: OutputStream, onProgress: (Float) -> Unit = {}) {
        // Fold the write-ahead log into the main file so one file holds everything.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val dbFile = app.getDatabasePath(DB_NAME)
        val files = DIRS.flatMap { d -> File(app.filesDir, d).walkTopDown().filter { it.isFile }.toList() }
        val total = (files.sumOf { it.length() } + dbFile.length()).coerceAtLeast(1)
        var done = 0L

        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            fun putFile(name: String, f: File) {
                // Audio is already compressed; deflating it again only costs time.
                zip.setLevel(if (f.extension.lowercase() in setOf("m4a", "mp3", "aac", "jpg", "jpeg", "png", "pdf")) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                done += f.length()
                onProgress(done.toFloat() / total)
            }
            put(MANIFEST, JSONObject()
                .put("format", FORMAT)
                .put("dbVersion", db.openHelper.readableDatabase.version)
                .put("filesDir", app.filesDir.absolutePath)
                .put("createdAt", System.currentTimeMillis())
                .put("prefs", JSONObject().apply { PREFS.forEach { put(it, prefsToJson(app.getSharedPreferences(it, Context.MODE_PRIVATE))) } })
                .toString(2).toByteArray())
            putFile(DB_ENTRY, dbFile)
            files.forEach { putFile(FILES + it.relativeTo(app.filesDir).invariantSeparatorsPath, it) }
        }
    }

    /** What a backup holds, shown before the user confirms a restore. */
    data class Staged(val dir: File, val lectures: Int, val createdAt: Long, private val manifest: JSONObject) {
        internal val oldFilesDir: String get() = manifest.getString("filesDir")
        internal val prefs: JSONObject get() = manifest.optJSONObject("prefs") ?: JSONObject()
    }

    /** Unpacks and checks a backup without changing any current data. */
    fun stage(app: Context, currentDb: AppDatabase, input: InputStream): Staged {
        val dir = File(app.filesDir, "restore_staging")
        dir.deleteRecursively()
        dir.mkdirs()
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val target = File(dir, e.name).canonicalFile
                    // Refuse entries that would land outside the staging folder ("../" tricks).
                    if (!target.path.startsWith(dir.canonicalPath + File.separator)) throw BackupException("This backup file is damaged.")
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
            val manifestFile = File(dir, MANIFEST)
            val dbFile = File(dir, DB_ENTRY)
            if (!manifestFile.exists() || !dbFile.exists()) throw BackupException("This isn't a Lecture Notes backup.")
            val manifest = JSONObject(manifestFile.readText())
            if (manifest.optInt("format") > FORMAT) throw BackupException("This backup is from a newer version of the app. Update the app first.")
            val current = currentDb.openHelper.readableDatabase.version
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db: SQLiteDatabase ->
                if (db.version > current) throw BackupException("This backup is from a newer version of the app. Update the app first.")
                db.rawQuery("PRAGMA integrity_check", null).use { c ->
                    if (!c.moveToFirst() || c.getString(0) != "ok") throw BackupException("This backup's database is damaged.")
                }
                // Paths are stored in full; point them at this install's folder.
                rewritePaths(db, manifest.getString("filesDir"), app.filesDir.absolutePath)
                val lectures = db.rawQuery("SELECT COUNT(*) FROM recordings", null).use { it.moveToFirst(); it.getInt(0) }
                return Staged(dir, lectures, manifest.optLong("createdAt"), manifest)
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw if (e is BackupException) e else BackupException("Couldn't read this backup: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun discard(staged: Staged) = staged.dir.deleteRecursively()

    /**
     * Replaces all current lectures with [staged]. The caller must cancel background work first and
     * restart the process afterwards: [db] is closed here and every screen holds stale data.
     */
    fun replace(app: Context, db: AppDatabase, staged: Staged) {
        val filesDir = app.filesDir
        val old = File(filesDir, "restore_previous").apply { deleteRecursively(); mkdirs() }
        val dbFile = app.getDatabasePath(DB_NAME)
        val dbFamily = listOf("", "-wal", "-shm", "-journal").map { File(dbFile.path + it) }
        db.close()

        val moved = mutableListOf<Pair<File, File>>()
        fun move(from: File, to: File) {
            if (!from.exists()) return
            to.parentFile?.mkdirs()
            if (!from.renameTo(to)) throw IOException("Couldn't move ${from.name}")
            moved += from to to
        }
        try {
            // 1. Set the current data aside (same filesystem, so these are instant renames).
            DIRS.forEach { move(File(filesDir, it), File(old, it)) }
            dbFamily.forEach { move(it, File(old, "db/" + it.name)) }
            // 2. Move the backup in.
            DIRS.forEach { d -> File(staged.dir, FILES + d).takeIf { it.exists() }?.let { move(it, File(filesDir, d)) } }
            move(File(staged.dir, DB_ENTRY), dbFile)
        } catch (e: Exception) {
            // Undo in reverse so the app is exactly as it was.
            moved.asReversed().forEach { (from, to) -> runCatching { from.parentFile?.mkdirs(); to.renameTo(from) } }
            throw BackupException("Restore failed; nothing was changed. (${e.message})")
        }
        PREFS.forEach { name ->
            staged.prefs.optJSONObject(name)?.let { jsonToPrefs(it, app.getSharedPreferences(name, Context.MODE_PRIVATE)) }
        }
        old.deleteRecursively()
        staged.dir.deleteRecursively()
    }

    internal fun rewritePaths(db: SQLiteDatabase, from: String, to: String) {
        if (from == to) return
        val prefix = from.trimEnd('/') + "/"
        val target = to.trimEnd('/') + "/"
        for ((table, column) in listOf("recordings" to "audioPath", "recordings" to "sourcePath", "attachments" to "path")) {
            db.execSQL(
                "UPDATE $table SET $column = ? || substr($column, ?) WHERE substr($column, 1, ?) = ?",
                arrayOf(target, prefix.length + 1, prefix.length, prefix),
            )
        }
    }

    /** Typed so restore puts back an Int as an Int, not a Long. */
    private fun prefsToJson(p: SharedPreferences) = JSONObject().apply {
        p.all.forEach { (k, v) ->
            val (type, value) = when (v) {
                is Boolean -> "b" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                is String -> "s" to v
                is Set<*> -> "ss" to JSONArray(v.toList())
                else -> return@forEach
            }
            put(k, JSONObject().put("t", type).put("v", value))
        }
    }

    private fun jsonToPrefs(json: JSONObject, p: SharedPreferences) {
        val e = p.edit().clear()
        json.keys().forEach { k ->
            val o = json.getJSONObject(k)
            when (o.getString("t")) {
                "b" -> e.putBoolean(k, o.getBoolean("v"))
                "i" -> e.putInt(k, o.getInt("v"))
                "l" -> e.putLong(k, o.getLong("v"))
                "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                "s" -> e.putString(k, o.getString("v"))
                "ss" -> e.putStringSet(k, o.getJSONArray("v").let { a -> (0 until a.length()).map(a::getString).toSet() })
            }
        }
        e.commit()
    }
}
