package com.lecturenotes.speakers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The two small models speaker labelling needs; downloaded on first use (~31 MB). */
object SpeakerModels {
    private data class Spec(val fileName: String, val url: String, val sizeBytes: Long)

    /** pyannote segmentation 3.0 (int8): finds where speech turns change. */
    private val SEGMENTATION = Spec(
        "pyannote-segmentation-3.0.int8.onnx",
        "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.int8.onnx",
        1_540_506,
    )

    /** 3D-Speaker CAM++ trained on VoxCeleb (English): a voice "fingerprint" per speaker. */
    private val EMBEDDING = Spec(
        "campplus-en-voxceleb-16k.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
        29_596_978,
    )

    private fun dir(context: Context) = File(context.filesDir, "models/speakers").apply { mkdirs() }
    fun segmentation(context: Context) = File(dir(context), SEGMENTATION.fileName)
    fun embedding(context: Context) = File(dir(context), EMBEDDING.fileName)

    fun ready(context: Context) = listOf(SEGMENTATION to segmentation(context), EMBEDDING to embedding(context))
        .all { (spec, f) -> f.length() == spec.sizeBytes }

    /** Downloads whatever is missing; size-checked, written atomically. */
    suspend fun ensure(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val total = SEGMENTATION.sizeBytes + EMBEDDING.sizeBytes
        var done = 0L
        for ((spec, file) in listOf(SEGMENTATION to segmentation(context), EMBEDDING to embedding(context))) {
            if (file.length() != spec.sizeBytes) download(spec, file) { n -> onProgress((done + n).toFloat() / total) }
            done += spec.sizeBytes
        }
    }

    private fun download(spec: Spec, dst: File, onBytes: (Long) -> Unit) {
        val part = File(dst.path + ".part")
        val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != 200) throw IOException("Download failed (${conn.responseCode})")
            var n = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        n += r
                        onBytes(n)
                    }
                }
            }
            if (part.length() != spec.sizeBytes) throw IOException("Download was incomplete. Please try again.")
            if (!part.renameTo(dst)) throw IOException("Couldn't save the model")
        } finally {
            conn.disconnect()
            part.delete()
        }
    }
}
