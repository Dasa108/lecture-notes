package com.lecturenotes.audio

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lecturenotes.app
import com.lecturenotes.data.Recording
import com.lecturenotes.data.RecordingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps lecture audio small: compressed once transcribed, expanded again only when needed. */
object AudioStorage {
    /** Compresses a transcribed recording's WAV to .m4a and switches the recording to it. Returns bytes saved. */
    suspend fun compress(context: Context, rec: Recording): Long = withContext(Dispatchers.IO) {
        val wav = File(rec.audioPath)
        if (AudioCompressor.isCompressed(rec.audioPath) || !wav.exists() || rec.status != RecordingStatus.TRANSCRIBED) return@withContext 0L
        val m4a = File(wav.parentFile, wav.nameWithoutExtension + ".m4a")
        AudioCompressor.compress(wav, m4a)
        context.app.db.recordings().setAudioPath(rec.id, m4a.path)
        val saved = wav.length() - m4a.length()
        wav.delete()
        saved
    }

    /** Transcribed recordings still stored as WAV, and how much compressing them would roughly save. */
    suspend fun uncompressed(context: Context): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val recs = context.app.db.recordings().withStatus(RecordingStatus.TRANSCRIBED)
            .filter { !AudioCompressor.isCompressed(it.audioPath) && File(it.audioPath).exists() }
        recs.size to recs.sumOf { File(it.audioPath).length() * 7 / 8 } // AAC at 32 kbps is ~1/8 of 256 kbps PCM
    }

    /** A 16 kHz WAV of the recording for processing: the file itself, or a temporary decode of the .m4a. */
    fun <T> withWav(context: Context, rec: Recording, block: (File) -> T): T {
        if (!AudioCompressor.isCompressed(rec.audioPath)) return block(File(rec.audioPath))
        val tmp = File(context.cacheDir, "decode_${rec.id}.wav")
        try {
            AudioDecoder.decodeToWav(File(rec.audioPath), tmp)
            return block(tmp)
        } finally {
            tmp.delete()
        }
    }
}

/** "Free up space": compresses every transcribed recording still stored as WAV. */
class CompressWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val recs = applicationContext.app.db.recordings().withStatus(RecordingStatus.TRANSCRIBED)
        for (rec in recs) {
            if (isStopped) return Result.retry()
            runCatching { AudioStorage.compress(applicationContext, rec) }.onFailure { Log.w("CompressWorker", "skip ${rec.id}", it) }
        }
        return Result.success()
    }
}
