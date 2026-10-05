package com.lecturenotes.whisper

import java.io.Closeable

class NativeSegment(val t0Ms: Long, val t1Ms: Long, textBytes: ByteArray, val noSpeechProb: Float) {
    val text: String = String(textBytes, Charsets.UTF_8).trim()
}

internal object WhisperNative {
    fun interface ProgressListener {
        fun onProgress(percent: Int)
    }

    init {
        System.loadLibrary("whisper_jni")
    }

    external fun nativeLoadBackends(libDir: String): String
    external fun nativeSystemInfo(): String
    external fun nativeAllowedCpus(): IntArray
    external fun nativeInit(modelPath: String): Long
    external fun nativeFree(handle: Long)
    external fun nativeAbort(handle: Long)
    external fun nativeCpuShrunk(handle: Long): Boolean
    external fun nativeTimings(handle: Long): FloatArray
    external fun nativeResetTimings(handle: Long)
    external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        threads: Int,
        initialPrompt: String?,
        listener: ProgressListener?,
    ): Array<NativeSegment>?
}

object Whisper {
    @Volatile
    private var backends: String? = null

    /** Loads the best ggml CPU backend for this phone. Must run before any model is loaded. */
    @Synchronized
    fun ensureBackends(nativeLibDir: String): String =
        backends ?: WhisperNative.nativeLoadBackends(nativeLibDir).also { backends = it }

    fun systemInfo(): String = WhisperNative.nativeSystemInfo()

    /** CPU indices the calling thread may currently run on (empty if unknown). */
    fun allowedCpus(): IntArray = WhisperNative.nativeAllowedCpus()
}

/** A loaded Whisper model. Not thread-safe for concurrent [transcribe] calls. */
class WhisperContext private constructor(private var handle: Long) : Closeable {

    /**
     * Transcribes 16 kHz mono samples. Segment times are relative to the first sample.
     * Returns null if decoding failed, [abort] was called, or the usable CPUs dropped
     * below [threads] mid-run (see [cpuShrunk]).
     */
    fun transcribe(
        samples: FloatArray,
        threads: Int,
        initialPrompt: String?,
        onProgress: (Int) -> Unit = {},
    ): List<NativeSegment>? {
        check(handle != 0L) { "context closed" }
        return WhisperNative.nativeTranscribe(handle, samples, threads, initialPrompt) { onProgress(it) }?.toList()
    }

    /** True if the last [transcribe] stopped because the OS took CPUs away; retry with fewer threads. */
    val cpuShrunk: Boolean get() = handle != 0L && WhisperNative.nativeCpuShrunk(handle)

    /** Encode and decode time (ms) since [resetTimings]. */
    fun timings(): Pair<Float, Float> = WhisperNative.nativeTimings(handle).let { (it.getOrNull(0) ?: 0f) to (it.getOrNull(1) ?: 0f) }

    fun resetTimings() = WhisperNative.nativeResetTimings(handle)

    fun abort() {
        if (handle != 0L) WhisperNative.nativeAbort(handle)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            WhisperNative.nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        fun load(modelPath: String): WhisperContext {
            val h = WhisperNative.nativeInit(modelPath)
            require(h != 0L) { "Could not load model: $modelPath" }
            return WhisperContext(h)
        }
    }
}
