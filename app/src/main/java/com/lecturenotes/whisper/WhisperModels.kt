package com.lecturenotes.whisper

data class WhisperModel(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val minRamGb: Double,
    /** Rough compute cost relative to tiny.en, used to extrapolate the benchmark. */
    val relativeCost: Double,
    val description: String,
    val sha256: String,
) {
    val fileName: String get() = "ggml-$id.bin"
    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

object WhisperModels {
    val all = listOf(
        WhisperModel("tiny.en-q5_1", "Tiny", 32_166_155, 2.0, 1.0,
            "Fastest. Good for quick drafts and older phones.",
            "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b"),
        WhisperModel("base.en-q5_1", "Base", 59_721_011, 3.0, 2.0,
            "Balanced speed and accuracy for mid-range phones.",
            "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"),
        WhisperModel("small.en-q5_1", "Small", 190_098_681, 6.0, 6.0,
            "Much better on lectures and technical vocabulary.",
            "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30"),
        WhisperModel("medium.en-q5_0", "Medium", 539_225_533, 8.0, 18.0,
            "Most accurate. Flagship phones; slow on long recordings.",
            "76733e26ad8fe1c7a5bf7531a9d41917b2adc0f20f2e4f5531688a8c6cd88eb0"),
    )

    fun byId(id: String?): WhisperModel? = all.firstOrNull { it.id == id }

    /**
     * RAM/storage-based pick. The device check (M2) refines this with a benchmark.
     */
    fun recommend(device: DeviceInfo): WhisperModel {
        val ram = device.totalRamGb + 0.5 // phones report a little less than the advertised RAM
        val fits = all.filter { it.minRamGb <= ram && it.sizeBytes * 2 < device.freeStorageBytes }
        val candidates = fits.filter { it.id != "medium.en-q5_0" } // medium is too slow as a default
        return candidates.lastOrNull() ?: all.first()
    }
}
