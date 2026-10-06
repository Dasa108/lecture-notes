package com.lecturenotes.whisper

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lecturenotes.app
import com.lecturenotes.transcribe.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Tiny's measured cost per 30 s of dense speech on this phone, and what it implies for other models. */
data class BenchResult(
    val encodeMs: Double,
    val decodeMs: Double,
    val threads: Int,
    val measuredAt: Long,
) {
    /**
     * Estimated minutes to transcribe one hour of lecture with the app open (120 windows of 30 s).
     * Both encode and decode are scaled from Tiny's encode time (see [WhisperModel.encodeRatio]).
     */
    fun minutesPerHour(model: WhisperModel): Double =
        encodeMs * (model.encodeRatio + model.decodeRatio) * WINDOWS_PER_HOUR / 60_000.0

    fun toJson(): String = JSONObject().put("encode", encodeMs).put("decode", decodeMs)
        .put("threads", threads).put("at", measuredAt).toString()

    companion object {
        private const val WINDOWS_PER_HOUR = 120

        fun parse(json: String?): BenchResult? = runCatching {
            val o = JSONObject(json ?: return null)
            BenchResult(o.getDouble("encode"), o.getDouble("decode"), o.getInt("threads"), o.getLong("at"))
        }.getOrNull()
    }
}

/**
 * Measures this phone's transcription speed: Tiny transcribes a bundled 30 s clip of dense speech
 * (warm-up run, then a timed run), and the per-model ratios turn that into estimates for every model.
 */
object DeviceCheck {
    private const val CLIP = "bench30.wav"

    suspend fun run(context: Context, onStatus: (String) -> Unit): BenchResult {
        val models = context.app.models
        val tiny = WhisperModels.all.first()
        if (!models.isDownloaded(tiny)) {
            onStatus("Downloading the Tiny model (32 MB) for the test…")
            Jobs.downloadModel(context, tiny)
            val info = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(Jobs.downloadName(tiny))
                .first { infos -> infos.lastOrNull()?.state?.isFinished == true }.last()
            if (info.state != WorkInfo.State.SUCCEEDED || !models.isDownloaded(tiny)) {
                throw IOException("Couldn't download the test model. Check your connection and try again.")
            }
        }
        onStatus("Testing transcription speed (about 10 seconds)…")
        return withContext(Dispatchers.Default) {
            Whisper.ensureBackends(context.applicationInfo.nativeLibraryDir)
            val samples = readClip(context)
            val threads = DeviceInfo.threadsForCurrentThread()
            WhisperContext.load(models.fileFor(tiny).path).use { w ->
                w.transcribe(samples, threads, initialPrompt = null) ?: throw IOException("The test was interrupted.")
                w.resetTimings()
                w.transcribe(samples, threads, initialPrompt = null) ?: throw IOException("The test was interrupted.")
                val (encode, decode) = w.timings()
                BenchResult(encode.toDouble(), decode.toDouble(), threads, System.currentTimeMillis())
            }
        }.also { models.saveBench(it) }
    }

    private fun readClip(context: Context): FloatArray {
        val bytes = context.assets.open(CLIP).use { it.readBytes() }
        val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
    }
}
