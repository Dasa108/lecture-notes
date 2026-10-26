package com.lecturenotes.whisper

data class WhisperModel(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    /**
     * Least total phone RAM to download and run this model. Measured peak memory while transcribing
     * (whisper.cpp, 30 s clip): Tiny 104 MB, Base 176 MB, Small 369 MB, Medium 843 MB, plus ~80 MB for
     * the app itself (Tiny peaked at 181 MB in the app on the test phone). Android kills large apps
     * long before RAM is full, so each minimum leaves at least 6× the model's peak as headroom.
     */
    val minRamGb: Double,
    /**
     * Encoder cost and decoding cost per 30 s window, both as multiples of *Tiny's encode time*, used
     * to turn the in-app Tiny benchmark into estimates for every model. The benchmark clip's decode is
     * unrealistically cheap (repetitive speech), so decoding is estimated from encode instead.
     * Measured with whisper-cli (greedy) on a Snapdragon 8s Gen 4: encode Tiny 0.54 s, Base ~1.2 s,
     * Small ~4.6 s, Medium ~12.5 s; decode Tiny 1.4 s, Base ~2.2 s, Small 3.6 s. The resulting
     * estimate for Small (~15 min per lecture hour) matches the app's own measured ~17 min.
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
        WhisperModel("tiny.en-q5_1", "Tiny", 32_166_155, 2.0, 1.0, 2.6,
            "Fastest. Good for quick drafts and older phones.",
            "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b"),
        WhisperModel("base.en-q5_1", "Base", 59_721_011, 3.0, 2.2, 4.0,
            "Balanced speed and accuracy for mid-range phones.",
            "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"),
        WhisperModel("small.en-q5_1", "Small", 190_098_681, 4.0, 8.5, 6.7,
            "Much better on lectures and technical vocabulary.",
            "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30"),
        WhisperModel("medium.en-q5_0", "Medium", 539_225_533, 6.0, 23.0, 9.0,
            "Most accurate. Flagship phones; slow on long recordings.",
            "76733e26ad8fe1c7a5bf7531a9d41917b2adc0f20f2e4f5531688a8c6cd88eb0"),
    )

    fun byId(id: String?): WhisperModel? = all.firstOrNull { it.id == id }

    /** Enough RAM to run [model] without Android killing the app. Phones report a little less than advertised. */
    fun fitsMemory(model: WhisperModel, device: DeviceInfo) = model.minRamGb <= device.totalRamGb + 0.5

    /** Room to download [model] (the download and a margin). Irrelevant once it's downloaded. */
    fun fitsStorage(model: WhisperModel, device: DeviceInfo) = model.sizeBytes * 2 < device.freeStorageBytes

    /** Models this phone can use: enough memory, and either already [downloaded] or room to download. */
    fun fitting(device: DeviceInfo, downloaded: (WhisperModel) -> Boolean = { false }): List<WhisperModel> =
        all.filter { fitsMemory(it, device) && (downloaded(it) || fitsStorage(it, device)) }

    /** Why [model] can't be downloaded or used on this phone, or null if it can. */
    fun whyNot(model: WhisperModel, device: DeviceInfo, downloaded: Boolean = false): String? = when {
        !fitsMemory(model, device) ->
            "Needs %.0f GB of RAM; this phone has %.1f GB, so it could be closed mid-transcription.".format(model.minRamGb, device.totalRamGb)
        !downloaded && !fitsStorage(model, device) ->
            "Needs ${(model.sizeBytes * 2 - device.freeStorageBytes) / 1_048_576 + 1} MB more free storage to download."
        else -> null
    }

    /**
     * With a benchmark: the most accurate model that transcribes a 1-hour lecture in at most
     * [TARGET_MINUTES_PER_HOUR] with the app open, or the fastest one if none is that quick.
     * Without one: a memory-based guess (never Medium). Null when no model fits this phone.
     */
    fun recommend(
        device: DeviceInfo,
        bench: BenchResult? = null,
        downloaded: (WhisperModel) -> Boolean = { false },
    ): WhisperModel? {
        val fits = fitting(device, downloaded).ifEmpty { return null }
        if (bench == null) return fits.lastOrNull { it.id != "medium.en-q5_0" } ?: fits.first()
        return fits.lastOrNull { bench.minutesPerHour(it) <= TARGET_MINUTES_PER_HOUR } ?: fits.first()
    }

    /** Why no model can run on this phone, or null if at least one fits. */
    fun unsupportedReason(device: DeviceInfo, downloaded: (WhisperModel) -> Boolean = { false }): String? {
        if (fitting(device, downloaded).isNotEmpty()) return null
        val smallest = all.first()
        return if (device.totalRamGb + 0.5 < smallest.minRamGb) {
            "On-device transcription needs at least %.0f GB of RAM, and this phone has %.1f GB."
                .format(smallest.minRamGb, device.totalRamGb)
        } else {
            val neededMb = (smallest.sizeBytes * 2 - device.freeStorageBytes) / 1_048_576 + 1
            "Not enough free storage: free up at least $neededMb MB to download the smallest speech model."
        }
    }

    /** A 1-hour lecture should be ready in about 20 minutes with the app open. */
    const val TARGET_MINUTES_PER_HOUR = 20.0
}
