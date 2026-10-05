package com.lecturenotes.whisper

data class WhisperModel(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val minRamGb: Double,
    /**
     * Cost relative to Tiny for the encoder and the (greedy) decoder, used to turn a Tiny benchmark into
     * estimates for every model. Measured on a Snapdragon 8s Gen 4 with the bundled 30 s speech clip:
     * encode per 30 s ≈ Tiny 0.6 s, Base 1.3 s, Small 4.8 s, Medium 13.5 s.
     */
    val encodeRatio: Double,
    val decodeRatio: Double,
    val description: String,
    val sha256: String,
) {
    val fileName: String get() = "ggml-$id.bin"
    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

object WhisperModels {
    val all = listOf(
        WhisperModel("tiny.en-q5_1", "Tiny", 32_166_155, 2.0, 1.0, 1.0,
            "Fastest. Good for quick drafts and older phones.",
            "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b"),
        WhisperModel("base.en-q5_1", "Base", 59_721_011, 3.0, 2.2, 1.3,
            "Balanced speed and accuracy for mid-range phones.",
            "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"),
        WhisperModel("small.en-q5_1", "Small", 190_098_681, 6.0, 8.5, 1.8,
            "Much better on lectures and technical vocabulary.",
            "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30"),
        WhisperModel("medium.en-q5_0", "Medium", 539_225_533, 8.0, 23.0, 2.5,
            "Most accurate. Flagship phones; slow on long recordings.",
            "76733e26ad8fe1c7a5bf7531a9d41917b2adc0f20f2e4f5531688a8c6cd88eb0"),
    )

    fun byId(id: String?): WhisperModel? = all.firstOrNull { it.id == id }

    /** Models that fit this phone's memory and storage. */
    fun fitting(device: DeviceInfo): List<WhisperModel> {
        val ram = device.totalRamGb + 0.5 // phones report a little less than the advertised RAM
        return all.filter { it.minRamGb <= ram && it.sizeBytes * 2 < device.freeStorageBytes }
    }

    /**
     * With a benchmark: the most accurate model that transcribes a 1-hour lecture in at most
     * [TARGET_MINUTES_PER_HOUR] with the app open. Without one: a memory-based guess (never Medium).
     */
    fun recommend(device: DeviceInfo, bench: BenchResult? = null): WhisperModel {
        val fits = fitting(device)
        if (bench == null) return fits.filter { it.id != "medium.en-q5_0" }.lastOrNull() ?: all.first()
        return fits.lastOrNull { bench.minutesPerHour(it) <= TARGET_MINUTES_PER_HOUR } ?: all.first()
    }

    /** A 1-hour lecture should be ready in about 20 minutes with the app open. */
    const val TARGET_MINUTES_PER_HOUR = 20.0
}
